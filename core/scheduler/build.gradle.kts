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
    }
}

// The rules-format vectors are the source of truth. The jvmTest checks read them from the repository and compare them with
// the Kotlin copies in commonTest, which is what linuxX64Test can use (it cannot read files).
val rulesFormatDir = rootProject.layout.projectDirectory.dir("docs/rules-format")

tasks.withType<Test>().configureEach {
    systemProperty("rulesFormat.dir", rulesFormatDir.asFile.absolutePath)
    inputs.files(rulesFormatDir.asFileTree).withPropertyName("rulesFormatFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}
