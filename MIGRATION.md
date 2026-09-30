# Migration Guide

This document records breaking changes and the steps to migrate between published
versions of `io.github.0xsequence:oms-wallet-kotlin-sdk`.

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
