# Migration Guide

This document records breaking changes and the steps to migrate between published
versions of `io.github.0xsequence:oms-wallet-kotlin-sdk`.

## 0.4.0

### Active wallet replaces `walletAddress`

`omsWallet.wallet.walletAddress` was removed. Read the active wallet from
`omsWallet.wallet.activeWallet`, which is a `Wallet` (`id`, `type`, `address`, `reference`,
`keyOrigin`) or `null` when signed out. Branch on `type` before passing the address to
family-specific code:

```kotlin
// 0.3.x
val address = omsWallet.wallet.walletAddress

// 0.4.0
val activeWallet = omsWallet.wallet.activeWallet
if (activeWallet?.type == WalletType.Ethereum) {
    val ethereumAddress = activeWallet.address
}
```

The `walletAddress` property was also removed from `WalletActivationResult` (formerly
`WalletSelectionResult`) and `CompleteAuthResult.WalletSelected`. Use `result.wallet.address`. Code that creates or
destructures these data classes positionally must drop the removed first component.

### `session` is null when signed out

`OMSWalletSessionState` was renamed to `OMSWalletSession`, and `omsWallet.wallet.session` is now
`OMSWalletSession?`. Previously it returned an object whose fields were all `null` when signed
out. The `walletAddress` property was removed, and `expiresAt` and `auth` are now non-null.
`session` is non-null exactly when `activeWallet` is.

```kotlin
// 0.3.x
val email = omsWallet.wallet.session.auth?.email

// 0.4.0
val email = omsWallet.wallet.session?.auth?.email
```

`OMSWalletSessionExpiredEvent` gained `wallet: Wallet?`, and its `session` is an
`OMSWalletSession` without `walletAddress`. `wallet` is `null` when the credential expired while a
manual wallet selection was still pending. `wallet` is the first constructor property, so code that
creates or destructures the event positionally must account for it; prefer named arguments.

### Sliding session expiry

Wallet API versions with sliding expiry extend a session's expiry with use, up to the requested lifetime.
`OMSWalletSession.expiresAt` is the current expiry, which the SDK refreshes after restoring a
session, while the session is in use, and before treating a locally expired session that is active
in memory as ended; do not cache it as a fixed end time. `WalletCredential.expiresAt` is the expiry
as reported when the credential was returned and is not updated afterwards. A stored session that
is already past its expiry when the SDK restores it is discarded without asking the wallet API.

- `WalletClient.MAX_SESSION_LIFETIME_SECONDS` rose from 30 days (`2_592_000`) to 180 days
  (`15_552_000`). `WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS` rose from 7 days (`604_800`) to the
  same maximum, so sign-in without `sessionLifetimeSeconds` now requests 180 days. An idle session
  still ends about 10 days after its last use; pass a shorter `sessionLifetimeSeconds` if you need a
  hard limit.
  Wallet API versions without sliding expiry clamp the requested lifetime to their 30-day maximum
  and have no idle timeout, so the default gives a fixed 30-day session there.
- When the wallet API rejects the session credential as unknown or expired (WaaS `KeyNotFound`
  7202 or `KeyExpired` 7203), the session ends as on local expiry (`onSessionExpired` fires once) and
  the call throws `OMSWalletSessionException` (`OMS_SESSION_EXPIRED`, no `upstreamError`) instead of
  `OMSWalletRequestException`. This includes a rejected `Execute`, which never ran: it throws
  `OMS_SESSION_EXPIRED` instead of `OMS_TRANSACTION_EXECUTION_UNCONFIRMED`. Status polling after a
  submitted transaction keeps `OMS_TRANSACTION_STATUS_LOOKUP_FAILED` and its `txnId`.
- When the wallet API answers the SDK's expiry check (`GetCredential`) with `Unauthorized` 7207, the
  credential was revoked, and the session ends the same way. 7207 from any other call stays a
  request error and keeps the session.
- Reading a credential's expiry extends an idle session, so the expiry timer no longer reads it as
  soon as the session looks expired: it waits until 5 minutes past the expected server-side
  expiry, which is later than the local one when the session was used after its expiry was last
  read. Without
  SDK activity, an idle session's `onSessionExpired` event can arrive up to about 6 hours after the
  wallet API ended it (it extends the expiry only in steps of at least 6 hours; within 5 minutes
  when the lifetime is shorter than the idle window), given an accurate device clock and the app running in the foreground.
  Protected operations on a locally expired session still check right away.
