// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Applies `stationbreak.hilt` to a fixture project and reads back what it declared (F0.3 task 3.13). Hilt's and
 * KSP's real Gradle plugins aren't on the TestKit classpath, so the fixture's `buildSrc` stands in for them with
 * plugins of the same IDs that only create the configurations.
 */
class HiltPluginTest {
    @TempDir
    lateinit var projectDir: File

    private fun declared(project: String): List<String> {
        File(System.getProperty("fixtures"), "hilt").copyRecursively(projectDir)
        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(":$project:printDeclared", "--quiet", "--stacktrace")
                .build()
        return result.output
            .lines()
            .filter { it.startsWith("DECLARED ") }
            .map { it.removePrefix("DECLARED ") }
    }

    @Test
    fun addsHiltAndItsProcessorInTheRightConfigurations() {
        assertEquals(
            listOf(
                "androidTestImplementation com.google.dagger:hilt-android-testing:9.9",
                "implementation com.google.dagger:hilt-android:9.9",
                "ksp com.google.dagger:hilt-compiler:9.9",
                "kspAndroidTest com.google.dagger:hilt-compiler:9.9",
            ),
            declared("app"),
        )
    }

    @Test
    fun anAndroidFeatureModuleMayApplyItToo() {
        // F0.5: feature:* modules apply stationbreak.hilt for @HiltViewModel (MODULES.md, "Hilt in pure modules").
        assertEquals(
            listOf(
                "androidTestImplementation com.google.dagger:hilt-android-testing:9.9",
                "implementation com.google.dagger:hilt-android:9.9",
                "ksp com.google.dagger:hilt-compiler:9.9",
                "kspAndroidTest com.google.dagger:hilt-compiler:9.9",
            ),
            declared("feature"),
        )
    }

    @Test
    fun addsNothingToAModuleThatDoesNotApplyIt() {
        // A KMP module (MODULES.md, "Hilt in pure modules") never applies the plugin, so it gets no Dagger.
        assertEquals(emptyList(), declared("kmp"))
    }
}
