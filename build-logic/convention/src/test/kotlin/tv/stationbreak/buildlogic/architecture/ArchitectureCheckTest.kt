// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds the fixture projects in src/test/fixtures with the convention plugins on the classpath. */
class ArchitectureCheckTest {
    @TempDir
    lateinit var projectDir: File

    private fun runner(fixture: String): GradleRunner {
        File(System.getProperty("fixtures"), fixture).copyRecursively(projectDir)
        return GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("checkArchitecture", "--configuration-cache", "--stacktrace")
    }

    @Test
    fun aBadEdgeFailsTheBuildNamingTheModuleTheDependencyAndTheRule() {
        val result = runner("bad-edge").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":core:scheduler:checkArchitecture")?.outcome, result.output)
        assertTrue(
            ":core:scheduler -> :core:engine (implementation) breaks [scheduler-independence]" in result.output,
            result.output,
        )
    }

    @Test
    fun coreDomainTestFixturesInAMainConfigurationFailsTheBuild() {
        val runner = runner("allowed-edges")
        val engine = projectDir.resolve("core/engine/build.gradle.kts")
        engine.writeText(
            engine.readText().replace(
                "testImplementation(testFixtures(project(\":core:domain\")))",
                "implementation(testFixtures(project(\":core:domain\")))",
            ),
        )

        val result = runner.buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":core:engine:checkArchitecture")?.outcome, result.output)
        assertTrue(
            ":core:engine -> testFixtures(:core:domain) (implementation) breaks [testfixtures-in-tests-only]" in
                result.output,
            result.output,
        )
    }

    // The fixture includes core:engine's tests using core:domain's testFixtures.
    @Test
    fun onlyAllowedEdgesPass() {
        val result = runner("allowed-edges").build()

        for (module in listOf("model", "rules", "engine", "scheduler", "testing", "domain")) {
            assertEquals(TaskOutcome.SUCCESS, result.task(":core:$module:checkArchitecture")?.outcome, module)
        }
    }
}
