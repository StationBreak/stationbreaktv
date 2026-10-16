// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.kmp-lib")
}

kotlin {
    // Test only: checks the rules-format examples against the JSON Schema. Not a runtime dependency of any module.
    sourceSets.jvmTest.dependencies {
        implementation(libs.json.schema.validator)
    }
}
