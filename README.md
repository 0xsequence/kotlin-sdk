# OMS Wallet Kotlin SDK

Build non-custodial OMS Wallet experiences on Android and Kotlin with email and
OIDC auth, secure session restore, message signing, transactions, and indexer
reads through a single `OMSWallet` root object.

[API reference](https://docs.polygon.technology/wallets/sdk/kotlin/api-reference)

## Installation

Maven Central:

```kotlin
implementation("io.github.0xsequence:oms-wallet-kotlin-sdk:0.2.0")
```

## Compatibility

- Android 10 / API 29 or newer
- Android `compileSdk 34` or newer
- Java 17 Android compile options
- Kotlin/Android app using the Android library module

The SDK does not require consumer apps to enable core library desugaring.

## Before You Start

- Use an OMS publishable key for your project. Use sandbox/dev keys for local
  development and testnet flows.
- Register any OIDC return URI you use, such as `yourapp://auth/callback`, as an
  Android app link or intent filter before testing redirect auth.
- Start with sign-in, message signing, or balance reads. Transaction examples
  below use Polygon Amoy; mainnet transactions can move real funds.

## Quick Start

```kotlin
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWallet
import technology.polygon.omswallet.wallet.CompleteAuthResult

lifecycleScope.launch {
    val omsWallet = OMSWallet(
        context = this@MainActivity,
        publishableKey = "your-publishable-key",
    )

    omsWallet.wallet.startEmailAuth("user@example.com")

    // Use the one-time code the user enters from their email inbox.
    val result = omsWallet.wallet.completeEmailAuth("123456")
    if (result is CompleteAuthResult.WalletSelected) {
        val wallet = result.wallet
        println("Wallet address: ${wallet.address}")

        val signature =
            omsWallet.wallet.signMessage(
                network = Network.AMOY,
                message = "hello from OMS Wallet",
            )
        println("Signature: $signature")

        // Read balances from the chains your app needs.
        val balances =
            omsWallet.indexer.getBalances(
                walletAddress = wallet.address,
                networks = listOf(Network.POLYGON, Network.BASE, Network.ARBITRUM),
                includeMetadata = true,
            )
        println("Balances: $balances")
    }
}
```

The SDK derives wallet API and indexer routing from the publishable key.

## Overview

`OMSWallet` exposes two sub-clients:

| Property | Type | Description |
|---|---|---|
| `omsWallet.wallet` | `WalletClient` | Authentication, session, signing, access management, and transaction helpers. |
| `omsWallet.indexer` | `IndexerClient` | Token balance and on-chain query helpers. |

## Capabilities

- email sign-in
- OIDC ID-token sign-in
- OIDC redirect sign-in with built-in Google and Apple provider defaults
- Android Keystore-backed request signing
- persisted wallet session metadata
- wallet selection and wallet creation flows
- message and typed-data signing
- transaction sending, contract calls, and transaction status lookup
- wallet access listing and revocation
- message and typed-data signature verification
- native and token balance lookups plus transaction history through the indexer
- unit formatting and parsing helpers for raw token amounts

## Security Model

Completed wallet-session metadata is restored automatically when `OMSWallet` is
created. Session restore does not store private signing material. Pending email
OTP state is kept in memory. OIDC redirect state is stored only to complete the
browser redirect flow and is cleared when the flow completes, fails, or is
replaced.

Expired sessions are made inactive before protected wallet operations and throw
`OMSWalletSessionException` with `code = OMSWalletErrorCode.SessionExpired`. The SDK
clears the active signer/session state, but keeps expired completed-session
metadata in storage until the app starts a new auth flow or calls `signOut()`.
Subscribe with `omsWallet.wallet.onSessionExpired { event -> ... }` to route users
back to sign-in while preserving the expired session snapshot for reauth.
Listeners are delivered on the Android main thread.

## Authentication

The quick start uses automatic wallet selection. Starting a new auth flow
intentionally replaces any existing wallet session so users can re-authenticate
or switch accounts.

By default email OTP and OIDC ID-token auth completion use
`WalletSelectionBehavior.Automatic`. They select a wallet for the requested
wallet type, create one when none exists, and return
`CompleteAuthResult.WalletSelected`. If more than one matching wallet exists,
automatic mode selects the first matching wallet returned by the wallet API. Use manual
mode for apps that need to let users choose between multiple wallets.

Completed auth requests ask the wallet API for a one-week session lifetime by default
(`WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS`, `604_800` seconds).
Pass `sessionLifetimeSeconds` to `startEmailAuth`, `signInWithOidcIdToken`,
`startOidcRedirectAuth`, or `handleOidcRedirectCallback` to request a different
value from 1 through `WalletClient.MAX_SESSION_LIFETIME_SECONDS` (`2_592_000`
seconds, 30 days). Invalid lifetimes are reported as
`OMSWalletErrorCode.ValidationError`.

### OIDC ID-Token Auth

For native mobile sign-in, prefer an ID-token flow when the identity provider
supports it. For example, use Google Sign-In with Credential Manager to obtain
an ID token, then pass it to the wallet SDK:

```kotlin
val result =
    omsWallet.wallet.signInWithOidcIdToken(
        idToken = googleIdToken,
        issuer = "https://accounts.google.com",
        audience = "YOUR_WEB_CLIENT_ID",
    )
if (result is CompleteAuthResult.WalletSelected) {
    println("Wallet address: ${result.wallet.address}")
}
```

Pass `provider` and `providerLabel` to `signInWithOidcIdToken` for custom
ID-token providers when you want those labels stored in `omsWallet.wallet.session.auth`.

### OIDC Redirect Auth

Use redirect auth when the provider requires a browser authorization-code flow.
For the OMS-managed Google and Apple configurations, choose a fixed relay
provider and provide the deep link where the relay should return to your app:

```kotlin
val started = omsWallet.wallet.startOidcRedirectAuth(
    provider = OmsRelayOidcProviders.google,
    omsRelayReturnUri = "yourapp://auth/callback",
)

// Open started.authorizationUrl in Custom Tabs.

when (val callback = omsWallet.wallet.handleOidcRedirectCallback(intent.data?.toString())) {
    is OidcRedirectAuthResult.Completed -> when (val auth = callback.result) {
        is CompleteAuthResult.WalletSelected -> println("Wallet address: ${auth.wallet.address}")
        is CompleteAuthResult.WalletSelection -> Unit // Show your wallet picker.
    }
    OidcRedirectAuthResult.NotOidcRedirectCallback -> Unit
    OidcRedirectAuthResult.NoPendingAuth -> Unit
}
```

Provider or completion failures throw `OMSWalletException`. Register an app link
or intent filter that matches `omsRelayReturnUri`, then pass incoming links from
`onCreate` and `onNewIntent` to `handleOidcRedirectCallback`.

`OmsRelayOidcProviders.google` and `OmsRelayOidcProviders.apple` are immutable
OMS relay choices. Their client IDs, scopes, authorization parameters, and PKCE
mode are SDK-owned and cannot be edited. The SDK derives the provider callback
as `{walletApiUrl}/auth/waas/callback/{google|apple}` and stores the app return
URI in OIDC state. Apple `form_post` is handled by this relay.

| Flow | Provider value | App callback | Provider OAuth callback |
|---|---|---|---|
| OMS relay Google/Apple | `OmsRelayOidcProviders.google` / `OmsRelayOidcProviders.apple` | `omsRelayReturnUri` | SDK-derived OMS relay URL |
| Caller-owned OIDC | `CustomOidcProviderConfig` | `providerRedirectUri` | `providerRedirectUri` |

For a caller-owned provider, construct `CustomOidcProviderConfig`. Its
`providerRedirectUri` is required, and the custom-provider overload does not
accept `omsRelayReturnUri`:

```kotlin
val acmeProvider =
    CustomOidcProviderConfig(
        issuer = "https://login.acme.example",
        clientId = "acme-client-id",
        authorizationUrl = "https://login.acme.example/oauth/authorize",
        providerRedirectUri = "yourapp://auth/callback",
        provider = "acme",
        providerLabel = "Acme",
        scopes = listOf("openid", "email"),
    )

val started = omsWallet.wallet.startOidcRedirectAuth(provider = acmeProvider)
```

On mobile, prefer `signInWithOidcIdToken` over defining a custom Google or Apple
redirect configuration when the native provider SDK can supply an ID token.
Use custom redirect auth when you own the provider configuration or need a
browser-only provider.

Pass `loginHint` only to prefill a Google account, such as during session-expiry
reauth. When omitted, the SDK can use the previous active session email. Pass an
empty string to suppress `login_hint`. Custom provider scopes and auth mode are
used as supplied; empty scopes omit the `scope` parameter.

### Manual Wallet Selection

To use your own wallet-selection UI, pass
`walletSelection = WalletSelectionBehavior.Manual` when completing auth:

```kotlin
val result =
    omsWallet.wallet.completeEmailAuth(
        code = "123456",
        walletSelection = WalletSelectionBehavior.Manual,
    )
if (result is CompleteAuthResult.WalletSelection) {
    // Show result.pendingSelection.wallets in your app UI.
    val selected = result.pendingSelection.selectWallet("wallet-id")
    // or:
    // val selected = result.pendingSelection.createAndSelectWallet()
    println("Wallet address: ${selected.wallet.address}")
}
```

Manual mode completes auth but does not select or create a wallet until the app
calls `pendingSelection.selectWallet(...)` or
`pendingSelection.createAndSelectWallet(...)`. `pendingSelection.wallets` is
already filtered to the requested wallet type, so the app picker can show those
wallets plus a "Create New Wallet" action. Both SDK calls return the selected
wallet and persist it as the active wallet session.

For OIDC redirect auth, pass the behavior when starting redirect auth to store it
with the pending redirect state:

```kotlin
val started = omsWallet.wallet.startOidcRedirectAuth(
    provider = OmsRelayOidcProviders.google,
    omsRelayReturnUri = "yourapp://auth/callback",
    walletSelection = WalletSelectionBehavior.Manual,
)
```

You can also pass a callback value to override the pending redirect preference:

```kotlin
when (
    val result =
        omsWallet.wallet.handleOidcRedirectCallback(
            callbackUrl = intent.data?.toString(),
            walletSelection = WalletSelectionBehavior.Manual,
        )
) {
    is OidcRedirectAuthResult.Completed -> when (val auth = result.result) {
        is CompleteAuthResult.WalletSelection -> {
            // Show auth.pendingSelection.wallets in your app UI.
            val selected = auth.pendingSelection.selectWallet("wallet-id")
            println("Wallet address: ${selected.wallet.address}")
        }
        is CompleteAuthResult.WalletSelected -> Unit
    }
    OidcRedirectAuthResult.NotOidcRedirectCallback -> Unit
    OidcRedirectAuthResult.NoPendingAuth -> Unit
}
```

### Session State

Useful state checks:

```kotlin
val walletAddress = omsWallet.wallet.session.walletAddress
val expiresAt = omsWallet.wallet.session.expiresAt
val auth = omsWallet.wallet.session.auth
val authEmail = auth?.email
```

`expiresAt` is an ISO-8601 timestamp string returned by the wallet API. OIDC
sessions include issuer/provider metadata on `OMSWalletOidcSessionAuth`, so apps
can display built-in Google and Apple sessions by provider label.

`omsWallet.wallet.session` only reports completed wallet-session state. It does
not include pending auth progress. Show OTP or redirect waiting UI from the
method result that started the flow, not from session state.

Always pass incoming app links to `handleOidcRedirectCallback`. If it returns
`NoPendingAuth`, show sign-in UI and let the user start again.

A fresh SDK instance restores completed wallet sessions, including the session
expiry and auth metadata returned by the wallet API, but not email OTP pending
state.

Completed auth requests ask the wallet API for a one-week session lifetime by
default. For email auth, pass `sessionLifetimeSeconds` to `startEmailAuth`; the
SDK stores it with the pending OTP attempt and uses it when
`completeEmailAuth` succeeds. Custom values must be from 1 through
`WalletClient.MAX_SESSION_LIFETIME_SECONDS` (`2_592_000` seconds, 30 days). For
OIDC redirects, values passed to `startOidcRedirectAuth` are stored with the
pending redirect state and used on callback completion unless
`handleOidcRedirectCallback` overrides them.

Auth completion loads all wallet pages before selecting or creating a wallet. If
auth completes but wallet selection, wallet creation, or session persistence
fails, the SDK clears the in-memory auth session instead of retaining
unrecoverable transient state.

To end the session, call:

```kotlin
omsWallet.wallet.signOut()
```

`signOut()` clears in-memory wallet state even when persistent storage or
Keystore cleanup fails. Handle `OMSWalletStorageException` to report or retry a
persistent cleanup failure.

### Import a Wallet

Configure wallet import with audited AWS Nitro Enclave PCR0 measurements. The SDK rejects all-zero
debug measurements, verifies the attestation and request/response binding, and encrypts plaintext
keys locally before import.

```kotlin
val omsWallet =
    OMSWallet(
        context = context,
        publishableKey = "your-publishable-key",
        walletImport = WalletImportConfiguration(
            trustedPcr0s = listOf("your-audited-48-byte-pcr0-hex"),
        ),
    )

val imported =
    omsWallet.wallet.importWallet(
        privateKey = WalletImportPrivateKey.Ethereum("0x..."),
        reference = "Imported wallet",
    )
println(imported.wallet.keyOrigin == WalletKeyOrigin.Imported)
```

Ethereum imports accept 32 raw bytes or hexadecimal text. Solana imports accept a 32-byte seed,
64-byte keypair, or base58 text. The SDK does not persist plaintext imported keys. For
caller-managed HPKE, use `getWalletImportRecipientKey` followed by `importEncryptedWallet`; both
responses remain attestation verified.

## Core Workflows

### Sign and Verify Messages

Use the selected wallet for signing.

```kotlin
val network = Network.AMOY

val signResult = omsWallet.wallet.signMessage(
    network = network,
    message = "hello from OMS Wallet",
)

val verifyResult = omsWallet.wallet.isValidMessageSignature(
    network = network,
    message = "hello from OMS Wallet",
    signature = signResult,
)
```

For a selected Solana wallet, off-chain messages use the Solana-specific methods and do not require
a cluster:

```kotlin
val signature = omsWallet.wallet.signSolanaMessage("hello from Solana")
val valid = omsWallet.wallet.isValidSolanaMessageSignature(
    message = "hello from Solana",
    signature = signature,
)
```

### Sign Typed Data

```kotlin
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

val network = Network.AMOY
val typedDataJson =
    buildJsonObject {
        putJsonObject("types") {
            putJsonArray("EIP712Domain") {
                add(buildJsonObject {
                    put("name", "name")
                    put("type", "string")
                })
                add(buildJsonObject {
                    put("name", "version")
                    put("type", "string")
                })
                add(buildJsonObject {
                    put("name", "chainId")
                    put("type", "uint256")
                })
            }
            putJsonArray("Message") {
                add(buildJsonObject {
                    put("name", "contents")
                    put("type", "string")
                })
            }
        }
        put("primaryType", "Message")
        putJsonObject("domain") {
            put("name", "OMS Wallet")
            put("version", "1")
            put("chainId", JsonPrimitive(network.id.toLong()))
        }
        putJsonObject("message") {
            put("contents", "hello from OMS Wallet")
        }
    }

val typedSignature = omsWallet.wallet.signTypedData(
    network = network,
    typedData = typedDataJson,
)

val typedDataValid = omsWallet.wallet.isValidTypedDataSignature(
    network = network,
    typedData = typedDataJson,
    signature = typedSignature,
)
```

### Send a First Transaction

Transaction examples use Polygon Amoy and can move testnet funds; fund the
wallet from a faucet before sending and switch networks only when you are ready
for production.

```kotlin
import technology.polygon.omswallet.utils.parseUnits

val network = Network.AMOY

val txResult = omsWallet.wallet.sendTransaction(
    network = network,
    to = "0x1111111111111111111111111111111111111111",
    value = parseUnits("0.001", 18),
)
```

`sendTransaction` prepares and executes the transaction, then polls the wallet API
status endpoint briefly for an executed status or transaction hash. If the
transaction is still nonterminal when polling times out, the response keeps the
`txnId`, latest status, any available hash, and
`statusResolution = TransactionStatusResolution.TimedOut`. Set
`waitForStatus = false` to return after submission with `NotRequested`;
completed polling returns `Resolved`.
Transaction values are raw base-unit integers. Use `parseUnits` to convert
human-entered decimal values before sending. Import the helpers from
`technology.polygon.omswallet.utils`.

### Query Balances

```kotlin
val walletAddress = requireNotNull(omsWallet.wallet.walletAddress)

val tokenBalances = omsWallet.indexer.getBalances(
    walletAddress = walletAddress,
    networks = listOf(Network.POLYGON, Network.BASE, Network.ARBITRUM),
    contractAddresses = listOf("0x3333333333333333333333333333333333333333"),
    includeMetadata = true,
)

tokenBalances.nativeBalances.forEach { balance ->
    println("${balance.symbol} ${balance.balance}")
}

tokenBalances.balances.forEach { balance ->
    println("${balance.contractInfo?.symbol.orEmpty()} ${balance.contractInfo?.decimals ?: 0}")
}
```

Query native SOL and fungible token balances through the Solana indexer gateway:

```kotlin
val result =
    omsWallet.indexer.getSolanaBalances(
        walletAddress = "solana-wallet-address",
        networks = listOf(SolanaNetwork.Mainnet, SolanaNetwork.Devnet),
    )
result.balances.forEach(::println)
```

Pass `includeMetadata = true` when you need token contract details or NFT/token
metadata from `balance.contractInfo` and `balance.tokenMetadata`.

### Query Transaction History

```kotlin
val history = omsWallet.indexer.getTransactionHistory(
    walletAddress = walletAddress,
    networks = listOf(Network.POLYGON, Network.BASE, Network.ARBITRUM),
)
```

### Advanced Transactions

For raw calldata or transaction parameters beyond `to` and `value`, use the request overload:

```kotlin
val network = Network.AMOY

val txResult = omsWallet.wallet.sendTransaction(
    network = network,
    request = SendTransactionRequest(
        to = "0x3333333333333333333333333333333333333333",
        value = parseUnits("0", 18),
        data = "0x1234",
        mode = TransactionMode.Native,
    ),
)
```

For ABI-style contract calls, use `callContract`:

```kotlin
val txResult = omsWallet.wallet.callContract(
    network = network,
    contract = "0x3333333333333333333333333333333333333333",
    method = "transfer(address,uint256)",
    args =
        listOf(
            AbiArg(type = "address", value = JsonPrimitive("0x1111111111111111111111111111111111111111")),
            AbiArg(type = "uint256", value = JsonPrimitive("1000000000000000000")),
        ),
)
```

To pick the first fee option the selected wallet can afford, pass the built-in
selector:

```kotlin
val txResult = omsWallet.wallet.sendTransaction(
    network = network,
    request = SendTransactionRequest(
        to = "0x3333333333333333333333333333333333333333",
        value = parseUnits("0", 18),
        data = "0x1234",
        mode = TransactionMode.Native,
    ),
    selectFeeOption = FeeOptionSelector.firstAvailable,
)
```

For a custom fee picker, return the selected option's `selection`:

```kotlin
val txResult = omsWallet.wallet.sendTransaction(
    network = network,
    request = SendTransactionRequest(
        to = "0x3333333333333333333333333333333333333333",
        value = parseUnits("0", 18),
        data = "0x1234",
        mode = TransactionMode.Native,
    ),
) { feeOptions ->
    feeOptions.first().selection
}
```

The selector receives `FeeOptionWithBalance` values. `balance` is the selected
wallet's raw indexer balance for that fee token when available. `available` is
formatted with the token decimals, while `availableRaw` keeps the raw integer
value. `decimals` is exposed as `Int?`. `selection` preserves the
API-provided `tokenID` when present and falls back to the token symbol. Sponsored
transactions skip fee selection; unsponsored transactions fail before execute
when no fee option can be selected.

To refresh a transaction later:

```kotlin
val status = omsWallet.wallet.getTransactionStatus(txnId = txResult.txnId)
```

For a selected Solana wallet, amounts are smallest units (lamports for SOL and base units for SPL
tokens):

```kotlin
val result =
    omsWallet.wallet.sendSolanaTransfer(
        network = SolanaNetwork.Devnet,
        asset = "SOL",
        to = "solana-recipient-address",
        amount = BigInteger("1000000"),
    )
```

## Reference

### Errors

Public SDK APIs throw `OMSWalletException` subclasses with stable fields such as
`code`, `operation`, `status`, nullable `retryable`, and `txnId`. When a failure comes
from a remote OMS service response or transport failure, the error also includes
`upstreamError` with normalized wallet API or indexer details for logging and
service-specific troubleshooting. For `OMSWalletException` values, branch
application logic on the SDK-level `code`.

For transaction writes, `OMS_TRANSACTION_EXECUTION_UNCONFIRMED` means the SDK
has a `txnId` from preparation, but the execute request failed before the SDK
could confirm whether the transaction was submitted; do not blindly resend the
same write. `OMS_TRANSACTION_STATUS_LOOKUP_FAILED` means the transaction was
submitted but status polling failed, so retry status lookup with the returned
`txnId`.
`retryable` describes the failed SDK operation, not the whole user intent.

```kotlin
try {
    omsWallet.wallet.startEmailAuth("user@example.com")
} catch (error: OMSWalletException) {
    println("${error.code} ${error.operation?.id} ${error.upstreamError}")
}
```

### Unit Formatting

```kotlin
val rawAmount = parseUnits("1.5", 18)
val displayAmount = formatUnits(rawAmount, 18)
```

### Wallet ID Tokens and Access

```kotlin
import kotlinx.serialization.json.JsonPrimitive

val idToken = omsWallet.wallet.getIdToken(ttlSeconds = 300u)
val scopedIdToken =
    omsWallet.wallet.getIdToken(
        ttlSeconds = 3_600u,
        customClaims = mapOf("role" to JsonPrimitive("member")),
    )

val credentials = omsWallet.wallet.listAccess(pageSize = 25u)
omsWallet.wallet.listAccessPages(pageSize = 25u).collect { page ->
    println(page.grants)
}

credentials
    .firstOrNull { !it.credential.isCaller }
    ?.let { omsWallet.wallet.revokeAccess(credentialId = it.credential.credentialId) }
```

For an owner-approved smart session, inspect the remote credential before showing consent, then
authorize bounded EVM transfer grants. Backend credential registration and execution stay outside
this SDK surface.

```kotlin
val credentialId = "remote-credential-id"
val metadata = omsWallet.wallet.inspectRemoteCredential(credentialId)
showConsentScreen(metadata)

val session =
    omsWallet.wallet.authorizeRemoteAccess(
        credentialId = credentialId,
        network = Network.POLYGON,
        grants =
            listOf(
                SmartSessionGrant.NativeTransfer(
                    to = "0x1111111111111111111111111111111111111111",
                    limit = BigInteger("1000000000000000"),
                ),
            ),
        expiresAt = "2099-01-01T00:00:00Z",
    )

val details = omsWallet.wallet.getRemoteAccessSession(session.sessionId)
val usage = omsWallet.wallet.getRemoteAccessSessionUsage(session.sessionId, Network.POLYGON)
omsWallet.wallet.revokeAccess(credentialId, session.sessionId)
```

## API Reference

The full public API surface is documented in [docs/api.md](docs/api.md).

## Android Compatibility Notes

The published artifact declares `minSdk 24` so apps with lower manifest floors,
including Expo/React Native apps, can include the dependency. This is only a
packaging compatibility floor: the SDK requires Android 10 / API 29 or newer at
runtime because the service endpoints require TLS 1.3.

## Sample App

This repository includes an Android sample app in [`app/`](app/) that demonstrates:

- Google sign-in with Android Credential Manager
- Google and Apple OIDC redirect sign-in
- email sign-in
- custom session lifetime input for expiry testing
- expired-session reauth UI
- wallet selection after sign-in
- message signing and verification
- transaction sending

The sample app uses additional Google Sign-In / AndroidX Credential Manager
dependencies and therefore compiles with SDK 35. That sample app requirement
does not raise the published SDK artifact's consumer `compileSdk` floor.

## Build From Source

To enable the local pre-push Kotlin style gate for this checkout:

```sh
tools/install-git-hooks.sh
```

The hook runs `./gradlew ktlintCheck` before push. This is intentionally local
and is not wired into GitHub CI.

```sh
./gradlew :oms-wallet-kotlin-sdk:testDebugUnitTest
./gradlew ktlintCheck
./gradlew :oms-wallet-kotlin-sdk:lintDebug
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

## Publishing

See [publishing.md](publishing.md) for release PR and Maven Central publishing
steps.

## License

Apache-2.0. See [LICENSE](LICENSE).
