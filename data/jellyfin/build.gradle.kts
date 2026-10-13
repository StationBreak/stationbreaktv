// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.jvm-lib")
}

dependencies {
    testImplementation(libs.gson.canary)
}
