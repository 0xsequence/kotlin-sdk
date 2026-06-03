# OMS Client Kotlin SDK

Android and Kotlin SDK for wallet, auth, signing, and API/indexer integrations.

## Installation

Maven Central:

```kotlin
implementation("io.github.0xsequence:oms-client-kotlin-sdk:0.1.0-alpha.1")
```

This is the only artifact consumers add. The generated WaaS client is packaged
inside the AAR as an internal implementation detail; consumers should use the
SDK APIs documented below instead of importing generated classes.

## What It Provides

- email sign-in flow against the wallet API
- OIDC ID-token sign-in flow against the wallet API
- non-extractable Android Keystore request credential for wallet API signing
- persisted wallet session metadata
- wallet selection and wallet creation flows
- message signing
- typed-data signing
- transaction sending and contract calls
- transaction status lookup
- wallet access listing and revocation
- message and typed-data signature verification through WaaS
- native and token balance lookups through the indexer service
- unit formatting and parsing helpers for raw token amounts

## Requirements

- Android `minSdk 26`
- Android `compileSdk 34` or newer
- Java 17 Android compile options
- Kotlin/Android app using the Android library module
- a valid `publishableKey`
- a valid `projectId`

The sample app in this repository uses additional Google Sign-In / AndroidX
Credential Manager dependencies and therefore compiles with SDK 35. That sample
app requirement does not raise the published SDK artifact's consumer
`compileSdk` floor.

## Quick Start

Create the SDK with the Android-friendly constructor:

```kotlin
val client = OMSClient(
    context = context,
    publishableKey = "YOUR_PUBLISHABLE_KEY",
    projectId = "YOUR_PROJECT_ID",
)
```

That constructor wires two separate Android-backed pieces. Wallet API requests
are authorized by a non-extractable Android Keystore P-256 credential
(`ecdsa-p256-sha256`) owned by the credential signer, so the private credential
key is never written to SDK session storage.

Session restore uses a separate metadata store. The SDK persists only
completed-session metadata in app-private no-backup storage: wallet id/address,
signer address/algorithm, expiry, login type, and optional session email. This
metadata is not wallet authorization material: by itself it cannot sign requests
or access a wallet. Restore succeeds only while the matching Keystore credential
still exists, and wallet operations must sign fresh requests with that
credential. `publishableKey` is sent as `X-Access-Key`, while `projectId` is used
as the WaaS signing scope.

Pending email OTP state is kept in memory. OIDC redirect state is stored only to
complete the browser redirect flow and is cleared when the flow completes, fails,
or is replaced.

Expired sessions are made inactive before protected wallet operations and throw
`OmsSessionException` with `code = OmsSdkErrorCode.SessionExpired`. The SDK
clears the active signer/session state, but keeps expired completed-session
metadata in storage until the app starts a new auth flow or calls `signOut()`.
Subscribe with `client.wallet.onSessionExpired { event -> ... }` to route users
back to sign-in while preserving the expired session snapshot for reauth.
Listeners are delivered on the Android main thread.

If you need a custom environment:

```kotlin
val client = OMSClient(
    context = context,
    publishableKey = "YOUR_PUBLISHABLE_KEY",
    projectId = "YOUR_PROJECT_ID",
    environment = OMSClientEnvironment(
        walletApiUrl = "https://...",
        apiRpcUrl = "https://...",
        indexerUrlTemplate = "https://{value}-indexer.example.com/rpc/Indexer/",
    ),
)
```

For demo or staging-style defaults:

```kotlin
val client = OMSClient(
    context = context,
    publishableKey = "YOUR_PUBLISHABLE_KEY",
    projectId = "YOUR_PROJECT_ID",
    environment = OMSClientEnvironment.demoDefaults(),
)
```

## Example Flow

`OMSClient` restores a persisted session automatically when it is created. Apps
can hide sign-in controls while a wallet is selected, but starting a new auth
flow intentionally replaces any existing wallet session so users can re-auth or
switch accounts:

By default email OTP and OIDC ID-token auth completion use
`WalletSelectionBehavior.Automatic`. They select a wallet for the requested
wallet type, create one when none exists, and return
`CompleteAuthResult.WalletSelected`. If more than one matching wallet exists,
automatic mode selects the first matching wallet returned by WaaS. Use manual
mode for apps that need to let users choose between multiple wallets.

