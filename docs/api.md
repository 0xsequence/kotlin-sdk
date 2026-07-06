# Public API

This document describes the intended public API for external consumers of the OMS Wallet Kotlin SDK.

## Installation and Requirements

```kotlin
implementation("io.github.0xsequence:oms-wallet-kotlin-sdk:0.2.0")
```

Consumer apps need Android 10 / API 29 or newer, Android `compileSdk 34` or
newer, and Java 17 Android compile options. The SDK does not require app-level
core library desugaring.

The published artifact declares `minSdk 24` so apps with lower manifest floors,
including Expo/React Native apps, can include the dependency. This is a packaging
compatibility floor; the SDK requires Android 10 / API 29 or newer at runtime
because the service endpoints require TLS 1.3. Updating `compileSdk` is separate
from `targetSdk`; consumers do not need to opt into a newer Android runtime
behavior just to consume the SDK.

The published SDK is a single Maven artifact. Consumers should use the SDK APIs
documented here and do not need any separate service-client artifact.

## Entry Point

```kotlin
OMSWallet(
    context: Context,
    publishableKey: String,
    okHttpClient: OkHttpClient = OkHttpClient(),
)
```

```kotlin
val client.wallet: WalletClient
val client.indexer: IndexerClient
```

The SDK derives required service configuration from the publishable key.

## Auth and Session

```kotlin
val client.session: OMSWalletSessionState
```

```kotlin
data class OMSWalletSessionState(
    val walletAddress: String?,
    val expiresAt: String?,
    val auth: OMSWalletSessionAuth?,
)
```

```kotlin
data class OMSWalletSessionExpiredEvent(
    val session: OMSWalletSessionState,
    val expiredAt: String,
)
```

`expiresAt` and `expiredAt` are ISO-8601 timestamp strings returned by the
wallet API. They are strings so apps with `minSdk 24` do not need `java.time` or
core library desugaring for these public session fields.

```kotlin
sealed interface OMSWalletSessionAuth {
    val email: String?
}

data class OMSWalletEmailSessionAuth(
    override val email: String?,
) : OMSWalletSessionAuth

enum class OMSWalletOidcSessionAuthFlow {
    Redirect,
    IdToken,
}

data class OMSWalletOidcSessionAuth(
    val flow: OMSWalletOidcSessionAuthFlow,
    val issuer: String,
    val provider: String?,
    val providerLabel: String?,
    override val email: String?,
) : OMSWalletSessionAuth
```

`client.session` only reports completed wallet-session state. Apps should show
OTP or redirect waiting UI from the method result that started the flow, not
from session state. Always pass incoming app-link URLs to
`handleOidcRedirectCallback`; stale callbacks return `NoPendingAuth`, and the
app can show sign-in UI and let the user start again. Persisted session restore
revives completed wallet sessions, including the session expiry and auth
metadata returned by the wallet API, but not pending email OTP state. OIDC
sessions include the flow (`Redirect` or `IdToken`), issuer, provider key,
provider label, and email when available. Completed auth
requests use a one-week wallet API session lifetime by default
(`WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS`, `604_800` seconds); pass
`sessionLifetimeSeconds` to auth completion methods or `startOidcRedirectAuth`
to request a different value from 1 through
`WalletClient.MAX_SESSION_LIFETIME_SECONDS` (`2_592_000` seconds, 30 days). For
OIDC redirects, start-time values are stored with pending redirect state and used
on callback completion unless the callback provides an override. Invalid values
return `OMSWalletErrorCode.ValidationError` before the SDK sends the affected
auth request. Auth completion loads all wallet pages before selecting or creating
a wallet. If auth completes but wallet selection or session persistence fails,
the SDK clears the in-memory auth session instead of leaving transient state
active.
Starting a new email, OIDC ID-token, or OIDC redirect auth flow replaces any
existing wallet session so expired or stale sessions do not block
re-authentication.

Expired sessions are made inactive before protected wallet operations and throw
`OMSWalletSessionException` with `code = OMSWalletErrorCode.SessionExpired`. Use
`onSessionExpired` to route users back to sign-in; the event includes the
expired session snapshot so apps can reuse `session.auth?.email` for email OTP
reauth or as a Google `loginHint`, including after process recreation.

