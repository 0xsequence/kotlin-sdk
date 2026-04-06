# Kotlin Android SDK Notes

## Goal

This document captures the recommended implementation shape for a Kotlin Android version of the Sequence C SDK.

The current C SDK implementation lives locally at:

- `../c-sdk`

The main conclusions are:

- the only truly domain-specific external dependency we likely need is `web3j-crypto`
- normal app infrastructure can stay minimal: `okhttp`, Kotlin serialization, and coroutines
- Ethereum keys should not be stored directly as Android Keystore EC keys
- instead, store the raw secp256k1 private key encrypted at rest, with the wrapping key protected by Android Keystore

## Recommended libraries

### Required or strongly recommended

- `org.web3j:crypto`
  - Use for Ethereum-specific cryptography:
  - secp256k1 private/public key handling
  - address derivation
  - Keccak-256
  - EIP-191 signing
  - signature recovery / verification helpers
- `com.squareup.okhttp3:okhttp`
  - Use for Sequence HTTP APIs:
  - `/rpc/Wallet`
  - `/rpc/API`
  - `/rpc/Indexer`

- `org.jetbrains.kotlinx:kotlinx-serialization-json`
  - Use for request/response models and compact JSON encoding/decoding

- `org.jetbrains.kotlinx:kotlinx-coroutines-android`
  - Use for async work on Android

### Optional but useful

- `com.squareup.okhttp3:mockwebserver3`
  - Good for testing signed requests and canned API responses

## Why `web3j`

`web3j-crypto` is the main external dependency that matters for this SDK.

It should cover the Ethereum-specific functionality we need:

- generate and load secp256k1 keys
- derive Ethereum addresses
- compute Keccak-256
- sign messages with EIP-191
- verify or recover signatures
- help with on-chain validation if needed

Everything else is normal Android/Kotlin infrastructure.

## What we do not need

We likely do not need:

- native C bindings
- `libsecp256k1` JNI wrappers
- Retrofit
- a larger blockchain framework beyond `web3j`
- AndroidX Security Crypto as the primary design

## Key storage design

### Main recommendation

Do not rely on Android Keystore to directly hold the Ethereum credential keypair.

Instead:

1. Generate the Ethereum secp256k1 private key in app code.
2. Generate a symmetric AES key in Android Keystore.
3. Encrypt the raw secp256k1 private key with that AES key.
4. Store the encrypted private key in app-private storage.
5. On use, decrypt into memory, sign, then clear temporary buffers as much as practical.

### Why

This SDK needs access to the raw Ethereum private key material for:

- request signing
- address derivation
- on-demand signing after session restore

Android Keystore is good at protecting platform-managed keys, but it is not a clean fit for directly managing exportable Ethereum secp256k1 private keys in a portable SDK design.

So the better Android model is:

- Android Keystore protects the wrapping key
- encrypted app storage holds the Ethereum private key blob

## What should be stored

Persist these values:

- encrypted secp256k1 private key
- `challenge`
- `verifier`
- selected wallet address
- signer address

Current implementation choice:

- a JSON session envelope in app-private `noBackupFilesDir`
- Android Keystore protects the AES wrapping key
- session snapshots contain metadata only, not the raw private key

The critical part is still the encryption model, not the exact persistence API.

### Security note

This storage model is appropriate for the normal Android app threat model:

- other ordinary apps should not be able to read the session file
- the encrypted wallet blob is excluded from Auto Backup via `noBackupFilesDir`
- the AES wrapping key is protected by Android Keystore

But it is not equivalent to a non-exportable hardware wallet design.

Important limitation:

- this SDK still needs raw secp256k1 private key material at signing time
- once decrypted inside the app process, that key can be exposed by a rooted device, app-process compromise, or hostile instrumentation/debug environment

So the design should be understood as:

- strong protection at rest
- best-effort protection in memory
- not a defense against full device or app compromise

## Mapping from the current C SDK

Reference implementation:

- `../c-sdk`
- cross-language correctness vectors and tests:
  - `../c-sdk/tests/request_signing_vectors.md`
  - `../c-sdk/tests/sequence_request_signing_test.c`
  - `../c-sdk/tests/timestamps_test.c`

The Kotlin SDK will need equivalents for:

- request payload builders
- request preimage formatting
- request signature generation
- authorization header formatting
- commit/complete auth flows
- wallet selection and wallet creation
- sign message
- send transaction
- verify message signature

The pure signing flow should remain the same:

1. Build the JSON payload.
2. Build the request preimage:
   - `POST /rpc/Wallet{endpoint}`
   - `nonce: {nonce}`
   - blank line
   - `{payload}`
3. Compute `keccak256(preimage)`.
4. Hex-encode the digest as lowercase `0x...`.
5. Sign that digest hex string as an EIP-191 UTF-8 message.
6. Build:
   - `Authorization: Ethereum_Secp256k1 scope="...",cred="...",nonce=...,sig="..."`

## How to confirm the Kotlin implementation is correct

Use the C SDK tests and vectors in `../c-sdk/tests` as the source of truth for the local signing pipeline.

These vectors confirm that an implementation produces the correct:

- JSON payloads
- request preimages
- Keccak digests
- signer addresses
- EIP-191 signatures
- authorization headers
- monotonic nonce behavior

For the Kotlin SDK, the goal should be to reproduce the same outputs for the same fixed inputs.

This is the right way to validate:

- request formatting
- digest generation
- signature generation
- auth header generation

These tests do not replace live integration tests, but they are the correct cross-language check for the SDK's canonical request-signing behavior.

## Suggested Android module shape

- `network/`
  - OkHttp client
  - request interceptors if useful
  - endpoint wrappers for Wallet, API, and Indexer

- `crypto/`
  - key generation
  - key loading
  - Keccak
  - EIP-191 signing
  - address derivation

- `storage/`
  - encrypted key storage
  - persisted session metadata

- `wallet/`
  - auth flow
  - use/create wallet
  - sign message
  - send transaction

- `models/`
  - Kotlin serialization DTOs

## Current public SDK shape

- `PolygonSdk(context, projectAccessKey, environment = SequenceEnvironment())`
  - Android-friendly constructor
  - defaults to Keystore-backed persisted session storage
  - restores persisted session metadata automatically on initialization
- `SequenceEnvironment`
  - endpoint configuration only
  - does not store `projectAccessKey`

Demo or staging usage should be explicit, for example:

- `SequenceEnvironment.demoDefaults()`
- wallet public state is intentionally sanitized:
  - `hasPendingSignIn`
  - `walletAddress`
  - `signerAddress`
- intended high-level auth flow is:
  - `signInWithEmail(email)`
  - `completeEmailSignIn(code)`
    - returns the selected wallet directly when one usable wallet exists
    - creates a wallet when none exist yet
    - if multiple wallets are possible, use `completeEmailSignIn(code, selectWallet)`

The raw private key is not part of the public session API. It is decrypted on demand through the private-key store only when a signing operation needs it.

## Practical minimum dependency set

If we want the leanest production-reasonable stack:

- `org.web3j:crypto`
- `com.squareup.okhttp3:okhttp`
- `org.jetbrains.kotlinx:kotlinx-serialization-json`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`

## Platform baseline

- current `minSdk` is `26`
- this matches the current implementation's use of Android/Java APIs such as `java.time` and `java.util.Base64`

## Key implementation note

The most important non-obvious decision is key storage:

- use Android Keystore indirectly
- do not assume Android Keystore should directly own the Ethereum secp256k1 keypair

That design is the cleanest match for the current C SDK behavior and should make restore/sign flows much easier to implement consistently.
