# TESTING.md

How testing works in this repo. AGENTS.md points here so agents know how to verify changes.

## Frameworks & Tools

- **Runner:** JUnit 4
- **Assertions:** JUnit 4 assertions + Kotlin stdlib
- **Network mocking:** MockWebServer (`com.squareup.okhttp3:mockwebserver3`)
- **Instrumented tests:** AndroidJUnit4 runner (`androidx.test.runner`)

## Unit Tests

- **Scope:** Pure JVM tests for SDK logic — serialization, request signing, service clients, session
  handling, wallet auth flows, and utility helpers. No Android platform or real network required.
- **Location:** `oms-client-kotlin-sdk/src/test/java/com/omsclient/kotlin_sdk/`
- **Run:** `./gradlew :oms-client-kotlin-sdk:testDebugUnitTest`

Key subdirectories:
- `wallet/` — wallet auth, signing, access, session, and transaction tests
- `network/` — indexer and service client tests using MockWebServer
- `utils/` — unit formatting, parsing, and nonce helper tests

## Instrumented (Android) Tests

- **Scope:** Tests that require the Android runtime — specifically Android Keystore credential
  creation, nonce generation, and persisted session behavior. These must run on a connected device
  or emulator.
- **Location:** `oms-client-kotlin-sdk/src/androidTest/`
- **Run:** `./gradlew :oms-client-kotlin-sdk:connectedDebugAndroidTest`
- **Prerequisites:** A connected Android device or running emulator. This is an intentional
  local/manual gate; it does **not** run in GitHub CI.

## Conventions

- Add tests under `oms-client-kotlin-sdk/src/test/` for new SDK behavior changes.
- Wallet auth, signing, and session tests belong in `wallet/` near the behavior being changed.
  Prefer narrowly scoped additions over broad setup rewrites.
- Signing parity tests must keep request payload, preimage, digest, signature, and authorization
  header behavior deterministic. Reference vectors live in `docs/complete_auth_vectors.md`.
- Mock true external boundaries (network, time, randomness, Android platform services). Use
  deterministic local fixtures for signing vectors.
- Prefer tests through public or internal SDK interfaces that callers actually exercise. Avoid
  tests that only assert private call ordering.
- Add a regression test for bug fixes before or alongside the fix when practical.

### Public Error Contract Tests

- Use `docs/error-contracts.md` as the audit matrix for public SDK error surfaces, recovery
  semantics, `upstreamError` expectations, and owning tests.
- Keep serialized public error shape assertions centralized in
  `PublicErrorContractsTest`; focused tests should cover local behavior or edge cases without
  duplicating the full public-field matrix.
- Exercise real public runtime APIs such as `client.wallet.*`, `client.indexer.*`, auth result
  actions, and public exception classes.
- Mock only external boundaries: network responses, time, randomness, Android platform services, or
  signer behavior.
- Assert stable public fields only: exception class, `code`, `operation`, `message`, `status`,
  `retryable`, `txnId`, and `upstreamError`.
- Do not assert raw `cause`, stacks, generated WebRPC internals, request headers, timestamps, or
  full backend payloads as public error contract fields.
- Include `upstreamError` only when the tested path truthfully crosses a remote service or transport
  boundary. SDK-local validation, session, and wallet-selection failures should assert no upstream
  details.
- Treat `code` and `operation` as stronger contract fields than `message`. Message changes are
  allowed when intentional, but they should be reviewed as user-visible API/UX changes.
- `retryable` describes the failed SDK operation, not the whole user intent. A retryable status
  lookup failure does not mean the original transaction write should be blindly resent.

## Execution Summary

| Goal | Command |
|---|---|
| Run SDK unit tests | `./gradlew :oms-client-kotlin-sdk:testDebugUnitTest` |
| Run instrumented tests (requires device/emulator) | `./gradlew :oms-client-kotlin-sdk:connectedDebugAndroidTest` |
| Run ktlint style check | `./gradlew ktlintCheck` |
| Auto-fix ktlint violations | `./gradlew ktlintFormat` |
| Full CI-equivalent check | `./gradlew --build-cache :oms-client-kotlin-sdk:testDebugUnitTest :oms-client-kotlin-sdk:lintDebug :app:lintDebug :app:assembleDebug` |
