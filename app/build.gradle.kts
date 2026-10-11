// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    // AGP 9 compiles Kotlin itself; the Kotlin version is pinned in the root build.
    alias(libs.plugins.android.application)
}

android {
    namespace = "tv.stationbreak.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "tv.stationbreak.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    flavorDimensions += "distribution"
    productFlavors {
        create("amazon") { dimension = "distribution" }
        create("github") { dimension = "distribution" }
    }

    compileOptions {
        // java.time and friends on API 25 (MODULES.md: desugaring on from day one).
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
