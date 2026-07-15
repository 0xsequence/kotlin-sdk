plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.ktlint)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    id("maven-publish")
    id("signing")
}

ktlint {
    version.set(libs.versions.ktlint.get())
    outputToConsole.set(true)
    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
    }
}

group = providers.gradleProperty("POM_GROUP_ID").orElse("io.github.0xsequence").get()
version = providers.gradleProperty("POM_VERSION_NAME").orElse("0.2.0-SNAPSHOT").get()

val waasGeneratedSource =
    layout.projectDirectory.file(
        "../oms-wallet-kotlin-sdk/src/main/java/technology/polygon/omswallet/internal/generated/waas/WaasWalletClient.kt",
    )

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDirs("src/main/java", waasGeneratedSource.asFile.parentFile.parentFile.parentFile)
        }
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    archiveFileName.set("oms-wallet-kotlin-sdk-waas-generated.jar")
    includeEmptyDirs = false
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}

publishing {
    publications {
        register<MavenPublication>("runtime") {
            from(components["java"])
            groupId = providers.gradleProperty("POM_GROUP_ID").orElse(project.group.toString()).get()
            artifactId = "oms-wallet-kotlin-sdk-waas-generated"
            version = providers.gradleProperty("POM_VERSION_NAME").orElse(project.version.toString()).get()

            pom {
                name.set("OMS Wallet Kotlin SDK WaaS Runtime")
                description.set("Internal generated WaaS runtime for the OMS Wallet Kotlin SDK.")
                url.set(providers.gradleProperty("POM_URL").orElse("https://github.com/0xsequence/kotlin-sdk"))
                licenses {
                    license {
                        name.set(providers.gradleProperty("POM_LICENSE_NAME").orElse("Apache License 2.0"))
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
                        name.set(providers.gradleProperty("POM_DEVELOPER_NAME").orElse("OMS Wallet"))
                    }
                }
                scm {
                    url.set(providers.gradleProperty("POM_SCM_URL").orElse("https://github.com/0xsequence/kotlin-sdk"))
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
