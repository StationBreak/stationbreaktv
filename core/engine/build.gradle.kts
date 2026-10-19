// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.kmp-lib")
}

kotlin {
    sourceSets.commonMain.dependencies {
        // The evaluator reads the channel rules (sources, exclusions, the rating ceiling) and the rating check.
        api(project(":core:rules"))
        // Its input snapshot and its output items are core:model types.
        api(project(":core:model"))
    }
}
