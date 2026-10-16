// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.jvm-lib")
    id("stationbreak.jvm-inject")
    // The SDK's licenses are checked from the first PR, not only once `app` depends on this module (F0.3, D5).
    id("stationbreak.licensee")
    // contractTest: the suite against real servers in Docker. `check` compiles it and never runs it (F0.3 D7).
    id("stationbreak.contract-test")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:model"))

    implementation(libs.jellyfin.sdk.core)
    implementation(libs.jellyfin.sdk.api.okhttp)
    // The pins that match the SDK (see the catalog).
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    // The probe decodes /System/Info/Public itself (see ServerProbe).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // The generated library the paging tests serve (F0.6). Test sources only (checkArchitecture).
    testImplementation(project(":core:testing"))
}

// Test only: the contract suite starts the server (and a subpath proxy) with Testcontainers.
dependencies {
    "contractTestImplementation"(libs.testcontainers)
}
