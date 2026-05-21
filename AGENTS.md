# AGENTS.md

## Project Overview

This repository is an Android/Kotlin SDK for OMS Client wallet, auth, signing,
indexer, and API integrations. It contains the publishable Android library
module plus a small Android sample app used for manual flows.

Use the Gradle wrapper for all build and verification work. Main CI runs SDK
unit tests, Android lint for both modules, and sample app assembly.

## Repository Layout

- `oms-client-kotlin-sdk/` - Android library module and public SDK surface.
- `oms-client-kotlin-sdk/src/main/java/com/omsclient/kotlin_sdk/` - SDK entry
  point, wallet/indexer clients, session storage, networking, utilities, and
  models.
- `oms-client-kotlin-sdk/src/main/java/com/omsclient/kotlin_sdk/generated/waas/`
  - generated WaaS WebRPC client/types.
- `oms-client-kotlin-sdk/src/test/` - JVM unit tests for SDK behavior,
  serialization, service clients, signing vectors, and utility helpers.
- `oms-client-kotlin-sdk/src/androidTest/` - instrumented Android tests for
  Android Keystore credential behavior.
- `app/` - Android sample app for auth, signing, transaction, and testbed flows.
- `docs/` - public API notes and request-signing parity vectors.
- `.github/workflows/android-ci.yml` - CI workflow for PRs and `master`.

## Development Commands

- `./gradlew --build-cache :oms-client-kotlin-sdk:testDebugUnitTest :oms-client-kotlin-sdk:lintDebug :app:lintDebug :app:assembleDebug`
  - CI-equivalent check from `.github/workflows/android-ci.yml`.
- `./gradlew :oms-client-kotlin-sdk:testDebugUnitTest`
  - Run SDK JVM unit tests. Use for most library logic changes.
- `./gradlew :oms-client-kotlin-sdk:lintDebug`
  - Run Android lint for the SDK module.
- `./gradlew :app:lintDebug`
  - Run Android lint for the sample app.
- `./gradlew :app:assembleDebug`
  - Build the sample debug APK and verify the SDK integrates into the app.
- `./gradlew ktlintCheck`
  - Run local Kotlin style lint for both modules. New violations should fail
    this check.
- `tools/install-git-hooks.sh`
  - Configure this checkout to use `tools/git-hooks`; the pre-push hook runs
    `./gradlew ktlintCheck`.
- `./gradlew ktlintFormat`
  - Auto-format Kotlin files where ktlint can safely correct violations.
- `./gradlew :oms-client-kotlin-sdk:connectedDebugAndroidTest`
  - Run local/manual instrumented SDK tests on a connected Android device or
    emulator. Use this when changing Android Keystore, credential, nonce, or
    platform session behavior.
- `./gradlew :oms-client-kotlin-sdk:publishToMavenLocal`
  - Publish the SDK artifact to the local Maven cache for packaging checks.

Use the Gradle wrapper; it resolves dependencies from Google Maven, Maven
Central, and the Gradle Plugin Portal.

## Verification Workflow

Always run the smallest relevant checks before reporting completion:

1. Kotlin style: `./gradlew ktlintCheck`.
2. SDK logic: `./gradlew :oms-client-kotlin-sdk:testDebugUnitTest`.
3. Sample app, manifest, or resource changes: `./gradlew :app:lintDebug`
   and `./gradlew :app:assembleDebug`.
4. Broad or cross-module changes: run the full CI-equivalent command listed in
   Development Commands.

Run only when relevant:

- Android Keystore, credential, nonce, or platform session changes:
  `./gradlew :oms-client-kotlin-sdk:connectedDebugAndroidTest` locally with a
  connected emulator or device. This is an intentional local/manual gate, not a
  GitHub CI requirement.
- Publishing/package validation: `./gradlew :oms-client-kotlin-sdk:publishToMavenLocal`
  when changing publication metadata or release packaging.

Add or update focused tests under `oms-client-kotlin-sdk/src/test/` for behavior
changes. Do not claim a check passed unless you ran it and have the command
result.

## Architecture and Boundaries

- `OMSClient` is the main public entry point. Keep public API changes aligned
  with `README.md` and `docs/api.md`.
- `WalletClient`, request signing, auth completion, wallet selection, fee
  selection, and transaction status polling are central library behavior; prefer
  small, tested changes there.
- `OMSClientEnvironment`, `OMSClientHttpClient`, and `OMSClientJson` define
  service routing and JSON behavior. Preserve existing serialization defaults
  unless tests and API docs are updated together.
- Some public models are client-facing aliases in `OMSClientModels.kt`, while
  auth, wallet selection, and signing APIs also expose generated WaaS types
  documented in `docs/api.md`. Check the docs and tests before changing that
  public boundary.
- Search for existing models, helper functions, serializers, and test fixtures
  before adding new ones. Do not add shallow wrappers unless they enforce a real
  invariant or isolate a real external boundary.
- Refactors should be thin vertical slices that leave the project compiling and
  testable after each step.

