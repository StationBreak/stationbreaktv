// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Applies `stationbreak.contract-test` to a fixture Java project and reads back what it set up (F0.3, D7). */
class ContractTestPluginTest {
    @TempDir
    lateinit var projectDir: File

    private fun runner(
        vararg arguments: String,
        environment: Map<String, String> = emptyMap(),
    ): GradleRunner {
        File(System.getProperty("fixtures"), "contract-test").copyRecursively(projectDir, overwrite = true)
        return GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            // The test's own environment stays out of it: a developer's JELLYFIN_IMAGE must not change the result.
            .withEnvironment(System.getenv().filterKeys { it != "JELLYFIN_IMAGE" } + environment)
            .withArguments(*arguments, "--stacktrace")
    }

    private fun printed(
        key: String,
        environment: Map<String, String> = emptyMap(),
    ): String =
        runner("printContract", "--quiet", environment = environment)
            .build()
            .output
            .lines()
            .first { it.startsWith("$key ") }
            .removePrefix("$key ")

    @Test
    fun addsAContractTestSourceSetAndATestTaskOnTheJUnitPlatform() {
        assertEquals("contractTest,main,test", printed("SOURCE_SETS"))
        assertEquals("true", printed("JUNIT_PLATFORM"))
    }

    @Test
    fun checkCompilesTheSourceSetButNeverRunsIt() {
        val dependencies = printed("CHECK_DEPENDS").split(",")

        assertTrue("contractTestClasses" in dependencies, dependencies.toString())
        assertFalse("contractTest" in dependencies, dependencies.toString())
    }

    @Test
    fun aDryRunOfCheckListsNoContractTestTask() {
        val tasks =
            runner("check", "--dry-run")
                .build()
                .output
                .lines()
                .filter { it.startsWith(":") }
                .map { it.substringBefore(' ') }

        assertTrue(":contractTestClasses" in tasks, tasks.toString())
        assertFalse(":contractTest" in tasks, tasks.toString())
    }

    @Test
    fun passesTheImageThroughAnEnvironmentVariableNotAPropertyThatLandsInReports() {
        val image = "registry.example/jellyfin@sha256:0000"

        assertEquals(image, printed("ENV_IMAGE", environment = mapOf("JELLYFIN_IMAGE" to image)))
        assertEquals("", printed("SYSTEM_PROPERTIES"))
        assertEquals("[]", printed("JVM_ARGS"))
    }

    @Test
    fun hasNoImageWhenTheVariableIsUnset() {
        assertEquals("null", printed("ENV_IMAGE"))
    }

    @Test
    fun alwaysRunsWhenAskedBecauseTheServerIsAnInput() {
        assertEquals("true", printed("UP_TO_DATE_ALWAYS_RERUNS"))
    }
}
