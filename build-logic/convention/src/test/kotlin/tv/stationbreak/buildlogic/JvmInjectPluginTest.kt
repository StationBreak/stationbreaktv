// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Applies `stationbreak.jvm-inject` to a fixture project and reads back what it declared. */
class JvmInjectPluginTest {
    @TempDir
    lateinit var projectDir: File

    private fun declared(): List<String> {
        File(System.getProperty("fixtures"), "jvm-inject").copyRecursively(projectDir)
        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("printDeclared", "--quiet", "--stacktrace")
                .build()
        return result.output
            .lines()
            .filter { it.startsWith("DECLARED ") }
            .map { it.removePrefix("DECLARED ") }
    }

    @Test
    fun addsJavaxInjectToImplementationAndNothingElse() {
        // No Dagger, no Hilt (MODULES.md, "Hilt in pure modules"): the annotations alone.
        assertEquals(listOf("implementation javax.inject:javax.inject:1"), declared())
    }
}
