// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.Directory
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension

// ADR-0003: every Kotlin source and build file carries this header. `./gradlew spotlessApply` inserts it.
internal const val SPDX_HEADER = "// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore"

// MODULES.md: JDK 17 everywhere, matching CI's setup-java.
internal const val JDK_VERSION = 17

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.intVersion(alias: String): Int = version(alias).toInt()

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

// The repository root, read through Gradle's isolated view so the plugins stay isolated-projects safe.
internal val Project.repoRoot: Directory
    get() = isolated.rootProject.projectDirectory

/**
 * #211: JUnit 5 skips a `@Test` it cannot run (one that returns a value, as an expression body ending in `assertIs`
 * does) and says nothing. From 5.13 it reports such a method as a discovery issue at WARNING; with WARNING as the
 * critical severity the run fails and names it, instead of passing with the test never run.
 */
internal fun Test.failOnSkippedTests() {
    systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
}

internal fun Project.configureKotlinToolchain() {
    extensions.getByType<KotlinBaseExtension>().jvmToolchain(JDK_VERSION)
}

// Task names, not task objects: a name the plugins stop creating fails the build instead of silently dropping
// the gate.
internal fun Project.checkDependsOn(vararg taskNames: String) {
    tasks.named("check") { dependsOn(*taskNames) }
}
