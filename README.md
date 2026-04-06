# Polygon Kotlin SDK

Android and Kotlin SDK for wallet, auth, signing, and API/indexer integrations.

## Status

This repository is prepared for first-release publishing, but Maven Central registration and signing setup are still external steps.

Intended Maven coordinates:

```kotlin
implementation("io.github.0xsequence:kotlin-sdk:0.0.1")
```

## Modules

- `polygon-kotlin-sdk`
  - publishable Android library module
- `app`
  - sample/demo application

## SDK Usage

```kotlin
val sdk = SequenceSdk(
    context = context,
    projectAccessKey = "YOUR_PROJECT_ACCESS_KEY",
)

sdk.wallet.restorePersistedSession()

if (!sdk.wallet.isSignedIn) {
    sdk.wallet.signInWithEmail("user@example.com")
    val auth = sdk.wallet.confirmEmailSignIn("123456")
    sdk.wallet.resolveWallet(auth)
}

val walletAddress = sdk.wallet.requireWalletAddress()

val signResult = sdk.wallet.signMessage(
    chainId = "80002",
    message = "hello from android",
)

val verifyResult = sdk.api.isValidMessageSignature(
    chainId = "80002",
    walletAddress = walletAddress,
    message = "hello from android",
    signature = signResult.signature,
)

val txResult = sdk.wallet.sendTransaction(
    chainId = "80002",
    to = "0xE5E8B483FfC05967FcFed58cc98D053265af6D99",
    value = "0",
)
```

## Build

```sh
./gradlew :polygon-kotlin-sdk:testDebugUnitTest
./gradlew :app:assembleDebug
```

## Publishing Notes

- group: `io.github.0xsequence`
- artifact: `kotlin-sdk`
- version: `0.0.1`

External release prerequisites still required:

- verify the `io.github.0xsequence` namespace in Sonatype Central Portal
- configure signing keys for release artifacts
- configure the final Central Portal upload flow
