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
    // The caption cues (androidx.media3.common.text.Cue) are in this module's API: VideoPlayer.cues.
    api(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.kotlinx.coroutines.core)
    constraints {
        // Media3 brings Guava into the APK; 33.3.1-android has GHSA-xxph-c9ww-hj94 (Java deserialization, which the
        // app never does on untrusted data). Raised to the first fixed release (#539).
        implementation(libs.guava.android) {
            because("GHSA-xxph-c9ww-hj94 is fixed in 33.7.2 (#539)")
        }
    }

    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
