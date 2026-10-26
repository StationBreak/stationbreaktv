// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_GUIDE
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Back and the Guide key through the real input path (spec 17): the first Back from the full picture opens the guide,
 * the next one leaves live TV. A Back the screen does not take reaches the activity, which this test's own BackHandler
 * stands in for.
 */
@RunWith(AndroidJUnit4::class)
class LiveGuideFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sent = CopyOnWriteArrayList<LiveCommand>()
    private val guideOpen = mutableStateOf(false)
    private var exits = 0

    private val tuneBanner =
        Banner(4, "Test Channel", BannerLabel.Now, "Test Movie A", null, 0L, 1_800_000L, null)

    private fun show(state: LiveState) {
        compose.setContent {
            BackHandler { exits++ }
            StationBreakTheme {
                LiveKeysRoot(
                    state = state,
                    info = null,
                    onCommand = { sent += it },
                    guideOpen = guideOpen.value,
                    // Read when the key arrives, as the ViewModel's flags are: never a frame behind.
                    upNow = { overlayUpOf(state, null, guideOpen.value) },
                    onGuide = { guideOpen.value = it },
                    guide = { Box(Modifier.fillMaxSize().testTag(GUIDE)) },
                ) {}
            }
        }
        compose.waitForIdle()
    }

    private fun tap(code: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val at = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(at, at, ACTION_DOWN, code, 0))
        compose.waitForIdle()
        instrumentation.sendKeySync(KeyEvent(at, at + PRESS_MS, ACTION_UP, code, 0))
        compose.waitForIdle()
    }

    @Test
    fun backOpensGuideThenExits() {
        show(LiveState.Watching(null))
        tap(KEYCODE_BACK)
        compose.onNodeWithTag(GUIDE).assertExists()
        // The release of the press that opened the guide did not reach the activity.
        assertEquals(0, exits)
        tap(KEYCODE_BACK)
        assertEquals(1, exits)
        assertEquals(emptyList<LiveCommand>(), sent.toList())
    }

    @Test
    fun backWithTuneBannerOpensGuide() {
        show(LiveState.Watching(tuneBanner))
        tap(KEYCODE_BACK)
        compose.onNodeWithTag(GUIDE).assertExists()
        assertEquals(0, exits)
        assertEquals(emptyList<LiveCommand>(), sent.toList())
    }

    @Test
    fun guideKeyOpensAndClosesTheGuide() {
        show(LiveState.Watching(tuneBanner))
        tap(KEYCODE_GUIDE)
        compose.onNodeWithTag(GUIDE).assertExists()
        tap(KEYCODE_GUIDE)
        compose.onNodeWithTag(GUIDE).assertDoesNotExist()
        assertEquals(0, exits)
    }

    private companion object {
        const val GUIDE = "test:guide"
        const val PRESS_MS = 50L
    }
}
