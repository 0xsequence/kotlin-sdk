# Polygon Kotlin SDK

Android and Kotlin SDK for wallet, auth, signing, and API/indexer integrations.

## Installation

Planned Maven coordinates:

```kotlin
implementation("io.github.0xsequence:kotlin-sdk:0.0.1")
```

Until the package is published, use the source directly from this repository.

## What It Provides

- email sign-in flow against the wallet API
- persisted wallet session storage backed by Android Keystore
- wallet selection and wallet creation flows
- message signing
- transaction sending
- signature verification through the API service
- token balance lookups through the indexer service

## Requirements

- Android `minSdk 26`
- Kotlin/Android app using the Android library module
- a valid `projectAccessKey`

## Quick Start

Create the SDK with the Android-friendly constructor:

```kotlin
val polygonSdk = PolygonSdk(
    context = context,
    projectAccessKey = "YOUR_PROJECT_ACCESS_KEY",
)
```

That constructor uses secure persisted session storage by default.

If you need a custom environment:

```kotlin
val polygonSdk = PolygonSdk(
    context = context,
    projectAccessKey = "YOUR_PROJECT_ACCESS_KEY",
    environment = SequenceEnvironment(
        walletApiUrl = "https://...",
        apiRpcUrl = "https://...",
        indexerUrlTemplate = "https://{value}-indexer.example.com/rpc/Indexer/",
    ),
)
```

For demo or staging-style defaults:

```kotlin
val polygonSdk = PolygonSdk(
    context = context,
    projectAccessKey = "YOUR_PROJECT_ACCESS_KEY",
    environment = SequenceEnvironment.demoDefaults(),
)
```

## Example Flow

`PolygonSdk` restores a persisted session automatically when it is created. Start email sign-in only if no wallet is currently selected:

```kotlin
if (polygonSdk.wallet.walletAddress == null) {
    polygonSdk.wallet.signInWithEmail("user@example.com")
    // A one-time code is sent to the user's email inbox.
    polygonSdk.wallet.completeEmailSignIn("123456")
}
```

Useful state checks:

```kotlin
val walletAddress = polygonSdk.wallet.walletAddress
val signerAddress = polygonSdk.wallet.signerAddress
val hasPendingSignIn = polygonSdk.wallet.hasPendingSignIn
```

Use the selected wallet:

```kotlin
val walletAddress = requireNotNull(polygonSdk.wallet.walletAddress)

val signResult = polygonSdk.wallet.signMessage(
    chainId = "80002",
    message = "hello from android",
)

val verifyResult = polygonSdk.api.isValidMessageSignature(
    chainId = "80002",
    walletAddress = walletAddress,
    message = "hello from android",
    signature = signResult.signature,
)

val txResult = polygonSdk.wallet.sendTransaction(
    chainId = "80002",
    to = "0xE5E8B483FfC05967FcFed58cc98D053265af6D99",
    value = "0",
)
```

If your app may need to choose between multiple wallets, use the selector overload:

```kotlin
val wallet = polygonSdk.wallet.completeEmailSignIn("123456") { wallets ->
    showWalletPickerAndWaitForChoice(wallets)
}
```

## API Reference

The full public API surface is documented in [docs/api.md](docs/api.md).

## Sample App

This repository includes an Android sample app in [`app/`](app/) that demonstrates:

- email sign-in
- wallet recovery/selection flow
- message signing and verification
- transaction sending
- a lower-level testbed for manual endpoint/config testing

## Build From Source

```sh
./gradlew :polygon-kotlin-sdk:testDebugUnitTest
./gradlew :app:assembleDebug
```
