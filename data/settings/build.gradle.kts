// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.android-lib")
}

android {
    namespace = "tv.stationbreak.data.settings"
}

dependencies {
    // The SecretStore port this module implements; core:domain exposes the coroutines it suspends with as api.
    implementation(project(":core:domain"))
    implementation(project(":core:model"))

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
