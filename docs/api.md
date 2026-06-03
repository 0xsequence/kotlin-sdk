# Public API

This document describes the intended public API for external consumers of the OMS Client Kotlin SDK.

## Installation and Requirements

```kotlin
implementation("io.github.0xsequence:oms-client-kotlin-sdk:0.1.0-alpha.1")
```

Consumer apps need Android `minSdk 26`, Android `compileSdk 34` or newer, and
Java 17 Android compile options. Updating `compileSdk` is separate from
`targetSdk`; consumers do not need to opt into a newer Android runtime behavior
just to consume the SDK.

The published SDK is a single Maven artifact. Generated WaaS WebRPC classes are
embedded in the AAR under `com.omsclient.kotlin_sdk.internal.generated.waas` and
are not part of the documented public API. Consumers should not add or depend on
an `oms-client-kotlin-sdk-waas-generated` artifact.

## Entry Point

```kotlin
OMSClient(
    context: Context,
    publishableKey: String,
    projectId: String,
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
data class OMSClientSessionExpiredEvent(
    val session: OMSClientSessionState,
    val expiredAt: Instant,
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
requests use a one-week wallet API session lifetime by default
(`WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS`, `604_800` seconds); pass
`sessionLifetimeSeconds` to auth completion methods to request a different
positive whole-number lifetime in seconds. Invalid values return
`OmsSdkErrorCode.ValidationError` before an auth completion request is sent. Auth
completion loads all wallet pages before selecting or creating a wallet. If auth
completes but wallet selection or session persistence fails, the SDK clears the
in-memory auth session instead of leaving transient state active.
Starting a new email, OIDC ID-token, or OIDC redirect auth flow replaces any
existing wallet session so expired or stale sessions do not block
re-authentication.

Expired sessions are made inactive before protected wallet operations and throw
`OmsSessionException` with `code = OmsSdkErrorCode.SessionExpired`. The SDK
clears active signer/session state, but keeps expired completed-session metadata
in storage until the app starts a new auth flow or calls `signOut()`. Use
`onSessionExpired` to route users back to sign-in; the event includes the
expired session snapshot so apps can reuse `sessionEmail` for email OTP reauth or
as a Google `loginHint`, including after process recreation.

The Android `OMSClient(context, ...)` constructor wires two separate
Android-backed pieces:

- an Android Keystore P-256 credential signer authorizes wallet API requests
  with `ecdsa-p256-sha256`; the private key is non-extractable and is not
  session metadata
- a session metadata store persists completed-session metadata in an app-private
  no-backup file; temporary OIDC redirect state uses a separate no-backup file

Completed-session metadata is limited to restorable wallet state: wallet
id/address, signer address/algorithm, expiry, login type, and optional email. It
is not wallet authorization material: by itself it cannot sign requests or
access a wallet. Restore succeeds only while the matching Keystore credential
still exists, and wallet operations must sign fresh requests with that
credential. `publishableKey` is sent as `X-Access-Key`; `projectId` is used as the
wallet request signing scope and OIDC redirect state scope.

```kotlin
fun client.wallet.signOut()
```

```kotlin
fun client.wallet.onSessionExpired(
    listener: (OMSClientSessionExpiredEvent) -> Unit,
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
): CompleteAuthResult
```

Pass `walletSelection = WalletSelectionBehavior.Manual` for OIDC ID-token auth
when the app needs to show its own wallet-selection UI before selecting or
creating a wallet.

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
suspend fun client.wallet.startOidcRedirectAuth(
    provider: OidcProviderConfig,
    redirectUri: String,
    walletType: WalletType = WalletType.Ethereum,
    relayRedirectUri: String? = provider.relayRedirectUri,
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
    walletSelection: WalletSelectionBehavior = WalletSelectionBehavior.Automatic,
    sessionLifetimeSeconds: Long = WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS,
): OidcRedirectAuthResult
```

