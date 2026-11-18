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

// OSV-Scanner flags wire-runtime 6.4.0 (GHSA-9rm7-3qhh-h2mc), which benchmark-macro 1.5.0 asks for, and okio 2.10.0
// (GHSA-w33c-445m-f8w7), which wire 6.4.0 asks for. This test module's classpath is the only place either appears; the
// app's release classpaths have neither. Ask for the fixed patch release instead, so the old one is never fetched.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.squareup.wire" && requested.name == "wire-runtime" && requested.version == "6.4.0") {
            useVersion("6.4.5")
            because("GHSA-9rm7-3qhh-h2mc, fixed in 6.4.5")
        }
        if (requested.group == "com.squareup.okio" && requested.name == "okio" && requested.version == "2.10.0") {
            useVersion("3.4.0")
            because("GHSA-w33c-445m-f8w7, fixed in 3.4.0")
        }
    }
}