Completed auth requests ask WaaS for a one-week session lifetime by default
(`WalletClient.DEFAULT_SESSION_LIFETIME_SECONDS`, `604_800` seconds).
Pass `sessionLifetimeSeconds` to `completeEmailAuth`, `signInWithOidcIdToken`,
or `handleOidcRedirectCallback` to request a different positive whole-number
lifetime in seconds. Invalid lifetimes are reported as
`OmsSdkErrorCode.ValidationError`.

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

For OIDC authorization-code PKCE redirect flows, start the redirect, open the
returned URL with your browser or Custom Tabs, then safely handle incoming app
links from `onCreate` / `onNewIntent`:

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
`yourapp://auth/callback`. If your Google OAuth setup uses a custom web client
ID, pass it with `OidcProviders.google(clientId = "YOUR_WEB_CLIENT_ID")`.
Pass `loginHint` only when you want to prefill or select a specific Google
account, such as during session-expiry reauth. When omitted, the SDK falls back
to the previous active session email when one exists before redirect auth
starts. Pass an empty string to force no `login_hint` for a call. Non-Google
providers do not receive `login_hint`.

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

For OIDC redirect auth, pass the same behavior when handling the callback:

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
val walletAddress = client.session.walletAddress
val expiresAt = client.session.expiresAt
val loginType = client.session.loginType
val sessionEmail = client.session.sessionEmail
```

`client.session` only reports completed wallet-session state. Pending auth
state, OIDC redirect verifier/state, and signer details are SDK internals. Show
OTP or redirect waiting UI from the method result that started the flow, not from
session state. Always pass incoming app links to `handleOidcRedirectCallback`;
if it returns `NoPendingAuth`, show sign-in UI and let the user start again. A
fresh SDK instance restores completed wallet sessions, including the session
expiry, login type, and email returned by the wallet API, but not email OTP
pending state. Completed auth requests ask the wallet API for a one-week
session lifetime. Auth completion loads all wallet pages before selecting or
creating a wallet. If auth completes but wallet selection, wallet creation, or
session persistence fails, the SDK clears the in-memory auth session instead of
retaining unrecoverable transient state.

Use the selected wallet:

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
            put("name", "OMS Client")
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
    to = "0xE5E8B483FfC05967FcFed58cc98D053265af6D99",
    value = parseUnits("0.01", 18),
)
```

`sendTransaction` prepares and executes the transaction, then polls the WaaS
status endpoint briefly for an executed status or transaction hash. If the
transaction is still pending when polling times out, the response keeps the
`txnId` with `status = TransactionStatus.Pending` and `txnHash = null`.
Transaction values are raw base-unit integers. Use `parseUnits` to convert
human-entered decimal values before sending. Import the helpers from
`com.omsclient.kotlin_sdk.utils`.

For raw token amount formatting and parsing:

```kotlin
val rawAmount = parseUnits("1.5", 18)
val displayAmount = formatUnits(rawAmount, 18)
```

For indexer balance lookups:

```kotlin
val walletAddress = requireNotNull(client.wallet.walletAddress)

val nativeBalance = client.indexer.getNativeTokenBalance(
    network = network,
    walletAddress = walletAddress,
)

val tokenBalances = client.indexer.getTokenBalances(
    network = network,
    contractAddress = "0xTokenContract",
    walletAddress = walletAddress,
    includeMetadata = true,
)
```

For raw calldata or transaction parameters beyond `to` and `value`, use the request overload:

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

For WaaS ABI-style contract calls, use `callContract`:

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

If the prepared transaction returns fee options, pass a selector callback:

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

The selector receives `FeeOptionWithBalance` values. `balance` is the selected
wallet's raw indexer balance for that fee token when available. `available` is
formatted with the token decimals, while `availableRaw` keeps the raw integer
value. `decimals` is exposed as a regular `Int`.

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

## Sample App

This repository includes an Android sample app in [`app/`](app/) that demonstrates:

- Google sign-in with Android Credential Manager
- Google OIDC redirect sign-in
- email sign-in
- custom session lifetime input for expiry testing
- expired-session reauth UI
- wallet selection after sign-in
- message signing and verification
- transaction sending

## Build From Source

To enable the local pre-push Kotlin style gate for this checkout:

```sh
tools/install-git-hooks.sh
```

The hook runs `./gradlew ktlintCheck` before push. This is intentionally local
and is not wired into GitHub CI.

```sh
./gradlew :oms-client-kotlin-sdk:testDebugUnitTest
./gradlew ktlintCheck
./gradlew :oms-client-kotlin-sdk:lintDebug
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```
