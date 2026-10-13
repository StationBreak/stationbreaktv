// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.jvm-lib")
}

dependencies {
    // Canary (never merged): a [JVM] module may not depend on an Android module.
    implementation(project(":core:ui"))
}