- If the wallet API cannot be reached when a session reaches its local expiry, or returns an expiry
  the SDK cannot parse, the session is kept: the check is retried later, and protected operations
  throw the request error (`OMSWalletResponseException`, `OMS_INVALID_RESPONSE`, for an unparseable
  expiry) instead of `OMS_SESSION_EXPIRED`.
- Protected operations that find the session locally expired share one expiry check. Cancelling the
  operation that started it no longer cancels the others; they run the check again.

### Signed requests go one at a time

WaaS needs every signed request's nonce to be higher than the last one it accepted, so concurrent
calls can reach it out of order and be rejected with `PreconditionFailed` (code `7206`, HTTP 412).
Requests signed with one credential now go one at a time, in the order they were made, for every
`OMSWallet` of that session in the process, so concurrent calls no longer reject each other; a
burst of calls takes longer. A sign-in after sign-out uses a new key, whose requests do not wait
for the previous key's. A call that waits for its turn past the 30-second request deadline
fails with `OMS_REQUEST_FAILED` and was never sent.

### Rejected nonces are resent

Requests from another process or device signed with the same credential are not ordered with
these. For a request WaaS still rejects for its nonce, the SDK signs it again with a fresh nonce
and resends it, at most 2 times, before surfacing the rejection as `OMS_REQUEST_FAILED` (status 412). WaaS rejects the nonce before running
the method, so a resent `Execute` cannot send a transaction twice, and a still-rejected `Execute`
is reported as `OMS_REQUEST_FAILED`, not `OMS_TRANSACTION_EXECUTION_UNCONFIRMED`. The same applies to
an `Execute` that WaaS refuses with `InvalidSignature` (7201), `UsageLimitExceeded` (7205) or
`Unauthorized` (7207): WaaS refuses these before submitting anything, so they are request errors too.
For Tron, so are `InvalidRequest` (7200) and `InvalidFeeOption` (7310), which WaaS raises while it
prices the transaction again, before broadcasting it. An `Execute` whose request the SDK never sent,
because signing failed or its deadline passed first, also throws the request error without `txnId`.

### Signed requests time out after 30 seconds

Every signed wallet API request now fails if it has not finished within 30 seconds, counting its
wait behind an expiry read, signing, nonce resends, and the HTTP call, whatever timeouts the
`OkHttpClient` passed to `OMSWallet` sets (shorter ones still apply). A request that never reached
the wallet API is not sent late. An `Execute` that times out after it was sent stays
`OMS_TRANSACTION_EXECUTION_UNCONFIRMED` with its `txnId`.

### Wallet-import key strings are redacted

`toString()` of `WalletImportPrivateKey.Ethereum`, `Tron`, and `Solana` no longer includes the key:
it returns `Ethereum(value=<redacted>)` (and likewise for the others). They remain data classes, so
`value`, equality, `copy`, and destructuring are unchanged.

### Reverting transactions are request failures

When the wallet API refuses to send a transaction because it would revert (WaaS
`TransactionSimulationFailed`, code `7314`, HTTP 400), the transaction methods throw
`OMSWalletRequestException` (`OMS_REQUEST_FAILED`, status `400`, `retryable = false`, no `txnId`) with
`upstreamError.name = "TransactionSimulationFailed"` and `upstreamError.code = "7314"`. 0.3.x reported
this as `OMS_TRANSACTION_EXECUTION_UNCONFIRMED`; the transaction was closed as failed and never sent,
so fix it before sending again. Execute failures that may have reached the wallet API still throw
`OMS_TRANSACTION_EXECUTION_UNCONFIRMED` with its `txnId`.

### One-time sign-in after upgrading

Saved sessions now record the full active wallet, including its type. Sessions saved by 0.3.x do
not, so 0.4.0 discards them on load and users sign in once after upgrading.

### Contract method names

`callContract` (and the new `callTronContract`) now require `method` to be a bare function name
such as `"transfer"`. The wallet service builds the signature from the `args` types and never
accepted full signatures; the SDK now rejects values such as `"transfer(address,uint256)"` with
`OMSWalletValidationException` before sending a request.

```kotlin
// 0.3.x
omsWallet.wallet.callContract(network, contract, method = "transfer(address,uint256)", args = args)

// 0.4.0
omsWallet.wallet.callContract(network, contractAddress, method = "transfer", args = args)
```

