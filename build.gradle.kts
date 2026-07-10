import groovy.json.JsonSlurper
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ktlint) apply false
}

fun ZipFile.readRequiredEntry(name: String): ByteArray {
    val entry = getEntry(name) ?: throw GradleException("Release archive is missing $name")
    return getInputStream(entry).use { it.readBytes() }
}

fun ZipFile.verifyChecksum(
    artifact: String,
    bytes: ByteArray,
    suffix: String,
    algorithm: String,
) {
    val expected = readRequiredEntry("$artifact.$suffix").toString(StandardCharsets.UTF_8).trim().lowercase()
    val actual =
        MessageDigest
            .getInstance(algorithm)
            .digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    if (expected != actual) {
        throw GradleException("$artifact has an invalid $algorithm checksum.")
    }
}

fun zipEntryNames(bytes: ByteArray): Set<String> =
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        buildSet {
            while (true) {
                val entry = zip.nextEntry ?: break
                add(entry.name)
            }
        }
    }

fun Element.childText(localName: String): String? =
    getElementsByTagNameNS("*", localName).item(0)?.textContent?.trim()

val releaseArchive = layout.buildDirectory.file("nmcp/zip/aggregation.zip")

val checkReleasePublicationGraph =
    tasks.register("checkReleasePublicationGraph") {
        group = "verification"
        description = "Checks the complete Maven Central publication archive and dependency graph."
        dependsOn("nmcpZipAggregation")
        inputs.file(releaseArchive)
        inputs.files("README.md", "docs/api.md", "gradle.properties")

        doLast {
            val groupId = providers.gradleProperty("POM_GROUP_ID").get()
            val mainArtifactId = providers.gradleProperty("POM_ARTIFACT_ID").get()
            val runtimeArtifactId = "oms-wallet-kotlin-sdk-waas-generated"
            val version = providers.gradleProperty("POM_VERSION_NAME").get()
            val coordinate = "$groupId:$mainArtifactId:$version"
            val installSnippet = "implementation(\"$coordinate\")"

            if (!Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""").matches(version)) {
                throw GradleException("POM_VERSION_NAME must be an exact SemVer version; found $version")
            }
            listOf(file("README.md"), file("docs/api.md")).forEach { documentation ->
                if (installSnippet !in documentation.readText()) {
                    throw GradleException(
                        "${documentation.path} must contain the release coordinate $installSnippet",
                    )
                }
            }

            val groupPath = groupId.replace('.', '/')
            val mainBase = "$groupPath/$mainArtifactId/$version/$mainArtifactId-$version"
            val runtimeBase = "$groupPath/$runtimeArtifactId/$version/$runtimeArtifactId-$version"
            val requiredArtifacts =
                listOf(
                    "$mainBase.aar",
                    "$mainBase.pom",
                    "$mainBase.module",
                    "$mainBase-sources.jar",
                    "$mainBase-javadoc.jar",
                    "$runtimeBase.jar",
                    "$runtimeBase.pom",
                    "$runtimeBase.module",
                    "$runtimeBase-sources.jar",
                    "$runtimeBase-javadoc.jar",
                )

            val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
            val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull
            if (signingKey.isNullOrBlank() != signingPassword.isNullOrBlank()) {
                throw GradleException(
                    "Set both signingInMemoryKey and signingInMemoryKeyPassword, or set neither.",
                )
            }
            val signingEnabled = !signingKey.isNullOrBlank()

            ZipFile(releaseArchive.get().asFile).use { archive ->
                requiredArtifacts.forEach { artifact ->
                    val artifactBytes = archive.readRequiredEntry(artifact)
                    archive.verifyChecksum(artifact, artifactBytes, "md5", "MD5")
                    archive.verifyChecksum(artifact, artifactBytes, "sha1", "SHA-1")
                    if (signingEnabled) {
                        val signature = "$artifact.asc"
                        val signatureBytes = archive.readRequiredEntry(signature)
                        archive.verifyChecksum(signature, signatureBytes, "md5", "MD5")
                        archive.verifyChecksum(signature, signatureBytes, "sha1", "SHA-1")
                    }
                }

                val documentBuilderFactory =
                    DocumentBuilderFactory.newInstance().apply {
                        isNamespaceAware = true
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    }
                val mainPom =
                    documentBuilderFactory
                        .newDocumentBuilder()
                        .parse(ByteArrayInputStream(archive.readRequiredEntry("$mainBase.pom")))
                val dependencies = mainPom.getElementsByTagNameNS("*", "dependency")
                val hasRuntimeDependency =
                    (0 until dependencies.length).any { index ->
                        val dependency = dependencies.item(index) as Element
                        dependency.childText("groupId") == groupId &&
                            dependency.childText("artifactId") == runtimeArtifactId &&
                            dependency.childText("version") == version &&
                            dependency.childText("scope") == "runtime"
                    }
                if (!hasRuntimeDependency) {
                    throw GradleException(
                        "$mainArtifactId POM must depend on $runtimeArtifactId:$version at runtime.",
                    )
                }

                val moduleMetadata =
                    JsonSlurper().parseText(
                        archive
                            .readRequiredEntry("$mainBase.module")
                            .toString(StandardCharsets.UTF_8),
                    ) as Map<*, *>
                val variants = moduleMetadata["variants"] as? List<*> ?: emptyList<Any>()
                val hasRuntimeModuleDependency =
                    variants
                        .filterIsInstance<Map<*, *>>()
                        .flatMap { (it["dependencies"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList() }
                        .any { dependency ->
                            val dependencyVersion = dependency["version"] as? Map<*, *>
                            dependency["group"] == groupId &&
                                dependency["module"] == runtimeArtifactId &&
                                dependencyVersion?.get("requires") == version
                        }
                if (!hasRuntimeModuleDependency) {
                    throw GradleException(
                        "$mainArtifactId Gradle metadata must depend on $runtimeArtifactId:$version.",
                    )
                }

                val generatedPackagePath = "technology/polygon/omswallet/internal/generated/waas/"
                val runtimeEntries = zipEntryNames(archive.readRequiredEntry("$runtimeBase.jar"))
                if (runtimeEntries.none { it.startsWith(generatedPackagePath) && it.endsWith(".class") }) {
                    throw GradleException("$runtimeArtifactId does not contain generated WaaS classes.")
                }

                listOf("$mainBase-sources.jar", "$mainBase-javadoc.jar").forEach { artifact ->
                    val leakedEntries =
                        zipEntryNames(archive.readRequiredEntry(artifact)).filter {
                            generatedPackagePath in it || "WaasWallet" in it
                        }
                    if (leakedEntries.isNotEmpty()) {
                        throw GradleException(
                            "$artifact exposes generated WaaS files: ${leakedEntries.joinToString()}",
                        )
                    }
                }
            }

            logger.lifecycle(
                "Verified release publication $coordinate at ${releaseArchive.get().asFile}",
            )
        }
    }

tasks.register("verifyReleasePublication") {
    group = "verification"
    description = "Runs every check required before publishing the SDK to Maven Central."
    dependsOn(
        ":app:ktlintCheck",
        ":app:lintDebug",
        ":app:assembleDebug",
        ":trails-actions:ktlintCheck",
        ":trails-actions:lintDebug",
        ":trails-actions:assembleDebug",
        ":oms-wallet-kotlin-sdk:ktlintCheck",
        ":oms-wallet-kotlin-sdk:testDebugUnitTest",
        ":oms-wallet-kotlin-sdk:lintDebug",
        ":oms-wallet-kotlin-sdk:checkReleaseArtifactBoundary",
        ":oms-wallet-kotlin-sdk:checkPublicApiBaseline",
        ":oms-wallet-kotlin-sdk-waas-generated:ktlintCheck",
        checkReleasePublicationGraph,
    )
}
