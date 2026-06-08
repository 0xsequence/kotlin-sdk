# Contributing

## Prerequisites

- JDK 17 (temurin recommended)
- Android SDK with API 26+
- Android Studio or IntelliJ IDEA (optional but recommended)

## Setup

```bash
git clone https://github.com/0xsequence/kotlin-sdk.git
cd kotlin-sdk
tools/install-git-hooks.sh   # enables ktlint pre-push hook
```

Resolve dependencies from Google Maven, Maven Central, and the Gradle Plugin Portal via the Gradle
wrapper — no extra configuration needed.

## Building

```bash
# Full CI-equivalent check
./gradlew --build-cache :oms-client-kotlin-sdk:testDebugUnitTest :oms-client-kotlin-sdk:lintDebug :app:lintDebug :app:assembleDebug

# SDK unit tests only
./gradlew :oms-client-kotlin-sdk:testDebugUnitTest

# Kotlin style check
./gradlew ktlintCheck

# Auto-fix style violations
./gradlew ktlintFormat
```

See [TESTING.md](./TESTING.md) for the full test reference.

## Making Changes

1. Create a branch from `master` with a plain, descriptive name — e.g. `fix-login-timeout` or `add-wallet-tests`.
2. Make focused changes. Keep PRs narrow; one concern per PR is easier to review and revert.
3. Add or update tests for behavior changes (see [TESTING.md](./TESTING.md)).
4. Run the relevant verification checks before pushing (the pre-push hook runs `ktlintCheck` automatically).
5. Update `docs/api.md` and `README.md` if you change the public API surface.

## Opening a PR

- PR title must follow [Conventional Commits](https://www.conventionalcommits.org), e.g.
  `feat(sdk): add wallet revocation helper` or `fix(auth): handle expired nonce correctly`.
- Fill in the PR template: summary, what changed, and which checks you ran.
- Do not commit `local.properties`, Android Studio state, or any secrets.

## Code Style

Kotlin style is enforced by ktlint (`ktlint_official` code style). Run `./gradlew ktlintFormat` to
auto-fix violations before pushing. The pre-push hook runs `ktlintCheck` for you if you installed
the hooks in the setup step.

## Security

Do not commit secrets, access keys, signing keys, or `local.properties`. If you discover a
security issue, please report it privately rather than opening a public issue.
