// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

import app.cash.licensee.UnusedAction

plugins {
    // AGP 9 compiles Kotlin itself; the Kotlin version is pinned in the root build.
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
    alias(libs.plugins.licensee)
}

android {
    namespace = "tv.stationbreak.app"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "tv.stationbreak.app"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
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

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Raises every Security-category issue to fatal (the DSL's `fatal` takes issue IDs, not categories).
        lintConfig = file("lint.xml")
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

detekt {
    buildUponDefaultConfig = true
}

tasks.named("check") {
    // detekt 2's plain `detekt` task skips rules that need type resolution (LongParameterList among them);
    // detektMain and detektTest run every rule.
    dependsOn("detektMain", "detektTest")
    // `lint` checks only the default variant (amazonDebug); lint the github flavor too.
    dependsOn("lintAmazonDebug", "lintGithubDebug")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation("junit:junit:4.13.2")

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
