plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
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

android {
    namespace = "technology.polygon.omswallet.trailsactions"
    compileSdk = 35

    defaultConfig {
        applicationId = "technology.polygon.omswallet.trailsactions"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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
}

dependencies {
    implementation(project(":oms-wallet-kotlin-sdk"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