```kotlin
fun client.wallet.signOut()
```

```kotlin
fun client.wallet.onSessionExpired(
    listener: (OMSWalletSessionExpiredEvent) -> Unit,
): () -> Unit
```

Registers a listener for expired wallet sessions and returns an unsubscribe
function. The wallet client stores the latest expired-session event and replays
it to each new listener until a new auth flow, new wallet session, or
`signOut()` clears it. Listeners are delivered on the Android main thread.

```kotlin
suspend fun client.wallet.startEmailAuth(
    email: String,
)
```

```kotlin
suspend fun client.wallet.signInWithOidcIdToken(
    idToken: String,
    issuer: String,
    audience: String,
    walletSelection: WalletSelectionBehavior = WalletSelectionBehavior.Automatic,
    walletType: WalletType = WalletType.Ethereum,
    sessionLifetimeSeconds: Long = WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS,
    provider: String? = null,
    providerLabel: String? = null,
): CompleteAuthResult
```

Pass `walletSelection = WalletSelectionBehavior.Manual` for OIDC ID-token auth
when the app needs to show its own wallet-selection UI before selecting or
creating a wallet. Pass `provider` and `providerLabel` when you want custom
session metadata for non-built-in identity providers. When omitted, Google and
Apple are derived from the issuer and custom issuers leave those fields null.

```kotlin
data class OidcProviderConfig(
    val issuer: String,
    val clientId: String,
    val authorizationUrl: String,
    val provider: String? = null,
    val providerLabel: String? = null,
    val scopes: List<String> = emptyList(),
    val relayRedirectUri: String? = null,
    val authorizeParams: Map<String, String> = emptyMap(),
    val authMode: OidcRedirectAuthMode = OidcRedirectAuthMode.AuthCodePKCE,
)
```

```kotlin
enum class OidcRedirectAuthMode {
    AuthCode,
    AuthCodePKCE,
}
```

```kotlin
object OidcProviders {
    fun google(
        clientId: String = OidcProviders.defaultGoogleClientId,
        relayRedirectUri: String? = null,
        scopes: List<String> = listOf("openid", "email", "profile"),
        authorizeParams: Map<String, String> = emptyMap(),
        authMode: OidcRedirectAuthMode = OidcRedirectAuthMode.AuthCodePKCE,
    ): OidcProviderConfig

    fun apple(
        clientId: String = OidcProviders.defaultAppleClientId,
        relayRedirectUri: String? = null,
        scopes: List<String> = listOf("openid", "email"),
        authorizeParams: Map<String, String> = emptyMap(),
        authMode: OidcRedirectAuthMode = OidcRedirectAuthMode.AuthCodePKCE,
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
suspend fun client.wallet.startOidcRedirectAuth(
    provider: OidcProviderConfig,
    redirectUri: String,
    walletType: WalletType = WalletType.Ethereum,
    walletSelection: WalletSelectionBehavior? = null,
    sessionLifetimeSeconds: Long? = null,
    relayRedirectUri: String? = provider.relayRedirectUri ?: /* derived for built-in providers */ null,
    authorizeParams: Map<String, String> = emptyMap(),
    loginHint: String? = null,
): StartOidcRedirectAuthResult
```

```kotlin
sealed interface OidcRedirectAuthResult {
    data class Completed(val wallet: Wallet) : OidcRedirectAuthResult
    data class WalletSelection(
        val pendingSelection: PendingWalletSelection,
    ) : OidcRedirectAuthResult
    data object NotOidcRedirectCallback : OidcRedirectAuthResult
    data object NoPendingAuth : OidcRedirectAuthResult
    data class Failed(val error: Throwable) : OidcRedirectAuthResult
}
```

```kotlin
suspend fun client.wallet.handleOidcRedirectCallback(
    callbackUrl: String?,
    walletSelection: WalletSelectionBehavior? = null,
    sessionLifetimeSeconds: Long? = null,
): OidcRedirectAuthResult
```

