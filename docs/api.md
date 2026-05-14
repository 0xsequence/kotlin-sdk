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
val client.indexer: IndexerClient
```

## Auth and Session

```kotlin
val client.session: OMSClientSessionState
```

```kotlin
data class OMSClientSessionState(
    val walletAddress: String?,
    val expiresAt: Instant?,
    val loginType: OMSClientSessionLoginType?,
    val sessionEmail: String?,
)
```

```kotlin
enum class OMSClientSessionLoginType {
    Email,
    GoogleAuth,
    Oidc,
}
```

`client.session` only reports completed wallet-session state. Pending auth
state, OIDC redirect verifier/state, and signer details are SDK internals. Apps
should show OTP or redirect waiting UI from the method result that started the
flow, not from session state. Always pass incoming app-link URLs to
`handleOidcRedirectCallback`; stale callbacks return `NoPendingAuth`, and the
app can show sign-in UI and let the user start again. Persisted session restore
revives completed wallet sessions, including the session expiry, login type, and
email returned by the wallet API, but not pending email OTP state. Completed auth
requests use a one-week wallet API session lifetime. Auth completion loads all
wallet pages before selecting or creating a wallet. If auth completes but wallet
resolution or session persistence fails, the SDK clears the in-memory auth
session instead of leaving transient state active.

The Android `OMSClient(context, ...)` constructor signs wallet API requests with
a non-extractable Android Keystore P-256 credential using the
`ecdsa-p256-sha256` signing algorithm. Persisted wallet sessions store wallet metadata
only; the credential private key remains owned by Android Keystore and is not
written to SDK session storage.

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
data class OidcProviderConfig(
    val issuer: String,
    val clientId: String,
    val authorizationUrl: String,
    val scopes: List<String> = listOf("openid", "email", "profile"),
    val relayRedirectUri: String? = null,
    val authorizeParams: Map<String, String> = emptyMap(),
)
```

```kotlin
object OidcProviders {
    fun google(
        clientId: String = OidcProviders.defaultGoogleClientId,
        relayRedirectUri: String = OidcProviders.defaultRelayRedirectUri,
        scopes: List<String> = listOf("openid", "email", "profile"),
        authorizeParams: Map<String, String> = emptyMap(),
    ): OidcProviderConfig
}
```

```kotlin
data class StartOidcRedirectAuthResult(
    val authorizationUrl: String,
    val state: String,
    val challenge: String,
)
```

```kotlin
suspend fun client.startOidcRedirectAuth(
    provider: OidcProviderConfig,
    redirectUri: String,
    walletType: WalletType = WalletType.Ethereum,
    relayRedirectUri: String? = provider.relayRedirectUri,
    authorizeParams: Map<String, String> = emptyMap(),
): StartOidcRedirectAuthResult
```

```kotlin
sealed interface OidcRedirectAuthResult {
    data class Completed(val wallet: Wallet) : OidcRedirectAuthResult
    data class WalletSelection(
        val wallets: List<Wallet>,
        val credential: CredentialInfo,
    ) : OidcRedirectAuthResult
    data object NotOidcRedirectCallback : OidcRedirectAuthResult
    data object NoPendingAuth : OidcRedirectAuthResult
    data class Failed(val error: Throwable) : OidcRedirectAuthResult
}
```

```kotlin
suspend fun client.handleOidcRedirectCallback(
    callbackUrl: String?,
    selectWallet: suspend (List<Wallet>) -> Wallet = { wallets -> wallets.single() },
): OidcRedirectAuthResult
```

```kotlin
suspend fun client.handleOidcRedirectCallback(
    callbackUrl: String?,
    autoActivate: Boolean = true,
    selectWallet: suspend (List<Wallet>) -> Wallet = { wallets -> wallets.single() },
): OidcRedirectAuthResult
```

OIDC redirect auth stores transient verifier/state data separately from the
completed wallet session so Android can resume after the browser redirect. Open
`StartOidcRedirectAuthResult.authorizationUrl` with app-owned UI such as Custom
Tabs, then pass incoming app-link URLs to `handleOidcRedirectCallback`. The
handler is idempotent and safe to call from `onCreate` / `onNewIntent`: unrelated
links return `NotOidcRedirectCallback`, stale links return `NoPendingAuth`, and
successful auth returns `Completed` or `WalletSelection` when `autoActivate` is
false. Starting a new auth flow clears or replaces stale redirect state, and
`signOut()` clears it.

