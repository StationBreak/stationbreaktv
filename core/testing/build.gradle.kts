// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.kmp-lib")
}

kotlin {
    // The fixtures are built from the domain's model types (MODULES.md: core:testing -> core:model).
    sourceSets.commonMain.dependencies {
        api(project(":core:model"))
    }
}