## High-Risk Areas

- `oms-client-kotlin-sdk/src/main/java/com/omsclient/kotlin_sdk/generated/waas/WaasWalletClient.kt`
  is generated WebRPC code and the largest production source file. Do not hand
  edit it casually. The upstream source of truth is the
  `https://github.com/0xsequence/waas` project; update this generated client
  from upstream as needed.
- `WalletClient.kt`, `WalletRequestSigner.kt`, `WalletAuthChallenge.kt`,
  `AndroidKeystoreP256CredentialSigner.kt`, and `AndroidKeystoreSessionStore.kt`
  handle auth state, credentials, nonces, signing, and persisted sessions. Treat
  behavior changes here as security-sensitive and add regression tests.
- Wallet auth, signing, access, session, and transaction tests live under
  `oms-client-kotlin-sdk/src/test/java/com/omsclient/kotlin_sdk/wallet/`. Add
  narrowly scoped tests near the behavior being changed instead of broad setup
  rewrites.

## Code Style

- Kotlin code style is set to `official` in `gradle.properties`.
- Android modules target Java 17 and Android `minSdk 26`.
- The repo uses Gradle Kotlin DSL and version catalog dependencies in
  `gradle/libs.versions.toml`.
- Ktlint is configured through `org.jlleitschuh.gradle.ktlint` and
  `.editorconfig`, with `ktlint_code_style = ktlint_official`.
- `ktlint_standard_package-name` is disabled because current package names
  include underscores as part of the existing SDK/sample namespace.
- `ktlint_standard_property-naming` is disabled because the repo intentionally
  exposes lowerCamel companion constants such as
  `OMSClientEnvironment.walletApiUrlDefault`; renaming those would change the
  documented API.
- Do not change ktlint rule exceptions casually. If a rule starts enforcing a
  real project invariant, remove the exception in the same change that fixes the
  affected code.
- Public SDK APIs use KDoc comments in existing entry points; preserve that
  style for new public members.
- There is no detekt static-analysis check configured.

## Testing Guidance

- Unit tests live in `oms-client-kotlin-sdk/src/test/java/...` and use JUnit 4.
- Network-facing client tests use MockWebServer where local HTTP behavior is
  needed.
- Signing parity tests are documented in `docs/complete_auth_vectors.md`; keep
  request payload, preimage, digest, signature, and authorization header behavior
  deterministic.
- Prefer tests through public or internal SDK interfaces that callers actually
  exercise. Avoid tests that only assert private call ordering.
- Mock true external boundaries such as network, time, randomness, and Android
  platform services. Prefer deterministic local fixtures for signing vectors.
- Add regression tests for bug fixes before or alongside the fix when practical.

## Generated Files and External Artifacts

- `oms-client-kotlin-sdk/src/main/java/com/omsclient/kotlin_sdk/generated/waas/WaasWalletClient.kt`
  is generated by `webrpc-gen` from the upstream `0xsequence/waas` project.
  Treat that upstream repository as the source of truth and update the generated
  client here only when the SDK needs a WaaS API refresh.
- `build/`, `*/build/`, `.gradle/`, `.gradle-user/`, `.npm-cache/`,
  `.android-user/`, `.kotlin/`, `.idea/`, `.codex/`, and `local.properties` are
  ignored local artifacts.
- Android launcher images and XML resources in `app/src/main/res/` are sample
  app assets. Change them only for sample app work.
- `tools/git-hooks/pre-push` is a versioned local hook. Run
  `tools/install-git-hooks.sh` after cloning to enable it for a checkout.

## Security and Configuration

- Do not commit secrets, access keys, signing keys, `local.properties`, Android
  Studio state, or local Gradle/cache files.
- `publicApiKey` and `projectId` are required by SDK consumers and sample flows.
  Keep examples placeholder-based unless the user explicitly provides test
  credentials.
- Android Keystore credential and session code must continue to avoid persisting
  private key material in app storage.
- Publishing/signing properties are read from Gradle properties. Do not add real
  publishing credentials or PGP material to the repo.

## Agent Workflow Rules

- Inspect relevant code, tests, docs, and Gradle configuration before editing.
- Keep changes narrowly scoped to the requested behavior.
- Preserve user changes in the working tree; never revert unrelated edits.
- Prefer existing package structure, naming, serializers, HTTP helpers, and
  session/signing abstractions.
- Update tests and docs when behavior or public API changes.
- Keep task specs durable: behavior, contracts, inputs/outputs, and acceptance
  criteria matter more than stale file or line references.
- Mark work as human-in-the-loop when it requires product judgment, external
  credentials, architecture decisions, manual Android device validation, or
  unclear security trade-offs.
- Run the relevant verification commands before reporting completion.

## PR / Commit Guidance

- Branch names should be plain and descriptive, such as `fix-login-timeout` or
  `add-wallet-tests`. Do not add a `codex/` prefix unless the user explicitly
  asks for that exact prefix.
