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

    // RealRepairRun (the real repair run the heap test uses) plans a real lineup, which takes engine and scheduler types. Not api.
    testFixturesImplementation(project(":core:engine"))
    testFixturesImplementation(project(":core:scheduler"))

    // Test only: a 15,000-item library for the schedule source's size test (MODULES.md: tests may use it).
    testImplementation(project(":core:testing"))

    // Test only: virtual time for the refresher's backoff.
    testImplementation(libs.kotlinx.coroutines.test)
}

// CatalogMarksDriftTest reads the guide's bundled logos: Gradle must see them as inputs, or a changed logo folder leaves
// the cached test result in place.
val channelMarks = rootProject.layout.projectDirectory.dir("core/ui/src/main/assets/channelmarks")
tasks.withType<Test>().configureEach {
    inputs.dir(channelMarks).withPropertyName("channelMarks").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("stationbreak.channelmarks", channelMarks.asFile.absolutePath)
}

// The published reference fixture (docs/rules-format/fixtures/) is checked against what the app builds, and rewritten
// with -PupdateFixtures=true. A rewrite is never cached or skipped as up to date, or the file would not be written.
val rulesFormatDir = rootProject.layout.projectDirectory.dir("docs/rules-format")
val updateFixtures = providers.gradleProperty("updateFixtures").orElse("false")
tasks.withType<Test>().configureEach {
    systemProperty("rulesFormat.dir", rulesFormatDir.asFile.absolutePath)
    systemProperty("updateFixtures", updateFixtures.get())
    inputs
        .files(
            rulesFormatDir.dir("fixtures").asFileTree,
        ).withPropertyName("rulesFormatFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("updateFixtures", updateFixtures)
    if (updateFixtures.get() == "true") {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}

// The repair benchmark is a wall-clock measurement, so it never gates anything: it is left out of `test` (and so of
// `check`) and runs only through `./gradlew :core:domain:selectionRepairBenchmark` (as core/scheduler does).
val benchmarkClasses = listOf("*SelectionRepairBenchmark")

tasks.named<Test>("test") {
    filter { benchmarkClasses.forEach { excludeTestsMatching(it) } }
}

tasks.register<Test>("selectionRepairBenchmark") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Times a selection change's repair over a synthetic library at real scale. Not part of check."
    val test = tasks.named<Test>("test").get()
    testClassesDirs = test.testClassesDirs
    classpath = test.classpath
    filter { benchmarkClasses.forEach { includeTestsMatching(it) } }
    environment("STATIONBREAK_BENCH", "1")
    // A timing is never "up to date", and the numbers are the point, so show the test's output.
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}