The contract parameter of `callContract` and `callTronContract` is now named `contractAddress`.
Positional calls are unchanged; update named arguments:

```kotlin
// 0.3.x
omsWallet.wallet.callContract(network = network, contract = token, method = "transfer", args = args)

// 0.4.0
omsWallet.wallet.callContract(network = network, contractAddress = token, method = "transfer", args = args)
```

### Signature verification targets a wallet address

Every verification method (`isValidMessageSignature`, `isValidTypedDataSignature`,
`isValidSolanaMessageSignature`, `isValidTronMessageSignature`, `isValidTronTypedDataSignature`)
takes an optional trailing `walletAddress` and no `walletId`. The SDK always sends `networkFamily`
and `walletAddress` to the wallet service and never sends `walletId`; `isValidTypedDataSignature`
now also sends `networkFamily = "evm"`.

- Passing `walletAddress` verifies any wallet and no longer requires a session, so the EVM and
  Solana methods now work while signed out.
- Omitting `walletAddress` uses the active wallet's address. Without a session the methods still
  throw `OMSWalletSessionException`. When the active wallet belongs to another family (for example
  an active Ethereum wallet for `isValidSolanaMessageSignature`, or an active Tron wallet for
  `isValidTypedDataSignature`), they now throw `OMSWalletValidationException` before any request.
  Previously such calls reached the wallet service, which rejected them with an
  `OMSWalletRequestException`.
- A given but empty or whitespace-only `walletAddress` throws `OMSWalletValidationException`
  (`walletAddress must not be empty`) before any request. Omit `walletAddress` to verify against
  the active wallet.

```kotlin
// 0.3.x: always the active wallet, which had to be signed in
omsWallet.wallet.isValidMessageSignature(network, message, signature)

// 0.4.0: the active wallet (unchanged call), or any Ethereum wallet, even while signed out
omsWallet.wallet.isValidMessageSignature(network, message, signature)
omsWallet.wallet.isValidMessageSignature(network, message, signature, walletAddress = "0x…")
```

### Renamed types

| 0.3.x | 0.4.0 |
|---|---|
| `WalletSelectionResult` | `WalletActivationResult` |
| `TokenBalancesResult` | `BalancesResult` |
| `OidcRedirectAuthMode` | `OidcAuthMode` (wire values `auth-code`/`auth-code-pkce` are unchanged, so pending redirects saved by 0.3.x still load) |

```kotlin
// 0.3.x
val result: WalletSelectionResult = omsWallet.wallet.useWallet(walletId)
val balances: TokenBalancesResult = omsWallet.indexer.getBalances(walletAddress)

// 0.4.0
val result: WalletActivationResult = omsWallet.wallet.useWallet(walletId)
val balances: BalancesResult = omsWallet.indexer.getBalances(walletAddress)
```

### Transaction status polling options

`TransactionStatusPollingOptions` properties were renamed to match the TypeScript and Swift SDKs.
The defaults (`400`, `5`, `2_000`, `60_000` milliseconds/polls), the constructor order, and the
polling behavior are unchanged. Validation messages use the new names (for example
`"timeoutMs must not be negative"`).

| 0.3.x | 0.4.0 |
|---|---|
| `fastPollIntervalMillis` | `fastIntervalMs` |
| `fastPollCount` | `fastPollCount` |
| `pollIntervalMillis` | `intervalMs` |
| `timeoutMillis` | `timeoutMs` |

```kotlin
// 0.3.x
TransactionStatusPollingOptions(timeoutMillis = 120_000L, pollIntervalMillis = 3_000L)

// 0.4.0
TransactionStatusPollingOptions(timeoutMs = 120_000L, intervalMs = 3_000L)
```

### `sendTransaction(network, to, value)` takes `mode`

The `to`/`value` overload gained `mode: TransactionMode = TransactionMode.Relayer` before
`waitForStatus`, matching `callContract` and `sendSolanaTransfer`. Positional calls that passed
`waitForStatus` fourth must name it:

```kotlin
// 0.3.x
omsWallet.wallet.sendTransaction(network, to, value, false)

// 0.4.0
omsWallet.wallet.sendTransaction(network, to, value, waitForStatus = false)
omsWallet.wallet.sendTransaction(network, to, value, mode = TransactionMode.Native)
```

