# OMS Wallet Kotlin SDK

Build non-custodial OMS Wallet experiences on Android and Kotlin with email and
OIDC auth, secure session restore, message signing, transactions, and indexer
reads through a single `OMSWallet` root object.

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
- a valid `publishableKey`

The SDK does not require consumer apps to enable core library desugaring.

## Quick Start

```kotlin
val client = OMSWallet(
    context = context,
    publishableKey = "YOUR_PUBLISHABLE_KEY",
)

client.wallet.startEmailAuth("user@example.com")

// Use the one-time code the user enters from their email inbox.
val result = client.wallet.completeEmailAuth("123456")
check(result is CompleteAuthResult.WalletSelected)

val wallet = result.wallet
println("Wallet address: ${wallet.address}")

val signature =
    client.wallet.signMessage(
        network = Network.AMOY,
        message = "hello from OMS Wallet",
    )
println("Signature: $signature")

val balances =
    client.indexer.getBalances(
        walletAddress = wallet.address,
        networks = listOf(Network.AMOY),
        includeMetadata = true,
    )
println("Native balances: ${balances.nativeBalances}")
```

The SDK derives wallet API and indexer routing from the publishable key. Start
with sign-in, message signing, or balance reads. Transaction examples below use
Polygon Amoy; mainnet transactions can move real funds.

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
Subscribe with `client.wallet.onSessionExpired { event -> ... }` to route users
back to sign-in while preserving the expired session snapshot for reauth.
Listeners are delivered on the Android main thread.

## Authentication Details

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
Pass `sessionLifetimeSeconds` to `completeEmailAuth`, `signInWithOidcIdToken`,
`startOidcRedirectAuth`, or `handleOidcRedirectCallback` to request a different
value from 1 through `WalletClient.MAX_SESSION_LIFETIME_SECONDS` (`2_592_000`
seconds, 30 days). Invalid lifetimes are reported as
`OMSWalletErrorCode.ValidationError`.

```kotlin
if (client.wallet.walletAddress == null) {
    client.wallet.startEmailAuth("user@example.com")
    // A one-time code is sent to the user's email inbox.
    val result = client.wallet.completeEmailAuth("123456")
    check(result is CompleteAuthResult.WalletSelected)
    showWallet(result.wallet)
}
```

For OIDC ID-token flows such as Google Sign-In with Credential Manager:

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

Pass `provider` and `providerLabel` to `signInWithOidcIdToken` for custom
ID-token providers when you want those labels stored in `client.wallet.session.auth`.

For OIDC authorization-code redirect flows, start the redirect, open the
returned URL with your browser or Custom Tabs, then safely handle incoming app
links from `onCreate` / `onNewIntent`:

```kotlin
val started = client.wallet.startOidcRedirectAuth(
    provider = OidcProviders.google(),
    omsRelayReturnUri = "yourapp://auth/callback",
)

// Open started.authorizationUrl.

when (val result = client.wallet.handleOidcRedirectCallback(intent.data?.toString())) {
    is OidcRedirectAuthResult.Completed -> showWallet(result.wallet)
    OidcRedirectAuthResult.NotOidcRedirectCallback -> Unit
    OidcRedirectAuthResult.NoPendingAuth -> Unit
    is OidcRedirectAuthResult.Failed -> showRestartSignIn(result.error)
}
```

Use an OMS relay return URI that matches a deep link registered by your app,
such as `yourapp://auth/callback`. If your Google OAuth setup uses a custom web
client ID, pass it with `OidcProviders.google(clientId = "YOUR_WEB_CLIENT_ID")`.
`OidcProviders.google()` uses the SDK default Google client ID, `openid email
profile` scopes, PKCE auth-code mode, and Google authorization parameters
`access_type=offline` and `prompt=consent`. `OidcProviders.apple()` uses the SDK
default Apple Services ID, `openid email` scopes, `response_mode=form_post`, and
PKCE auth-code mode. These helpers are the SDK default OMS-relayed providers, so
`startOidcRedirectAuth` derives the OMS relay URL from the publishable-key Wallet
API base and stores `omsRelayReturnUri` in OIDC state.
Apple `form_post` works through that derived relay; do not configure a direct
app deep link as the Apple OAuth callback unless your provider flow supports it.
To use Google or Apple without the SDK relay, configure that provider as a custom
`OidcProviderConfig` with `providerRedirectUri`; custom providers do not use
`omsRelayReturnUri`.

| Flow | Provider config | App return URL | Provider OAuth callback |
|---|---|---|---|
| SDK default Google/Apple | `OidcProviders.google()` / `OidcProviders.apple()` | `omsRelayReturnUri` | OMS relay callback derived as `{walletApiUrl}/auth/waas/callback/{google|apple}` |
| Custom OIDC provider | Custom `OidcProviderConfig` | `providerRedirectUri` | `providerRedirectUri` |
| Google/Apple without SDK relay | Custom `OidcProviderConfig` for Google or Apple | `providerRedirectUri` | `providerRedirectUri` |

