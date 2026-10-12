// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied to any module (AGP 9 has Kotlin built in); declared here to pin the Kotlin Gradle plugin version.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.licensee) apply false
}

// ADR-0003: every Kotlin source and build file carries this header. `./gradlew spotlessApply` inserts it.
val spdxHeader = "// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore"

allprojects {
    apply(plugin = "com.diffplug.spotless")

    configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint()
            // The default delimiter puts the header above `package`, `import` or `@file`.
            licenseHeader(spdxHeader)
        }
        kotlinGradle {
            // Each project's own build scripts: the root's settings and build files, and app/build.gradle.kts.
            target("*.gradle.kts")
            ktlint()
            // The header goes above the first line that isn't a `//` comment (`plugins {`, `import`,
            // `pluginManagement {`, or the blank line after an existing header).
            licenseHeader(spdxHeader, "^(?!//).*$")
        }
    }
}
