// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.benchmark

import android.util.Log
import android.view.KeyEvent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Times the guide opening from a cold launch (#416, ADR-0008): the app starts, the last channel tunes, Back opens the
 * guide, and the time from that key press until the guide's "Open the menu" hint is on screen is logged for each
 * launch (tag [TAG], one line per launch), beside the frame timing Macrobenchmark records for the same stretch.
 * Run it once with no compiled profile and once with the build's Baseline Profile, so a profile (or a rule in it) is
 * judged by the time it saves. The wait polls about every 100 ms, so a time is good to that much.
 *
 * The `launches` argument sets how many launches a run times (default [LAUNCHES]); more tame the emulator's noise.
 *
 * Needs the same set-up as [GuideBaselineProfile]: a device on Android 13 or later, the target build installed, signed
 * in with libraries chosen, and the `targetAppId` argument.
 */
@RunWith(AndroidJUnit4::class)
class GuideOpenBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun guideOpenWithoutProfile() = measure("none", CompilationMode.None())

    @Test
    fun guideOpenWithProfile() = measure("profile", withProfile)

    private fun measure(
        label: String,
        mode: CompilationMode,
    ) {
        var launch = 0
        rule.measureRepeated(
            packageName = targetPackage(),
            metrics = listOf(FrameTimingMetric()),
            compilationMode = mode,
            startupMode = StartupMode.COLD,
            iterations = launches(),
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            // Live TV tunes the last channel; give the first picture a moment before the guide opens over it.
            Thread.sleep(TUNE_MS)
            val keys = Keys(device, targetPackage())
            val start = System.nanoTime()
            keys.press(KeyEvent.KEYCODE_BACK, thenWaitMs = 0)
            check(device.wait(Until.hasObject(By.textContains(GUIDE_HINT)), WAIT_MS)) { "the guide did not open" }
            val ms = (System.nanoTime() - start) / NANOS_PER_MS
            Log.i(TAG, "mode=$label launch=${launch++} guideOpenMs=$ms")
        }
    }

    private val withProfile = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)

    private fun launches() = InstrumentationRegistry.getArguments().getString("launches")?.toInt() ?: LAUNCHES

    private companion object {
        const val TAG = "GuideOpen"
        const val GUIDE_HINT = "Open the menu"
        const val LAUNCHES = 10
        const val TUNE_MS = 6_000L
        const val WAIT_MS = 15_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
