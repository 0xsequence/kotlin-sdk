pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradleup.nmcp.settings") version "1.6.1"
}

val isCentralPortalPublish =
    gradle.startParameter.taskNames.any {
        it.contains("publishAggregationToCentralPortal")
    }
val centralPortalUsername =
    providers.gradleProperty("centralPortalUsername").orElse("").get()
val centralPortalPassword =
    providers.gradleProperty("centralPortalPassword").orElse("").get()

if (isCentralPortalPublish && (centralPortalUsername.isBlank() || centralPortalPassword.isBlank())) {
    error(
        "Central Portal publishing requires Gradle properties " +
            "centralPortalUsername and centralPortalPassword.",
    )
}

nmcpSettings {
    centralPortal {
        username = centralPortalUsername
        password = centralPortalPassword
        publishingType = "USER_MANAGED"
        publicationName = "oms-wallet-kotlin-sdk:${providers.gradleProperty("POM_VERSION_NAME").get()}"
        validationTimeout = java.time.Duration.ofMinutes(30)
        publishingTimeout = java.time.Duration.ZERO
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kotlin-sdk"
include(":app")
include(":trails-actions")
include(":oms-wallet-kotlin-sdk")
include(":oms-wallet-kotlin-sdk-waas-generated")
include(":api-docs-generator")
project(":api-docs-generator").projectDir = file("tools/api-docs-generator")
