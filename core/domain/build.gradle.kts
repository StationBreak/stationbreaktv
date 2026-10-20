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
    // The channel lineup (spec 12): the catalog, the planner and the pools come from core:engine, the epoch arithmetic
    // from core:scheduler. Neither is api: nothing outside core:domain sees an engine lineup type.
    implementation(project(":core:engine"))
    implementation(project(":core:scheduler"))

    // The fakes in testFixtures suspend and use Deferred.
    testFixturesApi(libs.kotlinx.coroutines.core)

    // Test only: a 15,000-item library for the schedule source's size test (MODULES.md: tests may use it).
    testImplementation(project(":core:testing"))

    // Test only: virtual time for the refresher's backoff.
    testImplementation(libs.kotlinx.coroutines.test)
}
