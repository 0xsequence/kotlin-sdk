# Public API

This document describes the intended public API for external consumers of the OMS Wallet Kotlin SDK.

## Entry Point

```kotlin
OmsWallet(
    context: Context,
    projectAccessKey: String,
    environment: OmsWalletEnvironment = OmsWalletEnvironment(),
    okHttpClient: OkHttpClient = OkHttpClient(),
)
```

```kotlin
val omsWallet.hasPendingSignIn: Boolean
val omsWallet.walletAddress: String?
val omsWallet.signerAddress: String?
val omsWallet.utils: OmsWalletUtils
val omsWallet.indexer: OmsWalletIndexerClient
```

`hasPendingSignIn` reflects an in-memory auth flow that has not resolved to a
wallet yet. Persisted session restore only revives completed wallet sessions.
If auth completes but wallet resolution or session persistence fails, the SDK
clears the in-memory auth session instead of leaving a transient signer active.

```kotlin
fun omsWallet.clearSession()
```

```kotlin
suspend fun omsWallet.signInWithEmail(
    email: String,
): CommitVerifierResponse
```

```kotlin
suspend fun omsWallet.signInWithOidcIdToken(
    idToken: String,
    issuer: String,
    audience: String,
    walletType: WalletType = WalletType.Ethereum,
): Wallet
```

```kotlin
suspend fun omsWallet.signInWithOidcIdToken(
    idToken: String,
    issuer: String,
    audience: String,
    walletType: WalletType = WalletType.Ethereum,
    selectWallet: suspend (List<Wallet>) -> Wallet,
): Wallet
```

```kotlin
suspend fun omsWallet.completeEmailSignIn(
    code: String,
    walletType: WalletType = WalletType.Ethereum,
): Wallet
```

```kotlin
suspend fun omsWallet.completeEmailSignIn(
    code: String,
    walletType: WalletType = WalletType.Ethereum,
    selectWallet: suspend (List<Wallet>) -> Wallet,
): Wallet
```

```kotlin
suspend fun omsWallet.signMessage(
    chainId: String,
    message: String,
): SignMessageResponse
```

```kotlin
suspend fun omsWallet.sendTransaction(
    chainId: String,
    to: String,
    value: String,
): SendTransactionResponse
```

```kotlin
suspend fun omsWallet.sendTransaction(
    chainId: String,
    request: SendTransactionRequest,
): SendTransactionResponse
```

## Utils

```kotlin
suspend fun utils.verifySignature(
    chainId: String,
    walletAddress: String,
    message: String,
    signature: String,
): VerifySignatureResult
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
class OmsWalletEnvironment(
    val walletApiUrl: String = OmsWalletEnvironment.walletApiUrlDefault,
    val apiRpcUrl: String = OmsWalletEnvironment.apiRpcUrlDefault,
    val indexerUrlTemplate: String = OmsWalletEnvironment.indexerUrlTemplateDefault,
)
```

`walletApiUrl` should be treated as the Wallet API base URL/origin. Wallet RPC method paths come from the generated waas schema.

```kotlin
fun environment.indexerUrlForChainId(chainId: String): String
```

```kotlin
fun OmsWalletEnvironment.Companion.demoDefaults(): OmsWalletEnvironment
```

## Public Models

```kotlin
typealias TransactionMode = com.omswallet.kotlin_sdk.generated.waas.TransactionMode
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
data class VerifySignatureResult(
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
com.omswallet.kotlin_sdk.generated.waas
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
    val id: String,
    val type: WalletType,
    val address: String,
    val reference: String? = null,
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
val omsWallet = OmsWallet(
    context = context,
    projectAccessKey = "YOUR_PROJECT_ACCESS_KEY",
)

if (omsWallet.walletAddress == null) {
    omsWallet.signInWithEmail("user@example.com")
    // A one-time code is sent to the user's email inbox.
    val wallet = omsWallet.completeEmailSignIn("123456")
}
```

For OIDC ID-token flows:

```kotlin
val wallet = omsWallet.signInWithOidcIdToken(
    idToken = googleIdToken,
    issuer = "https://accounts.google.com",
    audience = "YOUR_WEB_CLIENT_ID",
)
```

If your app needs to choose between multiple wallets:

```kotlin
val wallet = omsWallet.completeEmailSignIn("123456") { wallets ->
    showWalletPickerAndWaitForChoice(wallets) // wallets: List<Wallet>
}
```

For contract calls or transaction parameters beyond `to` and `value`:

```kotlin
val txResult = omsWallet.sendTransaction(
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