OIDC redirect auth stores transient redirect auth state separately from the
completed wallet session so Android can resume after the browser redirect. Open
`StartOidcRedirectAuthResult.authorizationUrl` with app-owned UI such as Custom
Tabs, then pass incoming app-link URLs to `handleOidcRedirectCallback`. The
handler is idempotent and safe to call from `onCreate` / `onNewIntent`: unrelated
links return `NotOidcRedirectCallback`, stale links return `NoPendingAuth`, and
provider or completion failures return `Failed` with an `OMSWalletException` when
the SDK can classify the failure. With
`WalletSelectionBehavior.Automatic`, successful callbacks return `Completed`.
With `WalletSelectionBehavior.Manual`, successful callbacks return
`WalletSelection`. Pass `walletSelection` or `sessionLifetimeSeconds` to
`startOidcRedirectAuth` to store completion preferences in the pending redirect
state. Non-null values passed to `handleOidcRedirectCallback` override pending
values; omitted callback values fall back to pending values and then SDK
defaults. Custom session lifetime values must be from 1 through
`WalletClient.MAX_SESSION_LIFETIME_SECONDS` (`2_592_000` seconds, 30 days).
Starting a new auth flow clears or replaces stale redirect state, and `signOut()`
clears it.

Provider configs are the source of truth for redirect scopes, auth mode, and
optional provider display metadata. If `scopes` is omitted or empty, the
authorization URL omits `scope`. PKCE `code_challenge` parameters are sent only
when `authMode = OidcRedirectAuthMode.AuthCodePKCE`. `OidcProviders.google()`
uses the SDK default Google client ID, `openid email profile` scopes, PKCE
auth-code mode, and Google authorization parameters
`access_type=offline` and `prompt=consent`. `OidcProviders.apple()` uses the SDK
default Apple Services ID, `openid email` scopes, `response_mode=form_post`, and
PKCE auth-code mode. When the provider relay URL is omitted,
`startOidcRedirectAuth` derives the relay URL from the publishable-key Wallet API
base as `{walletApiUrl}/auth/waas/callback/{google|apple}` for built-in Google
and Apple providers. Apple `form_post` works through that derived relay; a direct
app deep link should not be used as the Apple OAuth callback unless that provider
flow supports it. Pass `relayRedirectUri = null` explicitly to bypass the relay
for providers whose response mode can call your app callback directly.

Pass `loginHint` to `startOidcRedirectAuth` only when you want to prefill or
select a specific Google account, such as during session-expiry reauth. The SDK
only sends `login_hint` for providers whose issuer is
`https://accounts.google.com`. If omitted, the SDK falls back to the previous
active session email when one exists before the redirect auth attempt starts.
Pass an empty string to force no `login_hint` for a call. After `signOut()`, the
previous session email is cleared.

```kotlin
enum class WalletSelectionBehavior {
    Automatic,
    Manual,
}
```

```kotlin
class PendingWalletSelection {
    val walletType: WalletType
    val wallets: List<Wallet>
    val credential: CredentialInfo

    suspend fun selectWallet(walletId: String): WalletSelectionResult
    suspend fun createAndSelectWallet(reference: String? = null): WalletSelectionResult
}
```

```kotlin
sealed interface CompleteAuthResult {
    data class WalletSelected(
        val walletAddress: String,
        val wallet: Wallet,
        val wallets: List<Wallet>,
        val credential: CredentialInfo,
    ) : CompleteAuthResult

    data class WalletSelection(
        val pendingSelection: PendingWalletSelection,
    ) : CompleteAuthResult
}
```

```kotlin
suspend fun client.wallet.completeEmailAuth(
    code: String,
    walletSelection: WalletSelectionBehavior = WalletSelectionBehavior.Automatic,
    walletType: WalletType = WalletType.Ethereum,
    sessionLifetimeSeconds: Long = WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS,
): CompleteAuthResult
```

Auth completion loads all wallet pages before selecting or creating a wallet.
`walletType` defines which wallet type is eligible.

In `WalletSelectionBehavior.Automatic`, auth completion:

