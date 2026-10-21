// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.android-lib")
    // Media3 ships in the app: its licenses are checked here (ADR-0003).
    id("stationbreak.licensee")
}

android {
    namespace = "tv.stationbreak.core.playback"

    // Media3 value types read the Android clock when built; the unit tests only need them to exist.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    // The player. No OkHttp here: the app hands this module a Media3 DataSource.Factory.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
