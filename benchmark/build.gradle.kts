// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

import com.android.build.api.dsl.TestExtension

// Generates the app's Baseline Profile (#416, F2.5, ADR-0008 decision 3) by driving the guide on a real device. It is
// a test module: nothing in it ships, and no shipped module depends on it. The profile lands in app/src/main only when
// `:app:generateBaselineProfile` is run on a device by hand, with the tester signed in on the target build.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
    // checkArchitecture (this module may depend on no other project module) and Spotless and detekt, as in every module.
    id("stationbreak.architecture")
    id("stationbreak.quality")
}

android {
    namespace = "tv.stationbreak.benchmark"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // The same distribution flavors as the app (build-logic AndroidAppPlugin is the source of truth), so the test picks the app variant with the same flavor.
    flavorDimensions += "distribution"
    productFlavors {
        create("amazon") { dimension = "distribution" }
        create("github") { dimension = "distribution" }
    }
}

// Fire TVs and Android TVs: the profile runs on the device the owner plugs in, not on a managed emulator.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
}