- creates and selects a wallet when no wallet matches `walletType`
- selects the first matching wallet returned by OMS when one or more wallets
  match `walletType`

Automatic email and OIDC ID-token auth return
`CompleteAuthResult.WalletSelected` on success. Automatic OIDC redirect auth
returns `OidcRedirectAuthResult.Completed` on success. Automatic mode does not
fall back to `WalletSelection`; use manual mode when the user should choose
between wallets.

In `WalletSelectionBehavior.Manual`, auth completion returns
`CompleteAuthResult.WalletSelection` or `OidcRedirectAuthResult.WalletSelection`
with a `PendingWalletSelection`. No wallet is selected or created until the app
calls `pendingSelection.selectWallet(...)` or
`pendingSelection.createAndSelectWallet(...)`. `pendingSelection.wallets`
contains existing wallets filtered to `walletType`.

Use manual mode up front for apps that need to support choosing between multiple
wallets for the same wallet type.

## Wallet

```kotlin
val client.wallet.walletAddress: String?
```

```kotlin
data class WalletSelectionResult(
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
): WalletSelectionResult
```

```kotlin
suspend fun client.wallet.createWallet(
    walletType: WalletType = WalletType.Ethereum,
    reference: String? = null,
): WalletSelectionResult
```

```kotlin
suspend fun client.wallet.signMessage(
    network: Network,
    message: String,
): String
```

```kotlin
suspend fun client.wallet.signTypedData(
    network: Network,
    typedData: JsonElement,
): String
```

```kotlin
suspend fun client.wallet.isValidMessageSignature(
    network: Network,
    message: String,
    signature: String,
): Boolean
```

```kotlin
suspend fun client.wallet.isValidTypedDataSignature(
    network: Network,
    typedData: JsonElement,
    signature: String,
): Boolean
```

```kotlin
suspend fun client.wallet.sendTransaction(
    network: Network,
    to: String,
    value: BigInteger,
    waitForStatus: Boolean = true,
    statusPolling: TransactionStatusPollingOptions? = null,
    selectFeeOption: FeeOptionSelector? = null,
): SendTransactionResponse
```

```kotlin
suspend fun client.wallet.sendTransaction(
    network: Network,
    request: SendTransactionRequest,
    waitForStatus: Boolean = true,
    statusPolling: TransactionStatusPollingOptions? = null,
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
    waitForStatus: Boolean = true,
    statusPolling: TransactionStatusPollingOptions? = null,
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
suspend fun client.wallet.getIdToken(
    ttlSeconds: UInt? = null,
    customClaims: Map<String, JsonElement>? = null,
): String
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
Use `FeeOptionSelector.firstAvailable` to select the first option whose enriched
raw balance covers the quoted fee. Sponsored transactions skip fee selection;
unsponsored transactions fail before execute when no fee option exists or the
selector returns `null`.
`value` is a raw base-unit integer; use `parseUnits` to convert human-entered
decimal values before sending.
After execution, `sendTransaction` and `callContract` poll transaction status
briefly for an executed status or transaction hash. Pass
`waitForStatus = false` to return immediately after execute, or pass
`statusPolling` to tune the fast poll count, intervals, and timeout. If the
transaction remains pending when polling times out, the response contains the
`txnId`, `status = TransactionStatus.Pending`, and `txnHash = null`.
Use `getTransactionStatus` to refresh a transaction later. `listAccess` follows
pagination cursors and returns all credentials, `listAccessPages` emits each
page as a `Flow`, and `listAccessPage` exposes one page at a time for manual
cursor pagination. Pass `pageSize` when fetching credentials that may span
multiple pages so each request uses an explicit limit.

## Networks

```kotlin
val client.supportedNetworks: List<Network>
val supportedNetworks: List<Network>
fun findNetworkById(id: Int): Network?
fun findNetworkByName(name: String): Network?
```

```kotlin
data class Network(
    val id: Int,
    val name: String,
    val nativeTokenSymbol: String,
    val explorerUrl: String,
    val displayName: String = name,
)