For Android redirect auth, register an app link or intent filter that matches the
return URI, such as `yourapp://auth/callback`, then pass incoming links from
`onCreate` / `onNewIntent` to `handleOidcRedirectCallback`.

For custom providers, set `providerRedirectUri` on `OidcProviderConfig` and do
not pass `omsRelayReturnUri`; the SDK sends `providerRedirectUri` as the OAuth
`redirect_uri` and expects the provider callback at that URL.

```kotlin
val acmeProvider =
    OidcProviderConfig(
        issuer = "https://login.acme.example",
        clientId = "acme-client-id",
        authorizationUrl = "https://login.acme.example/oauth/authorize",
        providerRedirectUri = "yourapp://auth/callback",
        provider = "acme",
        providerLabel = "Acme",
        scopes = listOf("openid", "email"),
    )

val started = client.wallet.startOidcRedirectAuth(provider = acmeProvider)
```

Pass `loginHint` only when you want to prefill or select a specific Google
account, such as during session-expiry reauth. When omitted, the SDK falls back
to the previous active session email when one exists before redirect auth
starts. Pass an empty string to force no `login_hint` for a call. Non-Google
providers do not receive `login_hint`.

Provider configs are the source of truth for redirect scopes and auth mode. If
`scopes` is omitted or empty, the authorization URL omits `scope`. PKCE
`code_challenge` parameters are sent only when
`authMode = OidcRedirectAuthMode.AuthCodePKCE`; use
`OidcRedirectAuthMode.AuthCode` for non-PKCE auth-code providers.

With the default automatic behavior, a successful redirect callback returns
`OidcRedirectAuthResult.Completed`; `WalletSelection` is only a successful branch
when the callback is handled with manual wallet selection.

To use your own wallet-selection UI, pass
`walletSelection = WalletSelectionBehavior.Manual` when completing auth:

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

Manual mode completes auth but does not select or create a wallet until the app
calls `pendingSelection.selectWallet(...)` or
`pendingSelection.createAndSelectWallet(...)`. `pendingSelection.wallets` is
already filtered to the requested wallet type, so the app picker can show those
wallets plus a "Create New Wallet" action:

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

For OIDC redirect auth, pass the behavior when starting redirect auth to store it
with the pending redirect state:

```kotlin
val started = client.wallet.startOidcRedirectAuth(
    provider = OidcProviders.google(),
    omsRelayReturnUri = "yourapp://auth/callback",
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
    OidcRedirectAuthResult.NotOidcRedirectCallback -> Unit
    OidcRedirectAuthResult.NoPendingAuth -> Unit
    is OidcRedirectAuthResult.Failed -> showRestartSignIn(result.error)
    is OidcRedirectAuthResult.Completed -> error("Expected manual wallet selection")
}
```

Useful state checks:

```kotlin
val walletAddress = client.wallet.session.walletAddress
val expiresAt = client.wallet.session.expiresAt
val auth = client.wallet.session.auth
val authEmail = auth?.email
```

`expiresAt` is an ISO-8601 timestamp string returned by the wallet API. OIDC
sessions include issuer/provider metadata on `OMSWalletOidcSessionAuth`, so apps
can display built-in Google and Apple sessions by provider label.

`client.wallet.session` only reports completed wallet-session state. It does not
include pending auth progress. Show OTP or redirect waiting UI from the method
result that started the flow, not from session state. Always pass incoming app
links to `handleOidcRedirectCallback`;
if it returns `NoPendingAuth`, show sign-in UI and let the user start again. A
fresh SDK instance restores completed wallet sessions, including the session
expiry and auth metadata returned by the wallet API, but not email OTP
pending state. Completed auth requests ask the wallet API for a one-week session
lifetime by default; pass `sessionLifetimeSeconds` to request a different
value from 1 through `WalletClient.MAX_SESSION_LIFETIME_SECONDS` (`2_592_000`
seconds, 30 days). For OIDC redirects, values passed to `startOidcRedirectAuth`
are stored with the pending redirect state and used on callback completion unless
`handleOidcRedirectCallback` overrides them. Auth completion loads all wallet
pages before selecting or creating a wallet. If auth completes but wallet
selection, wallet creation, or session persistence fails, the SDK clears the
in-memory auth session instead of retaining unrecoverable transient state.

Use the selected wallet. Transaction examples use Polygon Amoy and can move
testnet funds; fund the wallet from a faucet before sending and switch networks
only when you are ready for production.

