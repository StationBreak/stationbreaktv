// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

plugins {
    // Declared once here, not applied, so every project shares one copy of each plugin; the convention plugins
    // in build-logic apply them.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP 9's built-in Kotlin uses the Kotlin Gradle plugin on this classpath, so it and the KMP and JVM modules
    // share the catalog's Kotlin version.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.spotless) apply false
    // Spotless and detekt, as in every module; Spotless formats the root's own build scripts.
    id("stationbreak.quality")
}

tasks.named("check") {
    // build-logic is an included build, so its checks (Spotless now, its tests later) don't run otherwise.
    dependsOn(gradle.includedBuild("build-logic").task(":convention:check"))
}
