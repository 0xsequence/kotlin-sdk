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
    walletType: WalletType = WalletType.Ethereum_EOA,
): Wallet
```

```kotlin
suspend fun wallet.completeEmailSignIn(
    code: String,
    walletType: WalletType = WalletType.Ethereum_EOA,
    selectWallet: suspend (List<Wallet>) -> Wallet,
): Wallet
```

```kotlin
suspend fun wallet.signMessage(
    chainId: String,
    message: String,
): SignMessageResponse
```

```kotlin
suspend fun wallet.sendTransaction(
    chainId: String,
    to: String,
    value: String,
): SendTransactionResponse
```

```kotlin
suspend fun wallet.sendTransaction(
    chainId: String,
    request: SendTransactionRequest,
): SendTransactionResponse
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

`walletApiUrl` should be treated as the Wallet API base URL/origin. Wallet RPC method paths come from the generated waas schema.

```kotlin
fun environment.indexerUrlForChainId(chainId: String): String
```

```kotlin
fun SequenceEnvironment.Companion.demoDefaults(): SequenceEnvironment
```

## Public Models

```kotlin
typealias TransactionMode = com.polygon_wallet.polygon_kotlin_sdk.generated.waas.TransactionMode
```

```kotlin
data class SendTransactionRequest(
    val to: String,
    val value: String,
    val data: String? = null,
    val mode: TransactionMode = TransactionMode.Relayer,
    val feeCeiling: String? = null,
    val nonce: String? = null,
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

Wallet auth, wallet selection, signing, and transaction result models now come from the generated waas package:

```kotlin
com.polygon_wallet.polygon_kotlin_sdk.generated.waas
```

Common public return types from that package include:

```kotlin
data class CommitVerifierResponse(
    val verifier: String,
    val loginHint: String? = null,
    val challenge: String,
)
```

```kotlin
data class Wallet(
    val type: WalletType,
    val address: String,
    val index: UByte,
    val comment: String? = null,
)
```

```kotlin
data class SignMessageResponse(
    val signature: String,
)
```

```kotlin
data class SendTransactionResponse(
    val txHash: String,
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
    showWalletPickerAndWaitForChoice(wallets) // wallets: List<Wallet>
}
```

For contract calls or transaction parameters beyond `to` and `value`:

```kotlin
val txResult = polygonSdk.wallet.sendTransaction(
    chainId = "80002",
    request = SendTransactionRequest(
        to = "0xContractAddress",
        value = "0",
        data = "0x1234",
        mode = TransactionMode.Native,
        feeCeiling = "1000000",
        nonce = "42",
    ),
)
```
