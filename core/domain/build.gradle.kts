// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.jvm-lib")
    id("stationbreak.jvm-inject")
    // The port fakes (ADR-0004): FakeMediaServer and, later, InMemorySecretStore.
    `java-test-fixtures`
}

dependencies {
    api(project(":core:model"))

    // runBlocking in the fakes' tests. The pin that matches the SDK (see the catalog).
    testImplementation(libs.kotlinx.coroutines.core)
}