Network.MAINNET
Network.SEPOLIA
Network.POLYGON
Network.AMOY
Network.ARBITRUM
Network.ARBITRUM_SEPOLIA
Network.OPTIMISM
Network.OPTIMISM_SEPOLIA
Network.BASE
Network.BASE_SEPOLIA
Network.BSC
Network.BSC_TESTNET
Network.ARBITRUM_NOVA
Network.AVALANCHE
Network.AVALANCHE_TESTNET
Network.KATANA
```

Each entry exposes `id`, `name`, `nativeTokenSymbol`, `explorerUrl`, and
`displayName`. `name` is also the registry/routing slug for indexer and node
URLs, while `displayName` is the user-facing label. Ethereum mainnet uses
`name = "mainnet"` and `displayName = "Ethereum"`.

## Utils

Top-level unit helpers live in `technology.polygon.omswallet.utils`.

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

## Indexer Service

```kotlin
suspend fun indexer.getBalances(
    walletAddress: String,
    networks: List<Network> = emptyList(),
    networkType: IndexerNetworkType = IndexerNetworkType.MAINNETS,
    contractAddresses: List<String> = emptyList(),
    includeMetadata: Boolean = true,
    omitPrices: Boolean? = null,
    tokenIds: List<String> = emptyList(),
    contractStatus: ContractVerificationStatus? = null,
    page: TokenBalancesPageRequest = TokenBalancesPageRequest(),
): TokenBalancesResult
```

```kotlin
suspend fun indexer.getTransactionHistory(
    walletAddress: String,
    networks: List<Network> = emptyList(),
    networkType: IndexerNetworkType = IndexerNetworkType.MAINNETS,
    contractAddresses: List<String> = emptyList(),
    transactionHashes: List<String> = emptyList(),
    metaTransactionIds: List<String> = emptyList(),
    fromBlock: Long? = null,
    toBlock: Long? = null,
    tokenId: String? = null,
    includeMetadata: Boolean = true,
    omitPrices: Boolean? = null,
    metadataOptions: MetadataOptions? = null,
    page: TokenBalancesPageRequest = TokenBalancesPageRequest(),
): TransactionHistoryResult
```

`getBalances` queries the OMS indexer and returns token balances plus
`nativeBalances`. Pass explicit `networks` for chain IDs, or omit them and use
`networkType`. Pass `includeMetadata = true` when callers need
`TokenContractInfo` or `TokenMetadata` fields on returned token balances.

## Errors

Public SDK APIs throw `OMSWalletException` when the SDK can classify a failure.

```kotlin
enum class OMSWalletErrorCode {
    HttpError,
    InvalidResponse,
    RequestFailed,
    AuthCommitmentConsumed,
    SessionMissing,
    SessionExpired,
    WalletSelectionStale,
    WalletSelectionUnavailable,
    WalletSelectionInFlight,
    TransactionExecutionUnconfirmed,
    TransactionStatusLookupFailed,
    ValidationError,
    StorageError,
}
```

```kotlin
open class OMSWalletException(
    val code: OMSWalletErrorCode,
    val operation: OMSWalletOperation?,
    val status: Int?,
    val txnId: String?,
    val retryable: Boolean?,
    val upstreamError: OMSWalletUpstreamError?,
) : RuntimeException
```

```kotlin
enum class OMSWalletUpstreamService {
    Waas,
    Indexer,
}

