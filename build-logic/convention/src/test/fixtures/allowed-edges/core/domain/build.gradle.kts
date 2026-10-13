// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    `java-library`
    `java-test-fixtures`
    id("stationbreak.architecture")
}

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:scheduler"))
    testImplementation(project(":core:testing"))
}
