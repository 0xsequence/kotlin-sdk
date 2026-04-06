# Public API

This document describes the intended public API for external consumers of the Polygon Kotlin SDK.

## Entry Point

```kotlin
PolygonSdk(
    context: Context,
    projectAccessKey: String,
    environment: SequenceEnvironment = SequenceEnvironment(),
    okHttpClient: OkHttpClient = OkHttpClient(),
)
```

```kotlin
val polygonSdk.wallet: SequenceWalletClient
val polygonSdk.api: SequenceApiClient
val polygonSdk.indexer: SequenceIndexerClient
```

## Wallet

```kotlin
val wallet.hasPendingSignIn: Boolean
val wallet.walletAddress: String?
val wallet.signerAddress: String?
```

```kotlin
fun wallet.clearSession()
```

```kotlin
suspend fun wallet.signInWithEmail(
    email: String,
): CommitVerifierResponse
```

```kotlin
suspend fun wallet.completeEmailSignIn(
    code: String,
    walletType: String = "Ethereum_EOA",
): SequenceWallet
```

```kotlin
suspend fun wallet.completeEmailSignIn(
    code: String,
    walletType: String = "Ethereum_EOA",
    selectWallet: suspend (List<SequenceWallet>) -> SequenceWallet,
): SequenceWallet
```

```kotlin
suspend fun wallet.signMessage(
    chainId: String,
    message: String,
): SignMessageResult
```

```kotlin
suspend fun wallet.sendTransaction(
    chainId: String,
    to: String,
    value: String,
): SendTransactionResult
```

## API Service

```kotlin
suspend fun api.isValidMessageSignature(
    chainId: String,
    walletAddress: String,
    message: String,
    signature: String,
): IsValidMessageSignatureResult
```

## Indexer Service

```kotlin
suspend fun indexer.getTokenBalances(
    chainId: String,
    contractAddress: String,
    walletAddress: String,
    includeMetadata: Boolean,
): TokenBalancesResult
```

## Environment

```kotlin
class SequenceEnvironment(
    val walletApiUrl: String = SequenceEnvironment.walletApiUrlDefault,
    val apiRpcUrl: String = SequenceEnvironment.apiRpcUrlDefault,
    val indexerUrlTemplate: String = SequenceEnvironment.indexerUrlTemplateDefault,
)
```

```kotlin
fun environment.indexerUrlForChainId(chainId: String): String
```

```kotlin
fun SequenceEnvironment.Companion.demoDefaults(): SequenceEnvironment
```

## Public Models

```kotlin
data class CommitVerifierResponse(
    val verifier: String?,
    val loginHint: String?,
    val challenge: String?,
)
```

```kotlin
data class SequenceIdentity(
    val type: String?,
    val sub: String?,
    val email: String?,
)
```

```kotlin
data class SequenceWallet(
    val type: String?,
    val address: String?,
    val index: Int?,
    val comment: String?,
)
```

```kotlin
data class SignMessageResult(
    val signature: String,
)
```

```kotlin
data class SendTransactionResult(
    val txHash: String,
)
```

```kotlin
data class IsValidMessageSignatureResult(
    val status: Int,
    val isValid: Boolean,
)
```

```kotlin
data class TokenBalancesPage(
    val page: Int,
    val pageSize: Int,
    val more: Boolean,
)
```

```kotlin
data class TokenBalance(
    val contractType: String?,
    val contractAddress: String?,
    val accountAddress: String?,
    val tokenId: String?,
    val balance: String?,
    val blockHash: String?,
    val blockNumber: Long?,
    val chainId: Long?,
)
```

```kotlin
data class TokenBalancesResult(
    val status: Int,
    val page: TokenBalancesPage?,
    val balances: List<TokenBalance>,
)
```

## Recommended Usage

```kotlin
val polygonSdk = PolygonSdk(
    context = context,
    projectAccessKey = "YOUR_PROJECT_ACCESS_KEY",
)

if (polygonSdk.wallet.walletAddress == null) {
    polygonSdk.wallet.signInWithEmail("user@example.com")
    // A one-time code is sent to the user's email inbox.
    val wallet = polygonSdk.wallet.completeEmailSignIn("123456")
}
```

If your app needs to choose between multiple wallets:

```kotlin
val wallet = polygonSdk.wallet.completeEmailSignIn("123456") { wallets ->
    showWalletPickerAndWaitForChoice(wallets)
}
```