```kotlin
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
            put("contents", "hello from android")
        }
    }

val signResult = client.wallet.signMessage(
    network = network,
    message = "hello from android",
)

val verifyResult = client.wallet.isValidMessageSignature(
    network = network,
    message = "hello from android",
    signature = signResult,
)

val typedSignature = client.wallet.signTypedData(
    network = network,
    typedData = typedDataJson,
)

val txResult = client.wallet.sendTransaction(
    network = network,
    to = "0x1111111111111111111111111111111111111111",
    value = parseUnits("0.01", 18),
)
```

`sendTransaction` prepares and executes the transaction, then polls the wallet API
status endpoint briefly for an executed status or transaction hash. If the
transaction is still pending when polling times out, the response keeps the
`txnId` with `status = TransactionStatus.Pending` and `txnHash = null`.
Transaction values are raw base-unit integers. Use `parseUnits` to convert
human-entered decimal values before sending. Import the helpers from
`technology.polygon.omswallet.utils`.

## Errors

Public SDK APIs throw `OMSWalletException` subclasses with stable fields such as
`code`, `operation`, `status`, nullable `retryable`, and `txnId`. When a failure comes
from a remote OMS service response or transport failure, the error also includes
`upstreamError` with normalized wallet API or indexer details for logging and
service-specific troubleshooting. Application logic should usually branch on the
SDK-level `code`.

For transaction writes, `OMS_TRANSACTION_EXECUTION_UNCONFIRMED` means the SDK
has a `txnId` from preparation, but the execute request failed before the SDK
could confirm whether the transaction was submitted; do not blindly resend the
same write. `OMS_TRANSACTION_STATUS_LOOKUP_FAILED` means the transaction was
submitted but status polling failed, so retry status lookup with the returned
`txnId`.
`retryable` describes the failed SDK operation, not the whole user intent.

```kotlin
try {
    client.wallet.startEmailAuth("user@example.com")
} catch (error: OMSWalletException) {
    println("${error.code} ${error.operation?.id} ${error.upstreamError}")
}
```

For raw token amount formatting and parsing:

```kotlin
val rawAmount = parseUnits("1.5", 18)
val displayAmount = formatUnits(rawAmount, 18)
```

For indexer balance lookups:

```kotlin
val walletAddress = requireNotNull(client.wallet.walletAddress)

val tokenBalances = client.indexer.getBalances(
    walletAddress = walletAddress,
    networks = listOf(network),
    contractAddresses = listOf("0x3333333333333333333333333333333333333333"),
    includeMetadata = true,
)

tokenBalances.nativeBalances.forEach { balance ->
    println("${balance.symbol.orEmpty()} ${balance.balance.orEmpty()}")
}

tokenBalances.balances.forEach { balance ->
    println("${balance.contractInfo?.symbol.orEmpty()} ${balance.contractInfo?.decimals ?: 0}")
}
```

Pass `includeMetadata = true` when you need token contract details or NFT/token
metadata from `balance.contractInfo` and `balance.tokenMetadata`.

For transaction history:

```kotlin
val history = client.indexer.getTransactionHistory(
    walletAddress = walletAddress,
    networks = listOf(network),
)
```

For raw calldata or transaction parameters beyond `to` and `value`, use the request overload:

```kotlin
val network = Network.AMOY

val txResult = client.wallet.sendTransaction(
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
val txResult = client.wallet.callContract(
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
val txResult = client.wallet.sendTransaction(
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
val txResult = client.wallet.sendTransaction(
    network = network,
    request = SendTransactionRequest(
        to = "0x3333333333333333333333333333333333333333",
        value = parseUnits("0", 18),
        data = "0x1234",
        mode = TransactionMode.Native,
    ),
) { feeOptions ->
    val selected = showFeePickerAndWaitForChoice(feeOptions)
    selected.selection
}
```

The selector receives `FeeOptionWithBalance` values. `balance` is the selected
wallet's raw indexer balance for that fee token when available. `available` is
formatted with the token decimals, while `availableRaw` keeps the raw integer
value. `decimals` is exposed as a regular `Int`. `selection` preserves the
API-provided `tokenID` when present and falls back to the token symbol. Sponsored
transactions skip fee selection; unsponsored transactions fail before execute
when no fee option can be selected.

To refresh a transaction later or manage active wallet credentials:

```kotlin
val status = client.wallet.getTransactionStatus(txnId = txResult.txnId)
val idToken = client.wallet.getIdToken(ttlSeconds = 300u)
val credentials = client.wallet.listAccess(pageSize = 25u)
client.wallet.listAccessPages(pageSize = 25u).collect { page ->
    renderCredentials(page.credentials)
}

credentials
    .firstOrNull { !it.isCaller }
    ?.let { client.wallet.revokeAccess(targetCredentialId = it.credentialId) }
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