### OIDC parameter order

Parameters now follow the same order as the TypeScript and Swift SDKs. Defaults are unchanged and
named arguments are unaffected:

- `signInWithOidcIdToken(idToken, issuer, audience, walletType, walletSelection, sessionLifetimeSeconds, provider, providerLabel)`:
  `walletType` now comes before `walletSelection`.
- `startOidcRedirectAuth(provider: CustomOidcProviderConfig, walletType, walletSelection, sessionLifetimeSeconds, loginHint, authorizeParams)`:
  `loginHint` now comes before `authorizeParams`.

```kotlin
// 0.3.x
omsWallet.wallet.signInWithOidcIdToken(idToken, issuer, audience, WalletSelectionBehavior.Manual, WalletType.Solana)

// 0.4.0
omsWallet.wallet.signInWithOidcIdToken(idToken, issuer, audience, WalletType.Solana, WalletSelectionBehavior.Manual)
```

### Address-already-imported errors

Importing a key whose address is already imported (WaaS `AddressAlreadyImported`, code `7313`)
now throws `OMSWalletRequestException` with `code = OMSWalletErrorCode.WalletAddressAlreadyImported`
(`OMS_WALLET_ADDRESS_ALREADY_IMPORTED`), `status = 409`, and `retryable = false`. It previously
used `OMSWalletErrorCode.RequestFailed`:

```kotlin
// 0.3.x
catch (error: OMSWalletRequestException) {
    if (error.code == OMSWalletErrorCode.RequestFailed && error.status == 409) showAlreadyImported()
}

// 0.4.0
catch (error: OMSWalletRequestException) {
    if (error.code == OMSWalletErrorCode.WalletAddressAlreadyImported) showAlreadyImported()
}
```

### Indexer page cursors

`TokenBalancesPageRequest` and `TokenBalancesPage` gained trailing `column`, `before`, `after`, and
`sort` properties (`SortBy`, `SortOrder`). Omitted request fields are not sent. A page response
whose `sort` entries are malformed or use an order other than `DESC`/`ASC` now fails with
`OMSWalletResponseException` (`OMS_INVALID_RESPONSE`); previously the SDK ignored those fields.
Because these are data-class properties, `TokenBalancesPage` equality and `toString()` now include
them.

### Wallet responses

Ethereum wallet addresses returned by the wallet API must be `0x` followed by 40 hexadecimal
characters (checksum casing is not enforced). Other values fail with `OMSWalletResponseException`
(`OMS_INVALID_RESPONSE`). Wallet-family checks now use the wallet's stored type instead of the
address shape.

### Claimable wallets

Automatic wallet selection now claims a claimable (preallocated) wallet of the requested type
when the user owns none, instead of creating a new one. Owned wallets still win, and a wallet is
still created when there is nothing to claim. A failed claim fails sign-in.

- `CompleteAuthResult.WalletSelected` gained `claimableWallets: List<Wallet>`, the claimable
  wallets the sign-in did not claim. It is the last constructor property and defaults to an
  empty list.
- `PendingWalletSelection` gained `claimableWallets: List<Wallet>`, filtered to the requested
  wallet type like `wallets`, and `claimAndSelectWallet(walletId: String)`, which claims one of
  them and completes the selection like `selectWallet`. Its errors carry
  `OMSWalletOperation.PendingWalletSelectionClaimAndSelectWallet`
  (`wallet.pendingWalletSelection.claimAndSelectWallet`).
- `WalletClient.claimWallet(walletId: String): WalletActivationResult` claims and selects a
  claimable wallet, during a pending manual selection or with an active session. Its errors carry
  `OMSWalletOperation.WalletClaimWallet` (`wallet.claimWallet`).

### Exhaustive `when` expressions

These public enums gained cases. Update exhaustive `when` expressions over them:

- `WalletType.Tron`
- `OMSWalletOperation.WalletSignTronMessage`, `WalletSignTronTypedData`,
  `WalletIsValidTronMessageSignature`, `WalletIsValidTronTypedDataSignature`,
  `WalletSendTronTransaction`, `WalletCallTronContract`, `IndexerGetTronBalances`,
  `WalletClaimWallet`, and `PendingWalletSelectionClaimAndSelectWallet`
- `OMSWalletErrorCode.WalletAddressAlreadyImported`

