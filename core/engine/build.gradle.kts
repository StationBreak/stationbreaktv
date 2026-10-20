// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.kmp-lib")
}

kotlin {
    sourceSets.commonMain.dependencies {
        // The evaluator reads the channel rules (sources, exclusions, the rating ceiling) and the rating check.
        api(project(":core:rules"))
        // Its input snapshot and its output items are core:model types.
        api(project(":core:model"))
    }
    // Test only: the pool vectors are JSON. The library is already in the build for core:model and core:rules.
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.serialization.json)
    }
    // Test only: a 15,000-item library for the lineup planner's size test, which runs on the JVM only (MODULES.md:
    // tests may use it).
    sourceSets.jvmTest.dependencies {
        implementation(project(":core:testing"))
    }
}

// The rules-format vectors are the source of truth. The jvmTest check reads them from the repository and compares them
// with the Kotlin copy in commonTest, which is what linuxX64Test can use (it cannot read files).
val rulesFormatDir = rootProject.layout.projectDirectory.dir("docs/rules-format")

tasks.withType<Test>().configureEach {
    systemProperty("rulesFormat.dir", rulesFormatDir.asFile.absolutePath)
    inputs.files(rulesFormatDir.asFileTree).withPropertyName("rulesFormatFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}
