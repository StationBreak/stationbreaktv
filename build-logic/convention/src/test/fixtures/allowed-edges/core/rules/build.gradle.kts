// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    `java-library`
    id("stationbreak.architecture")
}

dependencies {
    implementation(project(":core:model"))
}
