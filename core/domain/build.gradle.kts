// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.jvm-lib")
    id("stationbreak.jvm-inject")
    // The port fakes (ADR-0004): FakeMediaServer and, later, InMemorySecretStore.
    `java-test-fixtures`
    // SessionRepository's private blob format (F0.3 D11).
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    // The port returns a Flow and its calls suspend. The pin that matches the SDK (see the catalog).
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // The fakes in testFixtures suspend and use Deferred.
    testFixturesApi(libs.kotlinx.coroutines.core)
}
