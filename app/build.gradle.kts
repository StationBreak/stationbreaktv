// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

import app.cash.licensee.UnusedAction

plugins {
    // SDK levels, flavors, desugaring, toolchain, Lint, Spotless and detekt: build-logic's AndroidAppPlugin.
    id("stationbreak.android-app")
    id("stationbreak.compose")
    alias(libs.plugins.licensee)
}

android {
    namespace = "tv.stationbreak.app"

    defaultConfig {
        applicationId = "tv.stationbreak.app"
        versionCode = 1
        versionName = "0.0.1"
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

// Every runtime dependency must carry a GPL-3.0-compatible license (ADR-0003). A license Licensee can't identify
// fails the build. Extend this list only when a real dependency needs it; per-dependency overrides go here too,
// each with a `// reason:` comment.
licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allow("LGPL-2.1-or-later")
    allow("LGPL-3.0-only")
    allow("LGPL-3.0-or-later")
    // Entries are listed ahead of need, so one that no dependency uses yet isn't an error.
    unusedAction(UnusedAction.IGNORE)
}

dependencies {
    // ADR-0004: app consumes the KMP shared core through Gradle's jvm variant.
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.activity.compose)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // The empty activity that createComposeRule hosts content in; debug builds only.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
