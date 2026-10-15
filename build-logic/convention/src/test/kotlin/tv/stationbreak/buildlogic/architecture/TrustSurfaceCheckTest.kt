// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrustSurfaceCheckTest {
    @TempDir
    lateinit var projectDir: File

    private fun runner(vararg tasks: String): GradleRunner {
        File(System.getProperty("fixtures"), "trust-surface").copyRecursively(projectDir)
        return GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*tasks, "--configuration-cache", "--stacktrace")
    }

    @Test
    fun theTrustApiOutsideTheTrustPackageFailsTheBuildNamingTheFileAndTheLine() {
        val result = runner("checkTrustSurface", "--continue").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":feature:bad:checkTrustSurface")?.outcome, result.output)
        assertTrue("src/main/kotlin/b/Bad.kt:3" in result.output, result.output)
    }

    @Test
    fun theTrustPackageOfDataJellyfinPasses() {
        val result = runner(":data:jellyfin:checkTrustSurface").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":data:jellyfin:checkTrustSurface")?.outcome, result.output)
    }

    @Test
    fun checkRunsIt() {
        val result = runner(":feature:bad:check").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":feature:bad:checkTrustSurface")?.outcome, result.output)
    }
}
