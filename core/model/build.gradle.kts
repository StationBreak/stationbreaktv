// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.kmp-lib")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets.commonMain.dependencies {
        // ServerAddress is a serialisable value (F0.3, D11). The format library is common code.
        implementation(libs.kotlinx.serialization.json)
    }
}
