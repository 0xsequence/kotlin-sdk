plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
    id("signing")
}

group = providers.gradleProperty("POM_GROUP_ID").orElse("io.github.0xsequence").get()
version = providers.gradleProperty("POM_VERSION_NAME").orElse("0.1.0-SNAPSHOT").get()

android {
    namespace = "com.polygon_wallet.polygon_kotlin_sdk"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.web3j.crypto) {
        exclude(group = "io.vertx", module = "vertx-core")
    }
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = providers.gradleProperty("POM_GROUP_ID").orElse(project.group.toString()).get()
            artifactId = providers.gradleProperty("POM_ARTIFACT_ID").orElse(project.name).get()
            version = providers.gradleProperty("POM_VERSION_NAME").orElse(project.version.toString()).get()

            pom {
                name.set(providers.gradleProperty("POM_NAME").orElse("Polygon Kotlin SDK"))
                description.set(
                    providers.gradleProperty("POM_DESCRIPTION")
                        .orElse("Android/Kotlin SDK module for wallet, auth, and API flows.")
                )
                url.set(
                    providers.gradleProperty("POM_URL")
                        .orElse("https://github.com/0xsequence/kotlin-sdk")
                )
                licenses {
                    license {
                        name.set(
                            providers.gradleProperty("POM_LICENSE_NAME")
                                .orElse("Apache License 2.0")
                        )
                        url.set(
                            providers.gradleProperty("POM_LICENSE_URL")
                                .orElse("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        )
                    }
                }
                developers {
                    developer {
                        id.set(providers.gradleProperty("POM_DEVELOPER_ID").orElse("0xsequence"))
                        name.set(providers.gradleProperty("POM_DEVELOPER_NAME").orElse("Sequence"))
                    }
                }
                scm {
                    url.set(
                        providers.gradleProperty("POM_SCM_URL")
                            .orElse("https://github.com/0xsequence/kotlin-sdk")
                    )
                    connection.set(
                        providers.gradleProperty("POM_SCM_CONNECTION")
                            .orElse("scm:git:https://github.com/0xsequence/kotlin-sdk.git")
                    )
                    developerConnection.set(
                        providers.gradleProperty("POM_SCM_DEV_CONNECTION")
                            .orElse("scm:git:ssh://git@github.com/0xsequence/kotlin-sdk.git")
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

    isRequired = false

    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
