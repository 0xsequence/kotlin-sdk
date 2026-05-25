import java.io.File

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
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
version = providers.gradleProperty("POM_VERSION_NAME").orElse("0.1.0-SNAPSHOT").get()

evaluationDependsOn(":oms-client-kotlin-sdk-waas-generated")
val waasGeneratedProject = project(":oms-client-kotlin-sdk-waas-generated")
val waasGeneratedJar =
    waasGeneratedProject.tasks
        .named<org.gradle.jvm.tasks.Jar>("jar")
        .flatMap { it.archiveFile }
val waasGeneratedClassesJar =
    files(waasGeneratedJar)
        .builtBy(waasGeneratedProject.tasks.named("jar"))

android {
    namespace = "com.omsclient.kotlin_sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
        delete(destinationDirectory.dir("com/omsclient/kotlin_sdk/generated"))
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
    exclude("com/omsclient/kotlin_sdk/internal/generated/**")
}

val releaseKotlinClasses =
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes")

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

        val generatedPackage = "com.omsclient.kotlin_sdk.internal.generated.waas"
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

tasks.named("check") {
    dependsOn("checkPublicApiDoesNotExposeGeneratedWaas")
}

dependencies {
    implementation(waasGeneratedClassesJar)
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
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
                name.set(providers.gradleProperty("POM_NAME").orElse("OMS Client Kotlin SDK"))
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
                                .orElse("OMS Client"),
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
