// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.kmp-lib")
}

kotlin {
    sourceSets.commonMain.dependencies {
        // The scheduler's inputs and outputs (settings, items, blocks) are core:model types, and it may use nothing else.
        api(project(":core:model"))
    }
    // Test only: the vector files are JSON. The library is already in the build for core:model and core:rules.
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.serialization.json)
        // Test only: a 10,000-item library for the counted-steps test and the benchmark (MODULES.md: tests may use it).
        implementation(project(":core:testing"))
    }
}

// The rules-format vectors are the source of truth. The jvmTest checks read them from the repository and compare them with
// the Kotlin copies in commonTest, which is what linuxX64Test can use (it cannot read files).
val rulesFormatDir = rootProject.layout.projectDirectory.dir("docs/rules-format")

tasks.withType<Test>().configureEach {
    systemProperty("rulesFormat.dir", rulesFormatDir.asFile.absolutePath)
    inputs.files(rulesFormatDir.asFileTree).withPropertyName("rulesFormatFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}

// The benchmark is a wall-clock measurement, so it never gates anything (spec 08, Technical Considerations): it is left out
// of jvmTest (and so of allTests and check) and runs only through `./gradlew :core:scheduler:benchmark`.
val benchmarkClass = "*WhatsOnBenchmark"

tasks.named<Test>("jvmTest") {
    filter { excludeTestsMatching(benchmarkClass) }
}

tasks.register<Test>("benchmark") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Times Day.whatsOn on a 10,000-item day and prints the median and p95. Not part of check."
    val jvmTest = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath
    filter { includeTestsMatching(benchmarkClass) }
    // A timing is never "up to date", and the numbers are the point, so show the test's output.
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}
