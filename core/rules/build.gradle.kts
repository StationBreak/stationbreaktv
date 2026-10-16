// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    id("stationbreak.kmp-lib")
}

kotlin {
    sourceSets.commonMain.dependencies {
        // The rules are read and written as a JSON tree; unknown fields stay in JsonObject extras, which the types expose.
        api(libs.kotlinx.serialization.json)
        // The types expose Seed and CanonicalId.
        api(project(":core:model"))
    }
    // Test only: checks the rules-format examples against the JSON Schema. Not a runtime dependency of any module.
    sourceSets.jvmTest.dependencies {
        implementation(project.dependencies.platform(libs.jackson.bom))
        implementation(libs.json.schema.validator)
    }
}

// The rules-format files (schema, examples, document) are the source of truth. The tests read them from the repository.
val rulesFormatDir = rootProject.layout.projectDirectory.dir("docs/rules-format")
val rulesFormatDoc = rootProject.layout.projectDirectory.file("docs/rules-format.md")

tasks.withType<Test>().configureEach {
    systemProperty("rulesFormat.dir", rulesFormatDir.asFile.absolutePath)
    inputs.files(rulesFormatDir.asFileTree).withPropertyName("rulesFormatFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .files(rulesFormatDoc)
        .withPropertyName("rulesFormatDoc")
        .optional()
        .withPathSensitivity(PathSensitivity.NONE)
}
