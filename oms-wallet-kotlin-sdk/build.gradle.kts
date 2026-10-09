import kotlinx.validation.api.dump
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses
import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipFile

buildscript {
    dependencies {
        // Kotlin-aware public API dump (drops internal, private and synthetic declarations).
        classpath(libs.binary.compatibility.validator)
        classpath(libs.kotlin.metadata.jvm)
    }
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.serialization)
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

group = providers.gradleProperty("POM_GROUP_ID").get()
version = providers.gradleProperty("POM_VERSION_NAME").get()

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

dependencies {
    implementation(libs.bouncy.castle)
    implementation(libs.cbor)
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

fun javapExecutable(): File =
    File(System.getProperty("java.home"))
        .resolve("bin")
        .resolve(if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "javap.exe" else "javap")

/** Runs `javap -public` (or the given [option]) for [className] and returns its output. */
fun runJavap(
    classpath: String,
    className: String,
    option: String = "-public",
): String {
    val process =
        ProcessBuilder(
            javapExecutable().absolutePath,
            "-classpath",
            classpath,
            option,
            className,
        ).start()
    val output = process.inputStream.bufferedReader().readText()
    val errors = process.errorStream.bufferedReader().readText()
    if (process.waitFor() != 0) {
        throw GradleException("javap failed for $className: ${errors.trim()}")
    }
    return output
}

/**
 * Dumps the public Kotlin/JVM API of [classesJar] with the binary-compatibility-validator library.
 * Unlike `javap -public`, it reads Kotlin metadata, so `internal` declarations, private classes'
 * companions and serializers, and synthetic accessors are excluded.
 */
fun generatePublicApiDump(classesJar: File): String {
    val dump =
        JarFile(classesJar).use { jar ->
            StringBuilder().also { jar.loadApiFromJvmClasses().filterOutNonPublic().dump(it) }.trimEnd().toString() + "\n"
        }
    verifyPublicApiDump(dump)
    return dump
}

/**
 * Guards the dump against regressing to Java-visible-but-internal symbols: it must contain no
 * name-mangled internal members or synthetic accessors, and its top-level types and top-level
 * functions must match the source-based API reference (`docs/api.md`, kept current by
 * `checkApiDocs`).
 */
fun verifyPublicApiDump(dump: String) {
    val leaked =
        dump.lineSequence().filter { "\$oms_wallet_kotlin_sdk" in it || "access\$" in it }.toList()
    if (leaked.isNotEmpty()) {
        throw GradleException("Public API dump contains internal or synthetic members:\n${leaked.joinToString("\n")}")
    }

    val classHeader = Regex("""^\S.*\b(?:class|interface) (technology/polygon/omswallet/\S+) """)
    val topLevelFunction = Regex("""^\tpublic static final fun (\w+) """)
    val dumpNames = sortedSetOf<String>()
    var currentFacade = false
    dump.lineSequence().forEach { line ->
        val header = classHeader.find(line)
        if (header != null) {
            val simpleName = header.groupValues[1].substringAfterLast('/')
            currentFacade = simpleName.endsWith("Kt") && '$' !in simpleName
            if ('$' !in simpleName && !currentFacade) dumpNames += simpleName
        } else if (currentFacade) {
            topLevelFunction.find(line)?.let { dumpNames += it.groupValues[1] }
        }
    }

    val apiDocs =
        rootProject.layout.projectDirectory
            .file("docs/api.md")
            .asFile
    val documentedNames =
        Regex("""^### `([^`.]+)`""", RegexOption.MULTILINE)
            .findAll(apiDocs.readText())
            .map { it.groupValues[1] }
            .toSortedSet()
    if (dumpNames != documentedNames) {
        throw GradleException(
            "Public API dump and docs/api.md disagree on top-level declarations.\n" +
                "Only in the binary dump: ${dumpNames - documentedNames}\n" +
                "Only in docs/api.md: ${documentedNames - dumpNames}",
        )
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
        if (!javapExecutable().isFile) {
            throw GradleException("Unable to find javap at ${javapExecutable().absolutePath}")
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
            val output =
                runJavap(
                    listOf(
                        classesDir.absolutePath,
                        waasGeneratedJar.get().asFile.absolutePath,
                    ).joinToString(File.pathSeparator),
                    className,
                )

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

        listOf(
            "technology.polygon.omswallet.Network",
            "technology.polygon.omswallet.wallet.WalletClient",
            "technology.polygon.omswallet.indexer.IndexerClient",
        ).forEach { className ->
            val output = runJavap(classesJar.absolutePath, className)
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
    }
}

tasks.register("dumpPublicApi") {
    group = "documentation"
    description = "Writes the public Kotlin/JVM API shipped in the release AAR."
    dependsOn("syncReleaseLibJars")
    inputs.file(packagedReleaseClassesJar)
    inputs.file(rootProject.layout.projectDirectory.file("docs/api.md"))
    outputs.file(publicApiBaseline)

    doLast {
        val baseline = publicApiBaseline.asFile
        baseline.parentFile.mkdirs()
        baseline.writeText(generatePublicApiDump(packagedReleaseClassesJar.get().asFile))
    }
}

tasks.register("checkPublicApiBaseline") {
    group = "verification"
    description = "Fails when the packaged public Kotlin/JVM API differs from the committed baseline."
    dependsOn("syncReleaseLibJars")
    mustRunAfter("dumpPublicApi")
    inputs.file(packagedReleaseClassesJar)
    inputs.file(rootProject.layout.projectDirectory.file("docs/api.md"))
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
            groupId = project.group.toString()
            artifactId = providers.gradleProperty("POM_ARTIFACT_ID").get()
            version = project.version.toString()

            pom {
                name.set(providers.gradleProperty("POM_NAME"))
                description.set(providers.gradleProperty("POM_DESCRIPTION"))
                url.set(providers.gradleProperty("POM_URL"))
                licenses {
                    license {
                        name.set(providers.gradleProperty("POM_LICENSE_NAME"))
                        url.set(providers.gradleProperty("POM_LICENSE_URL"))
                    }
                }
                developers {
                    developer {
                        id.set(providers.gradleProperty("POM_DEVELOPER_ID"))
                        name.set(providers.gradleProperty("POM_DEVELOPER_NAME"))
                    }
                }
                scm {
                    url.set(providers.gradleProperty("POM_SCM_URL"))
                    connection.set(providers.gradleProperty("POM_SCM_CONNECTION"))
                    developerConnection.set(providers.gradleProperty("POM_SCM_DEV_CONNECTION"))
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
