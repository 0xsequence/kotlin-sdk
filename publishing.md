# Publishing

The SDK is published as
`io.github.0xsequence:oms-wallet-kotlin-sdk:<POM_VERSION_NAME>`.
Maven Central versions are immutable; choose a new `POM_VERSION_NAME` for each
release.

Prerequisites:

- a Sonatype Central Portal account with the `io.github.0xsequence` namespace
  verified
- a Central Portal user token
- a GPG signing key whose public key has been published to a supported keyserver

1. Create a plain release branch from the latest `master`:

   ```sh
   git switch master
   git pull --ff-only origin master
   git switch -c release-oms-wallet-kotlin-sdk-<version>
   ```

2. Update `POM_VERSION_NAME` in `gradle.properties`. Update `README.md` and
   `docs/api.md` too if the release changes public behavior or API docs.

3. Run the release checks:

   ```sh
   ./gradlew --build-cache verifyReleasePublication
   ```

   This is the same task CI runs. It checks formatting, tests, Android lint,
   both example apps, the public API baseline, release artifact boundaries,
   release coordinates, Maven metadata, checksums, and the complete publication
   graph. It writes the validated Central Portal archive to
   `build/nmcp/zip/aggregation.zip` without uploading it. When signing
   properties are present, it also requires signatures for every artifact.

4. Commit the release changes, push the branch, and open a PR against `master`.
   Use a Conventional Commits title such as
   `chore(release): publish <version>`, and fill out the PR template with the
   checks you ran.

   ```sh
   git push -u origin release-oms-wallet-kotlin-sdk-<version>
   gh pr create --base master --title "chore(release): publish <version>"
   ```

5. After the PR is approved, merged, and `master` CI is green, publish from the
   merged commit. Do not commit Central Portal tokens, signing keys, passwords,
   `local.properties`, or local Gradle property files. Provide these as Gradle
   project properties from `~/.gradle/gradle.properties` or a secure
   environment:

   - `centralPortalUsername`
   - `centralPortalPassword`
   - `signingInMemoryKey`
   - `signingInMemoryKeyPassword`

   ```sh
   git switch master
   git pull --ff-only origin master
   ./gradlew publishAggregationToCentralPortal
   ```

   After the `USER_MANAGED` upload validates, open
   [Central Portal](https://central.sonatype.com/), review the deployment named
   `oms-wallet-kotlin-sdk:<version>`, and publish it.

6. After Maven Central propagation, verify the published POM is reachable and tag
   the exact published commit:

   ```sh
   POM_PATH=io/github/0xsequence/oms-wallet-kotlin-sdk/<version>/oms-wallet-kotlin-sdk-<version>.pom
   WAAS_RUNTIME_PATH=io/github/0xsequence/oms-wallet-kotlin-sdk-waas-generated/<version>
   curl -I "https://repo1.maven.org/maven2/$POM_PATH"
   curl -I "https://repo1.maven.org/maven2/$WAAS_RUNTIME_PATH/oms-wallet-kotlin-sdk-waas-generated-<version>.pom"
   curl -I "https://repo1.maven.org/maven2/$WAAS_RUNTIME_PATH/oms-wallet-kotlin-sdk-waas-generated-<version>.jar"
   git tag -s <version> -m <version>
   git push origin master <version>
   ```

## Alpha, Beta, and Snapshot Releases

Alpha and beta releases use the same release flow as stable releases. Set
`POM_VERSION_NAME` to a SemVer prerelease such as `0.2.1-alpha.1` or
`0.2.1-beta.1`, update exact dependency snippets that should point at the
prerelease, run the same validation commands, publish through Central Portal,
and tag the exact published commit. Maven Central versions are immutable, so do
not reuse a published prerelease version.

Consumers install prereleases with the exact version:

```kotlin
implementation("io.github.0xsequence:oms-wallet-kotlin-sdk:0.2.1-beta.1")
```

For snapshot testing, prefer local or internal test publication unless a remote
snapshot repository is intentionally added to this repo. The current Gradle
setup supports local snapshot artifacts through Maven Local:

```sh
./gradlew -PPOM_VERSION_NAME=0.2.1-SNAPSHOT publishToMavenLocal
```

Consumers on the same machine can test that local snapshot with `mavenLocal()`:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.0xsequence:oms-wallet-kotlin-sdk:0.2.1-SNAPSHOT")
}
```

Do not point README or `docs/api.md` install snippets at a snapshot unless that
snapshot is intentionally the documented tester install. Do not run the Central
Portal publish task for `-SNAPSHOT` versions unless the repository has been
explicitly configured and approved for remote snapshot publication.