`WalletImportPrivateKey` also gained the `Tron` and `TronBytes` subtypes; exhaustive `when`
expressions over it need branches for them.

## 0.3.0

### Wallet types and key origin

`WalletType` now includes `Solana`. Update exhaustive `when` expressions to handle Solana wallets
before passing wallet addresses or messages to Ethereum-only code.

Every `Wallet` now has a required `keyOrigin`. Wallets returned by the SDK already include it.
Tests, mocks, or adapters that construct `Wallet` values directly must pass
`WalletKeyOrigin.Enclave` or `WalletKeyOrigin.Imported`:

```kotlin
val wallet =
    Wallet(
        id = "wallet-id",
        type = WalletType.Ethereum,
        address = "0x1111111111111111111111111111111111111111",
        keyOrigin = WalletKeyOrigin.Enclave,
    )
```

### Error enum cases

`OMSWalletErrorCode` now includes `AttestationVerificationFailed`. `OMSWalletOperation` now
includes these operation identifiers:

- `WalletImportWallet`, `WalletGetImportRecipientKey`, and `WalletImportEncryptedWallet`
- `WalletInspectRemoteCredential`, `WalletAuthorizeRemoteAccess`,
  `WalletGetRemoteAccessSession`, and `WalletGetRemoteAccessSessionUsage`
- `WalletSignSolanaMessage`, `WalletIsValidSolanaMessageSignature`, and
  `WalletSendSolanaTransfer`
- `IndexerGetSolanaBalances`

Update exhaustive `when` expressions over either public enum to handle the new cases.

### Access grants and revocation

`CredentialInfo` was renamed to `WalletCredential`. Access listing now distinguishes direct
credentials from remote smart sessions:

- `listAccess()` returns `List<AccessGrant>` instead of `List<CredentialInfo>`.
- `ListAccessResponse` was replaced by `AccessGrantPage`.
- `listAccessPage()` and `listAccessPages()` return access-grant pages.

Narrow on each grant before reading remote-session fields:

```kotlin
for (grant in omsWallet.wallet.listAccess()) {
    when (grant) {
        is AccessGrant.Direct -> println(grant.credential.credentialId)
        is AccessGrant.Remote -> {
            // Display the remote app/session and its authorized permissions.
            println("${grant.sessionId} ${grant.metadata} ${grant.grants}")
        }
    }
}
```

The public `revokeAccess` parameter changed from `targetCredentialId` to `credentialId`. For a
direct grant, omit `sessionId`. For a remote grant, its `sessionId` is required and revokes exactly
that session; revoke each session separately when a remote credential has more than one:

```kotlin
// 0.2.0
omsWallet.wallet.revokeAccess(targetCredentialId = credentialId)

// 0.3.0
val grant = omsWallet.wallet.listAccess().firstOrNull { !it.credential.isCaller }
if (grant != null) {
    when (grant) {
        is AccessGrant.Direct ->
            omsWallet.wallet.revokeAccess(credentialId = grant.credential.credentialId)
        is AccessGrant.Remote ->
            omsWallet.wallet.revokeAccess(
                credentialId = grant.credential.credentialId,
                sessionId = grant.sessionId,
            )
    }
}
```

Authentication results and pending wallet selections expose `WalletCredential` through their
existing `credential` property.

### Sponsored fee selectors

Fee selections can now include the quoted option index. Custom selectors should return the
provided option's `selection` value instead of reconstructing a selection from its token:

```kotlin
val selector = FeeOptionSelector { options -> options.firstOrNull()?.selection }
```

`FeeOptionWithBalance` now stores `selection` as its second constructor property. Code that creates
or destructures this data class positionally must account for the inserted property; named
arguments and property access avoid component-order mistakes:

```kotlin
// 0.2.0
val oldOption = FeeOptionWithBalance(feeOption, balance, available, availableRaw, decimals)
val (quotedFee, quotedBalance) = oldOption

// 0.3.0
val option =
    FeeOptionWithBalance(
        feeOption = feeOption,
        balance = balance,
        available = available,
        availableRaw = availableRaw,
        decimals = decimals,
    )
val quotedFee = option.feeOption
val quotedBalance = option.balance
```

Sponsored transactions invoke the selector with an empty list. Return `null` to acknowledge the
free fee, or throw to stop execution. `FeeOptionSelector.firstAvailable` already handles both
sponsored and non-sponsored transactions.
