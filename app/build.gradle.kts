// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    // SDK levels, flavors, desugaring, toolchain, Lint, Spotless and detekt: build-logic's AndroidAppPlugin.
    id("stationbreak.android-app")
    id("stationbreak.compose")
    id("stationbreak.licensee")
    // The only module that applies Hilt: `[KMP]` and `[JVM]` modules keep to javax.inject (MODULES.md).
    id("stationbreak.hilt")
}

android {
    namespace = "tv.stationbreak.app"

    defaultConfig {
        applicationId = "tv.stationbreak.app"
        versionCode = 1
        versionName = "0.0.1"
        // Hilt's test application replaces ours in instrumented tests.
        testInstrumentationRunner = "tv.stationbreak.app.HiltTestRunner"
    }

    signingConfigs {
        // Public, throwaway and debug-only (ADR-0011): committed so every CI debug build installs over the last.
        // It must never sign a release.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // No signing config: release signing comes only from protected secrets (S4.1.1).
            isDebuggable = false
        }
    }
}

dependencies {
    // ADR-0004: app consumes the KMP shared core through Gradle's jvm variant.
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    // The adapter behind the MediaServer port, bound in di/MediaServerModule. Nothing else imports it.
    implementation(project(":data:jellyfin"))
    // The base OkHttpClient is built here (ADR-0005); data:jellyfin keeps OkHttp to itself otherwise.
    implementation(libs.okhttp)
    implementation(project(":core:ui"))
    implementation(libs.androidx.activity.compose)
    // The Jellyfin SDK logs through SLF4J and without a binding its first request crashes on Android (ADR-0006).
    // A no-op until S3.6.1 builds the redacting logger.
    runtimeOnly(libs.slf4j.nop)

    // The API 25 plaintext-marker test (F0.3 task 1.16) runs data:jellyfin's classifier on the emulator.
    androidTestImplementation(project(":data:jellyfin"))
    // The test makes its own https call; data:jellyfin keeps OkHttp to itself.
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(project(":core:domain"))
    // The SDK-binding test (F0.3 task 3.13, ADR-0006) makes one request through the SDK's own client.
    androidTestImplementation(libs.jellyfin.sdk.core)
    androidTestImplementation(libs.jellyfin.sdk.api.okhttp)
    // The adapter smoke test (F0.3 task 2.13): the adapter's own types, and runBlocking to call it.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