data class OMSWalletUpstreamError(
    val service: OMSWalletUpstreamService,
    val name: String?,
    val code: String?,
    val message: String?,
    val status: Int?,
)
```

```kotlin
enum class OMSWalletOperation(
    val id: String,
) {
    PendingWalletSelection("wallet.pendingWalletSelection"),
    PendingWalletSelectionCreateAndSelectWallet("wallet.pendingWalletSelection.createAndSelectWallet"),
    PendingWalletSelectionSelectWallet("wallet.pendingWalletSelection.selectWallet"),
    IndexerGetBalances("indexer.getBalances"),
    IndexerGetTransactionHistory("indexer.getTransactionHistory"),
    WalletCallContract("wallet.callContract"),
    WalletCompleteEmailAuth("wallet.completeEmailAuth"),
    WalletCreateWallet("wallet.createWallet"),
    WalletExecute("wallet.execute"),
    WalletGetIdToken("wallet.getIdToken"),
    WalletHandleOidcRedirectCallback("wallet.handleOidcRedirectCallback"),
    WalletGetTransactionStatus("wallet.getTransactionStatus"),
    WalletIsValidMessageSignature("wallet.isValidMessageSignature"),
    WalletIsValidTypedDataSignature("wallet.isValidTypedDataSignature"),
    WalletListAccess("wallet.listAccess"),
    WalletListAccessPage("wallet.listAccessPage"),
    WalletListAccessPages("wallet.listAccessPages"),
    WalletListWallets("wallet.listWallets"),
    WalletRevokeAccess("wallet.revokeAccess"),
    WalletSendTransaction("wallet.sendTransaction"),
    WalletSignInWithOidcIdToken("wallet.signInWithOidcIdToken"),
    WalletSignMessage("wallet.signMessage"),
    WalletSignTypedData("wallet.signTypedData"),
    WalletStartEmailAuth("wallet.startEmailAuth"),
    WalletStartOidcRedirectAuth("wallet.startOidcRedirectAuth"),
    WalletTransactionStatus("wallet.transactionStatus"),
    WalletUseWallet("wallet.useWallet"),
}
```

`RequestFailed` covers classified backend failures. `InvalidResponse` is
reserved for malformed or unparseable responses.

`upstreamError` is normalized diagnostic detail from a remote OMS service response
or transport failure. Use SDK-level `code` for app branching; use
`upstreamError` for logging and service-specific troubleshooting. SDK-local
validation, session, storage, and wallet-selection failures do not include
upstream details.

`TransactionExecutionUnconfirmed` means transaction preparation succeeded and
the SDK has a `txnId`, but the execute request failed before the SDK could
confirm whether the transaction was submitted. Do not blindly resend the same
write solely because the upstream failure looked temporary.

`TransactionStatusLookupFailed` means the transaction was submitted, but
post-submit status polling failed. Retry by calling `getTransactionStatus` with
the returned `txnId`; `retryable` describes that status lookup operation, not
the original write.

## Public Models

```kotlin
enum class WalletType {
    Ethereum,
    UNKNOWN_DEFAULT,
}

enum class TransactionMode {
    Native,
    Relayer,
    UNKNOWN_DEFAULT,
}

enum class TransactionStatus {
    Quoted,
    Pending,
    Executed,
    Failed,
    UNKNOWN_DEFAULT,
}
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
data class FeeToken(
    val network: String,
    val name: String,
    val symbol: String,
    val type: String,
    val decimals: UInt? = null,
    val logoUrl: String? = null,
    val contractAddress: String? = null,
    val tokenId: String? = null,
)
```

```kotlin
data class FeeOption(
    val token: FeeToken,
    val value: String,
    val displayValue: String,
)

data class FeeOptionSelection(
    val token: String,
) {
    constructor(feeOption: FeeOption)
}