OIDC redirect auth stores transient verifier/state data separately from the
completed wallet session so Android can resume after the browser redirect. Open
`StartOidcRedirectAuthResult.authorizationUrl` with app-owned UI such as Custom
Tabs, then pass incoming app-link URLs to `handleOidcRedirectCallback`. The
handler is idempotent and safe to call from `onCreate` / `onNewIntent`: unrelated
links return `NotOidcRedirectCallback`, stale links return `NoPendingAuth`, and
provider or completion failures return `Failed` with an `OmsSdkException` when
the SDK can classify the failure. With
`WalletSelectionBehavior.Automatic`, successful callbacks return `Completed`.
With `WalletSelectionBehavior.Manual`, successful callbacks return
`WalletSelection`. Starting a new auth flow clears or replaces stale redirect
state, and `signOut()` clears it.

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
- selects the first matching wallet returned by WaaS when one or more wallets
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
`value` is a raw base-unit integer; use `parseUnits` to convert human-entered
decimal values before sending.
After execution, `sendTransaction` and `callContract` poll the WaaS status
endpoint briefly for an executed status or transaction hash. Pass
`waitForStatus = false` to return immediately after execute, or pass
`statusPolling` to tune the fast poll count, intervals, and timeout. If the
transaction remains pending when polling times out, the response contains the
`txnId`, `status = TransactionStatus.Pending`, and `txnHash = null`.
Use `getTransactionStatus` to refresh a transaction later. `listAccess` follows
WaaS cursors and returns all credentials, `listAccessPages` emits each page as a
`Flow`, and `listAccessPage` exposes one page at a time for manual cursor
pagination. Pass `pageSize` when fetching credentials that may span multiple
pages so each request uses an explicit limit.

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

## Indexer Service

```kotlin
suspend fun indexer.getTokenBalances(
    network: Network,
    contractAddress: String? = null,
    walletAddress: String,
    includeMetadata: Boolean,
    page: TokenBalancesPageRequest = TokenBalancesPageRequest(),
): TokenBalancesResult
```

```kotlin
suspend fun indexer.getNativeTokenBalance(
    network: Network,
    walletAddress: String,
): TokenBalance?
```

`contractAddress` can be omitted to query balances across token contracts.
`page` defaults to page `0` with page size `40`. `getNativeTokenBalance` returns
null when the indexer response has no native balance object. The wallet client
also uses it internally to enrich fee option balances.

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

## Errors

Public SDK APIs throw `OmsSdkException` when the SDK can classify a failure
without exposing generated WebRPC internals.

```kotlin
enum class OmsSdkErrorCode {
    HttpError,
    InvalidResponse,
    RequestFailed,
    AuthCommitmentConsumed,
    SessionMissing,
    SessionExpired,
    WalletSelectionStale,
    WalletSelectionUnavailable,
    WalletSelectionInFlight,
    TransactionStatusLookupFailed,
    ValidationError,
}
```

```kotlin
open class OmsSdkException(
    val code: OmsSdkErrorCode,
    val operation: OmsSdkOperation?,
    val status: Int?,
    val txnId: String?,
    val retryable: Boolean,
) : RuntimeException
```

```kotlin
enum class OmsSdkOperation(
    val id: String,
) {
    WalletStartEmailAuth,
    WalletCompleteEmailAuth,
    WalletStartOidcRedirectAuth,
    WalletHandleOidcRedirectCallback,
    WalletSendTransaction,
    // ...
}
```

`RequestFailed` covers classified WebRPC/backend failures, including backend
error codes newer than the generated WaaS client. `InvalidResponse` is reserved
for malformed or unparseable responses.

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
    val logoUrl: String,
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
)

fun interface FeeOptionSelector {
    suspend fun select(feeOptions: List<FeeOptionWithBalance>): FeeOptionSelection?
}
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

For OIDC redirect flows, pass the same behavior to the callback handler:

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
