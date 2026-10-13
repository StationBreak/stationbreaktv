// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.android-lib")
    id("stationbreak.compose")
}

android {
    namespace = "tv.stationbreak.core.ui"
}

dependencies {
    testImplementation(libs.junit)
}
