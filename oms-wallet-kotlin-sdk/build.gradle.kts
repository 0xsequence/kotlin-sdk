import java.io.File
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
    id("maven-publish")
    id("signing")
}

ktlint {
    version.set(libs.versions.ktlint.get())
    android.set(true)
    outputToConsole.set(true)
    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
    }
}

group = providers.gradleProperty("POM_GROUP_ID").orElse("io.github.0xsequence").get()
version = providers.gradleProperty("POM_VERSION_NAME").orElse("0.2.0-SNAPSHOT").get()

evaluationDependsOn(":oms-wallet-kotlin-sdk-waas-generated")
val waasGeneratedProject = project(":oms-wallet-kotlin-sdk-waas-generated")
val waasGeneratedJar =
    waasGeneratedProject.tasks
        .named<org.gradle.jvm.tasks.Jar>("jar")
        .flatMap { it.archiveFile }

android {
    namespace = "technology.polygon.omswallet"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/generated/**")
    doLast {
        delete(destinationDirectory.dir("technology/polygon/omswallet/generated"))
    }
}

tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    includeEmptyDirs = false
}

tasks.matching { it.name == "sourceReleaseJar" }.configureEach {
    this as org.gradle.jvm.tasks.Jar
    exclude("**/generated/**")
}

tasks.matching { it.name == "javaDocReleaseJar" }.configureEach {
    this as org.gradle.jvm.tasks.Jar
    exclude("technology/polygon/omswallet/internal/generated/**")
}

val releaseKotlinClasses =
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes")
val packagedReleaseClassesJar =
    layout.buildDirectory.file("intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar")
val publicApiBaseline = layout.projectDirectory.file("api/public-api.txt")

fun generatePublicApiDump(classesJar: File): String {
    val javapExecutable =
        File(System.getProperty("java.home"))
            .resolve("bin")
            .resolve(if (System.getProperty("os.name").startsWith("Windows", true)) "javap.exe" else "javap")
    val classNames =
        ZipFile(classesJar).use { zip ->
            zip
                .entries()
                .asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") && !it.endsWith("module-info.class") }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .sorted()
                .toList()
        }

    return buildString {
        classNames.forEach { className ->
            val process =
                ProcessBuilder(
                    javapExecutable.absolutePath,
                    "-classpath",
                    classesJar.absolutePath,
                    "-public",
                    className,
                ).start()
            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                throw GradleException("javap failed for $className: ${errors.trim()}")
            }
            val isPublic =
                output.lineSequence().any { line ->
                    val declaration = line.trim()
                    declaration.startsWith("public class ") ||
                        declaration.startsWith("public final class ") ||
                        declaration.startsWith("public abstract class ") ||
                        declaration.startsWith("public interface ") ||
                        declaration.startsWith("public enum ")
                }
            if (isPublic) {
                appendLine(output.trim())
                appendLine()
            }
        }
    }
}

tasks.register("checkPublicApiDoesNotExposeGeneratedWaas") {
    group = "verification"
    description = "Fails if public release bytecode signatures expose generated WaaS classes."
    dependsOn("compileReleaseKotlin")
    inputs.dir(releaseKotlinClasses)
    inputs.file(waasGeneratedJar)

    doLast {
        val classesDir = releaseKotlinClasses.get().asFile
        val javapExecutable =
            File(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(
                    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                        "javap.exe"
                    } else {
                        "javap"
                    },
                )
        if (!javapExecutable.isFile) {
            throw GradleException("Unable to find javap at ${javapExecutable.absolutePath}")
        }

        val generatedPackage = "technology.polygon.omswallet.internal.generated.waas"
        val classNames =
            classesDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map {
                    it
                        .relativeTo(classesDir)
                        .invariantSeparatorsPath
                        .removeSuffix(".class")
                        .replace('/', '.')
                }.filterNot { it.startsWith("$generatedPackage.") }
                .toList()
        val leaks = mutableListOf<String>()

        classNames.forEach { className ->
            val process =
                ProcessBuilder(
                    javapExecutable.absolutePath,
                    "-classpath",
                    listOf(
                        classesDir.absolutePath,
                        waasGeneratedJar.get().asFile.absolutePath,
                    ).joinToString(File.pathSeparator),
                    "-public",
                    className,
                ).start()
            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            val exitValue = process.waitFor()
            if (exitValue != 0) {
                throw GradleException("javap failed for $className: ${errors.trim()}")
            }

            val publicClassDeclaration =
                output
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull {
                        " class " in it ||
                            " interface " in it ||
                            " enum " in it
                    }?.startsWith("public ") == true
            if (!publicClassDeclaration) {
                return@forEach
            }

            val leakedLines =
                output
                    .lineSequence()
                    .filter { generatedPackage in it }
                    .map { it.trim() }
                    .toList()
            if (leakedLines.isNotEmpty()) {
                leaks += "$className\n  ${leakedLines.joinToString("\n  ")}"
            }
        }

        if (leaks.isNotEmpty()) {
            throw GradleException(
                "Public API exposes generated WaaS classes:\n" + leaks.joinToString("\n\n"),
            )
        }
    }
}

val releaseAar = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")

tasks.register("checkReleaseArtifactBoundary") {
    group = "verification"
    description =
        "Checks that the public AAR excludes generated WaaS bytecode and Java-callable implementation details."
    dependsOn("assembleRelease", "checkPublicApiDoesNotExposeGeneratedWaas")
    inputs.file(releaseAar)

    doLast {
        val aar = releaseAar.get().asFile
        val embeddedAarEntries =
            ZipFile(aar).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .filter {
                        it.startsWith("libs/") ||
                            it.startsWith("technology/polygon/omswallet/internal/generated/waas/")
                    }.toList()
            }
        if (embeddedAarEntries.isNotEmpty()) {
            throw GradleException(
                "Release AAR embeds generated WaaS implementation classes: " +
                    embeddedAarEntries.joinToString(),
            )
        }

        val classesJar = zipTree(aar).matching { include("classes.jar") }.singleFile
        val mergedGeneratedClasses =
            ZipFile(classesJar).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .filter { it.startsWith("technology/polygon/omswallet/internal/generated/waas/") }
                    .toList()
            }
        if (mergedGeneratedClasses.isNotEmpty()) {
            throw GradleException(
                "Release classes.jar contains generated WaaS implementation classes: " +
                    mergedGeneratedClasses.joinToString(),
            )
        }

        val javapExecutable =
            File(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(if (System.getProperty("os.name").startsWith("Windows", true)) "javap.exe" else "javap")
        listOf(
            "technology.polygon.omswallet.Network",
            "technology.polygon.omswallet.wallet.WalletClient",
            "technology.polygon.omswallet.indexer.IndexerClient",
        ).forEach { className ->
            val process =
                ProcessBuilder(
                    javapExecutable.absolutePath,
                    "-classpath",
                    classesJar.absolutePath,
                    "-public",
                    className,
                ).start()
            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                throw GradleException("javap failed for $className: ${errors.trim()}")
            }
            val simpleName = className.substringAfterLast('.')
            val publicConstructors =
                output.lineSequence().filter { line ->
                    val signature = line.trim()
                    (
                        signature.startsWith("public $className(") ||
                            signature.startsWith("public $simpleName(")
                    ) &&
                        "kotlin.jvm.internal.DefaultConstructorMarker" !in signature
                }
            if (publicConstructors.any()) {
                throw GradleException("$className exposes a public implementation constructor")
            }
        }

        listOf(
            "technology.polygon.omswallet.wallet.OidcRedirectAuthStore",
            "technology.polygon.omswallet.storage.AndroidOidcRedirectAuthStore",
        ).forEach { className ->
            val process =
                ProcessBuilder(
                    javapExecutable.absolutePath,
                    "-classpath",
                    classesJar.absolutePath,
                    "-v",
                    className,
                ).start()
            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                throw GradleException("javap failed for $className: ${errors.trim()}")
            }
            val getterSection =
                output
                    .lineSequence()
                    .dropWhile { "getSynchronizationKey();" !in it }
                    .take(3)
                    .joinToString("\n")
            if ("getSynchronizationKey();" !in getterSection || "ACC_SYNTHETIC" !in getterSection) {
                throw GradleException("$className exposes synchronizationKey to Java source callers")
            }
        }
    }
}

tasks.register("dumpPublicApi") {
    group = "documentation"
    description = "Writes the Java-visible API shipped in the release AAR."
    dependsOn("syncReleaseLibJars")
    inputs.file(packagedReleaseClassesJar)
    outputs.file(publicApiBaseline)

    doLast {
        val baseline = publicApiBaseline.asFile
        baseline.parentFile.mkdirs()
        baseline.writeText(generatePublicApiDump(packagedReleaseClassesJar.get().asFile))
    }
}

tasks.register("checkPublicApiBaseline") {
    group = "verification"
    description = "Fails when the packaged Java-visible API differs from the committed baseline."
    dependsOn("syncReleaseLibJars")
    mustRunAfter("dumpPublicApi")
    inputs.file(packagedReleaseClassesJar)
    inputs.file(publicApiBaseline)

    doLast {
        val baseline = publicApiBaseline.asFile
        if (!baseline.isFile) {
            throw GradleException("Missing public API baseline. Run :oms-wallet-kotlin-sdk:dumpPublicApi.")
        }
        val actual = generatePublicApiDump(packagedReleaseClassesJar.get().asFile)
        if (baseline.readText() != actual) {
            val actualFile =
                layout.buildDirectory
                    .file("reports/public-api/actual.txt")
                    .get()
                    .asFile
            actualFile.parentFile.mkdirs()
            actualFile.writeText(actual)
            val process =
                ProcessBuilder(
                    "git",
                    "diff",
                    "--no-ext-diff",
                    "--no-index",
                    "--no-color",
                    "--unified=3",
                    baseline.absolutePath,
                    actualFile.absolutePath,
                ).start()
            val diff = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            if (process.waitFor() !in setOf(0, 1)) {
                throw GradleException("Unable to generate public API diff: ${errors.trim()}")
            }
            throw GradleException(
                "Packaged public API changed:\n$diff\nReview the change, then run " +
                    ":oms-wallet-kotlin-sdk:dumpPublicApi to accept it.",
            )
        }
    }
}

tasks.named("check") {
    dependsOn("checkReleaseArtifactBoundary", "checkPublicApiBaseline")
}

dependencies {
    implementation(project(":oms-wallet-kotlin-sdk-waas-generated"))
    implementation(libs.androidx.core.ktx)
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId =
                providers
                    .gradleProperty("POM_GROUP_ID")
                    .orElse(project.group.toString())
                    .get()
            artifactId =
                providers
                    .gradleProperty("POM_ARTIFACT_ID")
                    .orElse(project.name)
                    .get()
            version =
                providers
                    .gradleProperty("POM_VERSION_NAME")
                    .orElse(project.version.toString())
                    .get()

            pom {
                name.set(providers.gradleProperty("POM_NAME").orElse("OMS Wallet Kotlin SDK"))
                description.set(
                    providers
                        .gradleProperty("POM_DESCRIPTION")
                        .orElse("Android/Kotlin SDK module for wallet, auth, and API flows."),
                )
                url.set(
                    providers
                        .gradleProperty("POM_URL")
                        .orElse("https://github.com/0xsequence/kotlin-sdk"),
                )
                licenses {
                    license {
                        name.set(
                            providers
                                .gradleProperty("POM_LICENSE_NAME")
                                .orElse("Apache License 2.0"),
                        )
                        url.set(
                            providers
                                .gradleProperty("POM_LICENSE_URL")
                                .orElse("https://www.apache.org/licenses/LICENSE-2.0.txt"),
                        )
                    }
                }
                developers {
                    developer {
                        id.set(providers.gradleProperty("POM_DEVELOPER_ID").orElse("0xsequence"))
                        name.set(
                            providers
                                .gradleProperty("POM_DEVELOPER_NAME")
                                .orElse("OMS Wallet"),
                        )
                    }
                }
                scm {
                    url.set(
                        providers
                            .gradleProperty("POM_SCM_URL")
                            .orElse("https://github.com/0xsequence/kotlin-sdk"),
                    )
                    connection.set(
                        providers
                            .gradleProperty("POM_SCM_CONNECTION")
                            .orElse("scm:git:https://github.com/0xsequence/kotlin-sdk.git"),
                    )
                    developerConnection.set(
                        providers
                            .gradleProperty("POM_SCM_DEV_CONNECTION")
                            .orElse("scm:git:ssh://git@github.com/0xsequence/kotlin-sdk.git"),
                    )
                }
            }
        }
    }
}

afterEvaluate {
    publishing {
        publications.named<MavenPublication>("release") {
            from(components["release"])
        }
    }
}

signing {
    val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
    val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    val isCentralPortalPublish =
        gradle.startParameter.taskNames.any {
            it.contains("publishAggregationToCentralPortal")
        }

    isRequired = false

    if (isCentralPortalPublish && (signingKey.isNullOrBlank() || signingPassword.isNullOrBlank())) {
        throw GradleException(
            "Central Portal publishing requires Gradle properties " +
                "signingInMemoryKey and signingInMemoryKeyPassword.",
        )
    }

    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
