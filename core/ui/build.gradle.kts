// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.android-lib")
    id("stationbreak.compose")
}

android {
    namespace = "tv.stationbreak.core.ui"
}

dependencies {
    // The instrumented tests' ComponentActivity and test manifest would otherwise resolve androidx.activity,
    // lifecycle and core to old versions that have no entry in gradle/verification-metadata.xml; these pin them
    // to the app's versions (feature modules get the same pins from their lifecycle dependencies).
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.androidx.lifecycle.viewmodel.compose)
    androidTestImplementation(libs.androidx.lifecycle.runtime.compose)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}