```kotlin
sealed interface CompleteAuthResult {
    data class Activated(
        val walletAddress: String,
        val wallet: Wallet,
        val wallets: List<Wallet>,
        val credential: CredentialInfo,
    ) : CompleteAuthResult

    data class WalletSelection(
        val wallets: List<Wallet>,
        val credential: CredentialInfo,
    ) : CompleteAuthResult
}
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
    autoActivate: Boolean,
    walletType: WalletType = WalletType.Ethereum,
): CompleteAuthResult
```

```kotlin
suspend fun client.completeEmailAuth(
    code: String,
    walletType: WalletType = WalletType.Ethereum,
    selectWallet: suspend (List<Wallet>) -> Wallet,
): Wallet
```

Auth completion loads all wallet pages before selecting or creating a wallet.
Pass `autoActivate = false` to return `CompleteAuthResult.WalletSelection`
without selecting or creating a wallet; then call `client.wallet.useWallet(...)`
or `client.wallet.createWallet(...)`.

## Wallet

```kotlin
val client.wallet.address: String?
```

```kotlin
data class WalletActivationResult(
    val walletAddress: String,
    val wallet: Wallet,
)
```

```kotlin
suspend fun client.wallet.listWallets(): List<Wallet>
```

```kotlin
suspend fun client.wallet.useWallet(
    walletId: String,
): WalletActivationResult
```

```kotlin
suspend fun client.wallet.createWallet(
    walletType: WalletType = WalletType.Ethereum,
    reference: String? = null,
): WalletActivationResult
```

```kotlin
suspend fun client.wallet.signMessage(
    network: Network,
    message: String,
): SignMessageResponse
```

```kotlin
suspend fun client.wallet.signTypedData(
    network: Network,
    typedData: JsonElement,
): SignTypedDataResponse
```

```kotlin
suspend fun client.wallet.sendTransaction(
    network: Network,
    to: String,
    value: BigInteger,
    selectFeeOption: FeeOptionSelector? = null,
): SendTransactionResponse
```

```kotlin
suspend fun client.wallet.sendTransaction(
    network: Network,
    request: SendTransactionRequest,
    selectFeeOption: FeeOptionSelector? = null,
): SendTransactionResponse
```

```kotlin
suspend fun client.wallet.callContract(
    network: Network,
    contract: String,
    method: String,
    args: List<AbiArg>? = null,
    mode: TransactionMode = TransactionMode.Relayer,
    selectFeeOption: FeeOptionSelector? = null,
): SendTransactionResponse
```

```kotlin
suspend fun client.wallet.getTransactionStatus(
    txnId: String,
): TransactionStatusResponse
```

```kotlin
suspend fun client.wallet.listAccess(
    pageSize: UInt? = null,
): List<CredentialInfo>
```

```kotlin
fun client.wallet.listAccessPages(
    pageSize: UInt? = null,
): Flow<ListAccessResponse>
```

```kotlin
suspend fun client.wallet.listAccessPage(
    pageSize: UInt? = null,
    cursor: String? = null,
): ListAccessResponse
```

```kotlin
suspend fun client.wallet.revokeAccess(
    targetCredentialId: String,
)
```

When a prepared transaction includes fee options, `selectFeeOption` receives the
available options enriched with the selected wallet's matching token balance
when available. When no selector is provided, `sendTransaction` uses the first
required fee option, or no fee option when the transaction is sponsored.
`value` is a raw base-unit integer; use `parseUnits` to convert human-entered
decimal values before sending.
After execution, `sendTransaction` and `callContract` poll the WaaS status
endpoint briefly for an executed status or transaction hash. If the transaction
remains pending when polling times out, the response contains the `txnId`, `status =
TransactionStatus.Pending`, and `txHash = null`.
Use `getTransactionStatus` to refresh a transaction later. `listAccess` follows
WaaS cursors and returns all credentials, `listAccessPages` emits each page as a
`Flow`, and `listAccessPage` exposes one page at a time for manual cursor
pagination. Pass `pageSize` when fetching credentials that may span multiple
pages so each request uses an explicit limit.

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

