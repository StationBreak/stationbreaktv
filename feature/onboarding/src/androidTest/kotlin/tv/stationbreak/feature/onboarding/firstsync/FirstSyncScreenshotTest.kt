// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import java.io.File

/**
 * Spec 06 task 3.11: the first-sync progress screen on the API 25 emulator, at the start, part way and finished, as
 * PNG files in the test app's own external files directory, for the proof file. The numbers are those of the generated
 * 15,000-item library (`SyntheticLibrary`), never of a real one. The "done" picture is the last frame before the app
 * moves on to the live channel: the bar full, with the count at the total.
 *
 * Compose's own `captureToImage` needs API 26 (PixelCopy), so the whole screen is taken with UiAutomation, which API 25
 * has.
 */
@RunWith(AndroidJUnit4::class)
class FirstSyncScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val phase = mutableStateOf<FirstSyncPhase>(FirstSyncPhase.Starting)

    private fun shoot(
        name: String,
        shown: FirstSyncPhase,
    ) {
        phase.value = shown
        compose.waitForIdle()
        // Let the frame reach the screen before it is taken.
        Thread.sleep(FRAME_WAIT_MS)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("no screenshot", bitmap)
        val directory = checkNotNull(InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null))
        val file = File(directory, name)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) }
        assertTrue("$name is empty", file.length() > 0)
    }

    @Test
    fun theThreeProgressStatesAndTheFailure() {
        compose.setContent { StationBreakTheme { FirstSyncScreen(phase.value, onRetry = {}) } }
        shoot("3.0-first-sync-start.png", FirstSyncPhase.Syncing(copied = 0, total = TOTAL))
        shoot("3.0-first-sync-partway.png", FirstSyncPhase.Syncing(copied = TOTAL / 2, total = TOTAL))
        shoot("3.0-first-sync-done.png", FirstSyncPhase.Syncing(copied = TOTAL, total = TOTAL))
        val error = MediaServerError.NetworkUnreachable()
        shoot("3.0-first-sync-failed.png", FirstSyncPhase.Failed(error, ErrorCopyTable.copyFor(error), false))
        shoot("3.0-first-sync-waiting.png", FirstSyncPhase.WaitingForNetwork)
        val refused = MediaServerError.SessionExpired()
        shoot("3.0-first-sync-sign-in-again.png", FirstSyncPhase.Failed(refused, ErrorCopyTable.copyFor(refused), true))
    }

    private companion object {
        const val TOTAL = 15_000
        const val PNG_QUALITY = 100
        const val FRAME_WAIT_MS = 500L
    }
}