fun interface FeeOptionSelector {
    suspend fun select(feeOptions: List<FeeOptionWithBalance>): FeeOptionSelection?

    companion object {
        val firstAvailable: FeeOptionSelector
    }
}
```

```kotlin
data class FeeOptionWithBalance(
    val feeOption: FeeOption,
    val balance: TokenBalance?,
    val available: String?,
    val availableRaw: String?,
    val decimals: Int?,
) {
    val selection: FeeOptionSelection
}
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
    val txnHash: String?,
)
```

```kotlin
data class TransactionStatusPollingOptions(
    val fastPollIntervalMillis: Long = 400L,
    val fastPollCount: Int = 5,
    val pollIntervalMillis: Long = 2_000L,
    val timeoutMillis: Long = 60_000L,
)
```

`sendTransaction` and `callContract` use the fast poll interval for the first
`fastPollCount` status attempts, then use `pollIntervalMillis` until
`timeoutMillis`. Set `pollIntervalMillis <= 0` to disable slow polling after the
fast polling phase.

```kotlin
data class TransactionStatusResponse(
    val status: TransactionStatus,
    val txnHash: String? = null,
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
data class TokenBalancesPageRequest(
    val page: Int = 0,
    val pageSize: Int = 40,
)
```

```kotlin
enum class IndexerNetworkType {
    MAINNETS,
    TESTNETS,
    ALL,
}

enum class ContractVerificationStatus {
    VERIFIED,
    UNVERIFIED,
    ALL,
}
```

```kotlin
data class TokenBalancesPage(
    val page: Int,
    val pageSize: Int,
    val more: Boolean,
)
```

```kotlin
data class MetadataOptions(
    val verifiedOnly: Boolean? = null,
    val unverifiedOnly: Boolean? = null,
    val includeContracts: List<String> = emptyList(),
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
    val name: String? = null,
    val symbol: String? = null,
    val balanceUSD: String? = null,
    val priceUSD: String? = null,
    val priceUpdatedAt: String? = null,
    val uniqueCollectibles: String? = null,
    val isSummary: Boolean? = null,
    val contractInfo: TokenContractInfo? = null,
    val tokenMetadata: TokenMetadata? = null,
)
```

```kotlin
data class TokenContractInfo(
    val chainId: Long? = null,
    val address: String? = null,
    val source: String? = null,
    val name: String? = null,
    val type: String? = null,
    val symbol: String? = null,
    val decimals: Int? = null,
    val logoURI: String? = null,
    val deployed: Boolean? = null,
    val bytecodeHash: String? = null,
    val extensions: Map<String, JsonElement>? = null,
    val updatedAt: String? = null,
    val queuedAt: String? = null,
    val status: String? = null,
)
```

```kotlin
data class TokenMetadata(
    val chainId: Long? = null,
    val contractAddress: String? = null,
    val tokenId: String? = null,
    val source: String? = null,
    val name: String? = null,
    val description: String? = null,
    val image: String? = null,
    val video: String? = null,
    val audio: String? = null,
    val properties: Map<String, JsonElement>? = null,
    val attributes: List<Map<String, JsonElement>>? = null,
    val imageData: String? = null,
    val externalUrl: String? = null,
    val backgroundColor: String? = null,
    val animationUrl: String? = null,
    val decimals: Int? = null,
    val updatedAt: String? = null,
    val assets: List<TokenMetadataAsset>? = null,
    val status: String? = null,
    val queuedAt: String? = null,
    val lastFetched: String? = null,
)
```

```kotlin
data class TokenMetadataAsset(
    val id: Long? = null,
    val collectionId: Long? = null,
    val tokenId: String? = null,
    val url: String? = null,
    val metadataField: String? = null,
    val name: String? = null,
    val filesize: Long? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val updatedAt: String? = null,
)
```

```kotlin
data class TokenBalancesResult(
    val status: Int,
    val page: TokenBalancesPage?,
    val balances: List<TokenBalance>,
    val nativeBalances: List<TokenBalance> = emptyList(),
)
```

```kotlin
data class TransactionTransfer(
    val transferType: String? = null,
    val contractAddress: String? = null,
    val contractType: String? = null,
    val from: String? = null,
    val to: String? = null,
    val tokenIds: List<String>? = null,
    val amounts: List<String>? = null,
    val logIndex: Long? = null,
    val amountsUSD: List<String>? = null,
    val pricesUSD: List<String>? = null,
    val contractInfo: TokenContractInfo? = null,
    val tokenMetadata: Map<String, TokenMetadata>? = null,
)

data class Transaction(
    val txnHash: String?,
    val blockNumber: Long?,
    val blockHash: String?,
    val chainId: Long?,
    val metaTxnId: String? = null,
    val transfers: List<TransactionTransfer>? = null,
    val timestamp: String? = null,
)

data class TransactionHistoryResult(
    val status: Int,
    val page: TokenBalancesPage?,
    val transactions: List<Transaction>,
)
```

## Recommended Usage

### Automatic Wallet Selection

```kotlin
if (client.wallet.walletAddress == null) {
    client.wallet.startEmailAuth("user@example.com")
    // A one-time code is sent to the user's email inbox.
    val result = client.wallet.completeEmailAuth("123456")
    check(result is CompleteAuthResult.WalletSelected)
    showWallet(result.wallet)
}
```

For OIDC ID-token flows:

```kotlin
val result =
    client.wallet.signInWithOidcIdToken(
        idToken = googleIdToken,
        issuer = "https://accounts.google.com",
        audience = "YOUR_WEB_CLIENT_ID",
    )
check(result is CompleteAuthResult.WalletSelected)
showWallet(result.wallet)
```

For OIDC redirect flows, start with the default Google provider unless the app
has its own web client ID or provider configuration:

```kotlin
val started = client.wallet.startOidcRedirectAuth(
    provider = OidcProviders.google(),
    redirectUri = "yourapp://auth/callback",
)

// Open started.authorizationUrl.

when (val result = client.wallet.handleOidcRedirectCallback(intent.data?.toString())) {
    is OidcRedirectAuthResult.Completed -> showWallet(result.wallet)
    OidcRedirectAuthResult.NotOidcRedirectCallback -> Unit
    OidcRedirectAuthResult.NoPendingAuth -> Unit
    is OidcRedirectAuthResult.Failed -> showRestartSignIn(result.error)
}
```

Use a redirect URI that matches a deep link registered by your app, such as
`yourapp://auth/callback`. For a custom Google web client ID, call
`OidcProviders.google(clientId = "YOUR_WEB_CLIENT_ID")`.
For Apple, use `OidcProviders.apple()` with the derived relay so the relay can
receive Apple's `response_mode=form_post` callback.

### Manual Wallet Selection

Use manual mode when the app needs to present wallet choices:

```kotlin
val result =
    client.wallet.completeEmailAuth(
        code = "123456",
        walletSelection = WalletSelectionBehavior.Manual,
    )
check(result is CompleteAuthResult.WalletSelection)

val selected = selectOrCreateWallet(result.pendingSelection)
showWallet(selected.wallet)
```

OIDC ID-token auth uses the same `walletSelection` argument on
`signInWithOidcIdToken(...)`.

The picker can always offer "Create New Wallet" as a separate option from the
existing wallet list:

```kotlin
private suspend fun selectOrCreateWallet(
    pendingSelection: PendingWalletSelection,
): WalletSelectionResult {
    val choice =
        showWalletPickerAndWaitForChoice(
            wallets = pendingSelection.wallets,
            includeCreateNewWallet = true,
        )

    return when (choice) {
        WalletPickerChoice.CreateNew ->
            pendingSelection.createAndSelectWallet()
        is WalletPickerChoice.Existing ->
            pendingSelection.selectWallet(choice.wallet.id)
    }
}
```

`WalletPickerChoice` is app UI state in this example. Both SDK calls return the
selected wallet and persist it as the active wallet session.

For OIDC redirect flows, pass the behavior when starting redirect auth to store
it with pending redirect state:

```kotlin
val started = client.wallet.startOidcRedirectAuth(
    provider = OidcProviders.google(),
    redirectUri = "yourapp://auth/callback",
    walletSelection = WalletSelectionBehavior.Manual,
)
```

You can also pass a callback value to override the pending redirect preference:

```kotlin
when (
    val result =
        client.wallet.handleOidcRedirectCallback(
            callbackUrl = intent.data?.toString(),
            walletSelection = WalletSelectionBehavior.Manual,
        )
) {
    is OidcRedirectAuthResult.WalletSelection -> {
        val selected = selectOrCreateWallet(result.pendingSelection)
        showWallet(selected.wallet)
    }
    is OidcRedirectAuthResult.Completed -> error("Expected manual wallet selection")
    OidcRedirectAuthResult.NotOidcRedirectCallback -> Unit
    OidcRedirectAuthResult.NoPendingAuth -> Unit
    is OidcRedirectAuthResult.Failed -> showRestartSignIn(result.error)
}
```

For raw calldata or transaction parameters beyond `to` and `value`:

```kotlin
val network = Network.AMOY

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

For method-signature contract calls:

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
    selectFeeOption = FeeOptionSelector.firstAvailable,
)
```

Or use a custom picker:

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
    selected.selection
}
```