Top-level unit helpers live in `com.omsclient.kotlin_sdk.utils`.

```kotlin
fun formatUnits(
    value: BigInteger,
    decimals: Int,
): String
```

```kotlin
fun parseUnits(
    value: String,
    decimals: Int,
): BigInteger
```

```kotlin
suspend fun wallet.isValidMessageSignature(
    network: Network,
    message: String,
    signature: String,
): Boolean
```

```kotlin
suspend fun wallet.isValidTypedDataSignature(
    network: Network,
    typedData: JsonElement,
    signature: String,
): Boolean
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
typealias TransactionStatus = com.omsclient.kotlin_sdk.generated.waas.TransactionStatus
typealias FeeOption = com.omsclient.kotlin_sdk.generated.waas.FeeOption
typealias FeeOptionSelection = com.omsclient.kotlin_sdk.generated.waas.FeeOptionSelection
typealias FeeOptionSelector = suspend (List<FeeOptionWithBalance>) -> FeeOptionSelection?
```

```kotlin
data class FeeOptionWithBalance(
    val feeOption: FeeOption,
    val balance: TokenBalance?,
    val available: String?,
    val availableRaw: String?,
    val decimals: Int?,
)
```

```kotlin
data class SendTransactionRequest(
    val to: String,
    val value: BigInteger,
    val data: String? = null,
    val mode: TransactionMode = TransactionMode.Relayer,
)
```

```kotlin
data class SendTransactionResponse(
    val txnId: String,
    val status: TransactionStatus,
    val txHash: String?,
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

Wallet auth, wallet selection, and signing models now come from the generated waas package:

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
data class SignTypedDataResponse(
    val signature: String,
)
```

```kotlin
data class AbiArg(
    val type: String,
    val value: JsonElement,
)
```

```kotlin
data class CredentialInfo(
    val credentialId: String,
    val expiresAt: String,
    val isCaller: Boolean,
)
```

```kotlin
data class ListAccessResponse(
    val credentials: List<CredentialInfo>,
    val page: Page? = null,
)

data class Page(
    val limit: UInt? = null,
    val cursor: String? = null,
)
```

```kotlin
data class TransactionStatusResponse(
    val status: TransactionStatus,
    val txHash: String? = null,
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

To opt out of automatic wallet activation and drive selection in your own UI:

```kotlin
when (val result = client.completeEmailAuth("123456", autoActivate = false)) {
    is CompleteAuthResult.WalletSelection -> {
        val picked = showWalletPickerAndWaitForChoice(result.wallets)
        client.wallet.useWallet(picked.id)
    }
    is CompleteAuthResult.Activated -> Unit
}
```

For raw calldata or transaction parameters beyond `to` and `value`:

```kotlin
val network = Network.POLYGON_AMOY

val txResult = client.wallet.sendTransaction(
    network = network,
    request = SendTransactionRequest(
        to = "0xContractAddress",
        value = parseUnits("0", 18),
        data = "0x1234",
        mode = TransactionMode.Native,
    ),
)
```

For WaaS ABI-style contract calls:

```kotlin
val txResult = client.wallet.callContract(
    network = network,
    contract = "0xContractAddress",
    method = "transfer(address,uint256)",
    args =
        listOf(
            AbiArg(type = "address", value = JsonPrimitive("0xRecipient")),
            AbiArg(type = "uint256", value = JsonPrimitive("1000000000000000000")),
        ),
)
```

To choose a fee option before execution:

```kotlin
val txResult = client.wallet.sendTransaction(
    network = network,
    request = SendTransactionRequest(
        to = "0xContractAddress",
        value = parseUnits("0", 18),
        data = "0x1234",
        mode = TransactionMode.Native,
    ),
) { feeOptions ->
    val selected = showFeePickerAndWaitForChoice(feeOptions)
    FeeOptionSelection(token = selected.feeOption.token.symbol)
}
```
