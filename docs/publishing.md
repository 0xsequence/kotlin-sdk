# Publishing

## Local Maven Publish

Use `mavenLocal` to verify that the Android library can be packaged and consumed
as a Maven artifact before setting up a remote repository.

Run the relevant SDK checks first:

```sh
./gradlew ktlintCheck :oms-client-kotlin-sdk:testDebugUnitTest :oms-client-kotlin-sdk:lintDebug
```

Publish the SDK to the local Maven cache with a throwaway version:

```sh
./gradlew :oms-client-kotlin-sdk:publishToMavenLocal -PPOM_VERSION_NAME=0.0.1-local.1
```

The artifact is written under:

```text
~/.m2/repository/io/github/0xsequence/oms-client-kotlin-sdk/0.0.1-local.1/
```

Expected files include:

- `oms-client-kotlin-sdk-0.0.1-local.1.aar`
- `oms-client-kotlin-sdk-0.0.1-local.1.pom`
- `oms-client-kotlin-sdk-0.0.1-local.1.module`
- `oms-client-kotlin-sdk-0.0.1-local.1-sources.jar`
- `oms-client-kotlin-sdk-0.0.1-local.1-javadoc.jar`

Signing is not required for `mavenLocal`. The module only signs publications
when `signingInMemoryKey` and `signingInMemoryKeyPassword` are provided.

### Consumer Test

Test the published artifact from a separate Android consumer project. The sample
app in this repository uses `implementation(project(":oms-client-kotlin-sdk"))`,
so it does not verify Maven artifact consumption as-is.

In the consumer project's repository configuration, add `mavenLocal()` before
the remote repositories:

```kotlin
repositories {
    mavenLocal()
    google()
    mavenCentral()
}
```

Then depend on the local artifact:

```kotlin
implementation("io.github.0xsequence:oms-client-kotlin-sdk:0.0.1-local.1")
```

Build the consumer app:

```sh
./gradlew assembleDebug
```

Remove `mavenLocal()` from the consumer project after testing so local artifacts
do not mask remote releases.

## Remote Publishing

TODO: Define the repository, credentials, signing requirements, release checks,
and rollback process for publishing snapshots or releases to a remote Maven
repository.
