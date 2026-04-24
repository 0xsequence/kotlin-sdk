# Public API

This document describes the intended public API for external consumers of the OMS Client Kotlin SDK.

## Entry Point

```kotlin
OMSClient(
    context: Context,
    projectAccessKey: String,
    environment: OMSClientEnvironment = OMSClientEnvironment(),
    okHttpClient: OkHttpClient = OkHttpClient(),
)
```

```kotlin
val client.wallet: WalletClient
val client.utils: OMSClientUtils
val client.indexer: IndexerClient
```

## Auth and Session

```kotlin
val client.session: OMSClientSessionState
```

```kotlin
data class OMSClientSessionState(
    val hasPendingSignIn: Boolean,
    val walletAddress: String?,
    val signerAddress: String?,
)
```

`client.session.hasPendingSignIn` reflects an in-memory auth flow that has not
resolved to a wallet yet. Persisted session restore only revives completed
wallet sessions. If auth completes but wallet resolution or session persistence
fails, the SDK clears the in-memory auth session instead of leaving a transient
signer active.

```kotlin
fun client.signOut()
```

```kotlin
suspend fun client.startEmailAuth(
    email: String,
): CommitVerifierResponse
```

```kotlin
suspend fun client.signInWithOidcIdToken(
    idToken: String,
    issuer: String,
    audience: String,
    walletType: WalletType = WalletType.Ethereum,
): Wallet
```

```kotlin
suspend fun client.signInWithOidcIdToken(
    idToken: String,
    issuer: String,
    audience: String,
    walletType: WalletType = WalletType.Ethereum,
    selectWallet: suspend (List<Wallet>) -> Wallet,
): Wallet
```

```kotlin
suspend fun client.completeEmailAuth(
    code: String,
    walletType: WalletType = WalletType.Ethereum,
): Wallet
```

```kotlin
suspend fun client.completeEmailAuth(
    code: String,
    walletType: WalletType = WalletType.Ethereum,
    selectWallet: suspend (List<Wallet>) -> Wallet,
): Wallet
```

## Wallet

```kotlin
val client.wallet.address: String?
```

```kotlin
suspend fun client.wallet.signMessage(
    network: Network,
    message: String,
): SignMessageResponse
```

```kotlin
suspend fun client.wallet.sendTransaction(
    network: Network,
    to: String,
    value: String,
): SendTransactionResponse
```

```kotlin
suspend fun client.wallet.sendTransaction(
    network: Network,
    request: SendTransactionRequest,
): SendTransactionResponse
```

## Networks

```kotlin
val client.supportedNetworks: List<Network>
fun client.network(chainId: String): Network?
```

```kotlin
enum class Network {
    POLYGON,
    POLYGON_AMOY,
}
```

Each entry exposes `chainId` and `displayName`.

## Utils

```kotlin
suspend fun utils.verifySignature(
    network: Network,
    walletAddress: String,
    message: String,
    signature: String,
): VerifySignatureResult
```

## Indexer Service

```kotlin
suspend fun indexer.getTokenBalances(
    network: Network,
    contractAddress: String,
    walletAddress: String,
    includeMetadata: Boolean,
): TokenBalancesResult
```

## Environment

```kotlin
class OMSClientEnvironment(
    val walletApiUrl: String = OMSClientEnvironment.walletApiUrlDefault,
    val apiRpcUrl: String = OMSClientEnvironment.apiRpcUrlDefault,
    val indexerUrlTemplate: String = OMSClientEnvironment.indexerUrlTemplateDefault,
)
```

`walletApiUrl` should be treated as the Wallet API base URL/origin. Wallet RPC method paths come from the generated waas schema.

```kotlin
fun OMSClientEnvironment.Companion.demoDefaults(): OMSClientEnvironment
```

## Public Models

```kotlin
typealias TransactionMode = com.omsclient.kotlin_sdk.generated.waas.TransactionMode
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
com.omsclient.kotlin_sdk.generated.waas
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
val client = OMSClient(
    context = context,
    projectAccessKey = "YOUR_PROJECT_ACCESS_KEY",
)

if (client.wallet.address == null) {
    client.startEmailAuth("user@example.com")
    // A one-time code is sent to the user's email inbox.
    val wallet = client.completeEmailAuth("123456")
}
```

For OIDC ID-token flows:

```kotlin
val wallet = client.signInWithOidcIdToken(
    idToken = googleIdToken,
    issuer = "https://accounts.google.com",
    audience = "YOUR_WEB_CLIENT_ID",
)
```

If your app needs to choose between multiple wallets:

```kotlin
val wallet = client.completeEmailAuth("123456") { wallets ->
    showWalletPickerAndWaitForChoice(wallets) // wallets: List<Wallet>
}
```

For contract calls or transaction parameters beyond `to` and `value`:

```kotlin
val network = Network.POLYGON_AMOY

val txResult = client.wallet.sendTransaction(
    network = network,
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
