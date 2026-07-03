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
   ./gradlew ktlintCheck
   ./gradlew --build-cache \
     :oms-wallet-kotlin-sdk:checkPublicApiDoesNotExposeGeneratedWaas \
     :oms-wallet-kotlin-sdk:testDebugUnitTest \
     :oms-wallet-kotlin-sdk:lintDebug \
     :app:lintDebug \
     :app:assembleDebug
   ./gradlew :oms-wallet-kotlin-sdk:publishToMavenLocal
   ./gradlew nmcpZipAggregation
   unzip -l build/nmcp/zip/aggregation.zip
   ```

   `publishToMavenLocal` verifies the Maven publication locally.
   `nmcpZipAggregation` builds the Central Portal upload archive without
   uploading it. Without signing properties this is only a structural preview;
   for a final publish preview, provide `signingInMemoryKey` and
   `signingInMemoryKeyPassword` and confirm the archive contains `.asc`
   signature files.

4. Inspect the local publication:

   ```sh
   RELEASE_DIR=~/.m2/repository/io/github/0xsequence/oms-wallet-kotlin-sdk/<version>

   jar tf "$RELEASE_DIR/oms-wallet-kotlin-sdk-<version>.aar" | \
     rg '^libs/oms-wallet-kotlin-sdk-waas-generated\.jar$'

   rg "oms-wallet-kotlin-sdk-waas-generated|generated\\.waas" \
     "$RELEASE_DIR"/*.pom "$RELEASE_DIR"/*.module

   jar tf "$RELEASE_DIR/oms-wallet-kotlin-sdk-<version>-sources.jar" | \
     rg "generated/waas|WaasWallet"

   jar tf "$RELEASE_DIR/oms-wallet-kotlin-sdk-<version>-javadoc.jar" | \
     rg "generated/waas|WaasWallet|waas"
   ```

   The AAR check should print the embedded generated WaaS jar. The POM, module,
   sources jar, and javadoc jar checks should return no matches.

5. Commit the release changes, push the branch, and open a PR against `master`.
   Use a Conventional Commits title such as
   `chore(release): publish <version>`, and fill out the PR template with the
   checks you ran.

   ```sh
   git push -u origin release-oms-wallet-kotlin-sdk-<version>
   gh pr create --base master --title "chore(release): publish <version>"
   ```

6. After the PR is approved, merged, and `master` CI is green, publish from the
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

7. After Maven Central propagation, verify the published POM is reachable and tag
   the exact published commit:

   ```sh
   POM_PATH=io/github/0xsequence/oms-wallet-kotlin-sdk/<version>/oms-wallet-kotlin-sdk-<version>.pom
   curl -I "https://repo1.maven.org/maven2/$POM_PATH"
   git tag -a <version> -m "Release <version>"
   git push origin master <version>
   ```
