plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.ktlint)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
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
version = providers.gradleProperty("POM_VERSION_NAME").orElse("0.1.0-SNAPSHOT").get()

val waasGeneratedSource =
    layout.projectDirectory.file(
        "../oms-client-kotlin-sdk/src/main/java/com/omsclient/kotlin_sdk/internal/generated/waas/WaasWalletClient.kt",
    )

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDirs("src/main/java", waasGeneratedSource.asFile.parentFile.parentFile.parentFile)
        }
    }
}

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    archiveFileName.set("oms-client-kotlin-sdk-waas-generated.jar")
    includeEmptyDirs = false
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
