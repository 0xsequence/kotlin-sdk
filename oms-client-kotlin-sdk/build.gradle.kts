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

val waasGeneratedProject = project(":oms-client-kotlin-sdk-waas-generated")
val embeddedWaasGeneratedJar =
    layout.buildDirectory.file("embedded-jars/oms-client-kotlin-sdk-waas-generated.jar")
val syncEmbeddedWaasGeneratedJar =
    tasks.register<Sync>("syncEmbeddedWaasGeneratedJar") {
        dependsOn(
            waasGeneratedProject.tasks.matching { it.name == "bundleLibRuntimeToJarRelease" },
        )
        from(
            waasGeneratedProject.layout.buildDirectory.file(
                "intermediates/runtime_library_classes_jar/release/bundleLibRuntimeToJarRelease/classes.jar",
            ),
        ) {
            rename { "oms-client-kotlin-sdk-waas-generated.jar" }
        }
        into(embeddedWaasGeneratedJar.map { it.asFile.parentFile })
    }
val waasGeneratedClassesJar =
    files(embeddedWaasGeneratedJar)
        .builtBy(syncEmbeddedWaasGeneratedJar)

android {
    namespace = "com.omsclient.kotlin_sdk"
    compileSdk {
        version =
            release(
                36,
            ) {
                minorApiLevel = 1
            }
    }

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
    exclude("com/omsclient/kotlin_sdk/generated/**")
}

dependencies {
    implementation(waasGeneratedClassesJar)
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.web3j.crypto) {
        exclude(group = "io.vertx", module = "vertx-core")
    }
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
