# OMS Client Kotlin SDK

Android and Kotlin SDK for wallet, auth, signing, and API/indexer integrations.

## Installation

Planned Maven coordinates:

```kotlin
implementation("io.github.0xsequence:oms-client-kotlin-sdk:0.0.1")
```

Until the package is published, use the source directly from this repository.

## What It Provides

- email sign-in flow against the wallet API
- OIDC ID-token sign-in flow against the wallet API
- non-extractable Android Keystore request credential for wallet API signing
- persisted wallet session metadata
- wallet selection and wallet creation flows
- message signing
- transaction sending
- signature verification through the generated WaaS public client
- token balance lookups through the indexer service
- unit formatting and parsing helpers for raw token amounts

## Requirements

- Android `minSdk 26`
- Kotlin/Android app using the Android library module
- a valid `projectAccessKey`

## Quick Start

Create the SDK with the Android-friendly constructor:

```kotlin
val client = OMSClient(
    context = context,
    projectAccessKey = "YOUR_PROJECT_ACCESS_KEY",
)
```

That constructor uses secure persisted session storage by default.
Wallet API requests are signed with a non-extractable Android Keystore P-256
credential (`webcrypto-secp256r1`), so the private credential key is not written
to app storage.
Only completed wallet sessions are restored automatically. Pending auth state is
not exposed through `client.session`; email OTP pending state is kept in memory,
while OIDC redirect verifier/state is stored internally only so incoming app
links can be handled.

If you need a custom environment:

```kotlin
val client = OMSClient(
    context = context,
    projectAccessKey = "YOUR_PROJECT_ACCESS_KEY",
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
    projectAccessKey = "YOUR_PROJECT_ACCESS_KEY",
    environment = OMSClientEnvironment.demoDefaults(),
)
```

## Example Flow

`OMSClient` restores a persisted session automatically when it is created. Start email sign-in only if no wallet is currently selected:

```kotlin
if (client.wallet.address == null) {
    client.startEmailAuth("user@example.com")
    // A one-time code is sent to the user's email inbox.
    client.completeEmailAuth("123456")
}
```

For OIDC ID-token flows such as Google Sign-In with Credential Manager:

```kotlin
val wallet = client.signInWithOidcIdToken(
    idToken = googleIdToken,
    issuer = "https://accounts.google.com",
    audience = "YOUR_WEB_CLIENT_ID",
)
```

For OIDC authorization-code PKCE redirect flows, start the redirect, open the
returned URL with your browser or Custom Tabs, then safely handle incoming app
links from `onCreate` / `onNewIntent`:

```kotlin
val started = client.startOidcRedirectAuth(
    provider = OidcProviders.google(clientId = "YOUR_WEB_CLIENT_ID"),
    redirectUri = "omsclientkotlindemo://auth/callback",
)

// Open started.authorizationUrl.

when (val result = client.handleOidcRedirectCallback(intent.data?.toString())) {
    is OidcRedirectAuthResult.Completed -> showWallet(result.wallet)
    OidcRedirectAuthResult.NotOidcRedirectCallback -> Unit
    OidcRedirectAuthResult.NoPendingAuth -> Unit
    is OidcRedirectAuthResult.Failed -> showRestartSignIn(result.error)
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
session lifetime. If auth completes but wallet selection, wallet creation, or
session persistence fails, the SDK clears the in-memory auth session instead of
retaining unrecoverable transient state.

Use the selected wallet:

```kotlin
val network = Network.POLYGON_AMOY
val walletAddress = requireNotNull(client.wallet.address)

val signResult = client.wallet.signMessage(
    network = network,
    message = "hello from android",
)

val verifyResult = client.wallet.isValidMessageSignature(
    network = network,
    walletAddress = walletAddress,
    message = "hello from android",
    signature = signResult.signature,
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
`txnId` with `status = TransactionStatus.Pending` and `txHash = null`.
Transaction values are raw base-unit integers. Use `parseUnits` to convert
human-entered decimal values before sending. Import the helpers from
`com.omsclient.kotlin_sdk.utils`.

For raw token amount formatting and parsing:

```kotlin
val rawAmount = parseUnits("1.5", 18)
val displayAmount = formatUnits(rawAmount, 18)
```

For contract calls or transaction parameters beyond `to` and `value`, use the request overload:

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

If your app may need to choose between multiple wallets, use the selector overload:

```kotlin
val wallet = client.completeEmailAuth("123456") { wallets ->
    showWalletPickerAndWaitForChoice(wallets)
}
```

## API Reference

The full public API surface is documented in [docs/api.md](docs/api.md).

## Sample App

This repository includes an Android sample app in [`app/`](app/) that demonstrates:

- Google sign-in with Android Credential Manager
- email sign-in
- wallet selection after sign-in
- message signing and verification
- transaction sending
- a lower-level testbed for manual endpoint/config testing

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
