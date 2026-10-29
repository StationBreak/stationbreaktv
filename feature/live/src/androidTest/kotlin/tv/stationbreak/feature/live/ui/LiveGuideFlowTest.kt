// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_GUIDE
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.model.ChannelId
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

    private val surfaces = CopyOnWriteArrayList<String>()

    // Records the surface's life: a destroyed surface is a black picture and a new decoder on the TV.
    private val surfaceLife =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaces += "created"
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaces += "destroyed"
            }
        }

    private fun show(
        state: LiveState,
        video: @Composable () -> Unit = {},
    ) {
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
                    guide = { _, onTune -> FakeGuide(onTune) },
                    video = video,
                )
            }
        }
        compose.waitForIdle()
    }

    /**
     * Stands in for the guide: takes the focus as the guide does, Select tunes [PICKED] as on a now cell, and Back goes
     * to the activity on its release, as the guide hands it on.
     */
    @Composable
    private fun FakeGuide(onTune: (ChannelId) -> Unit) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(focus) { focus.requestFocus() }
        val backs = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        Box(
            Modifier
                .fillMaxSize()
                .testTag(GUIDE)
                .focusRequester(focus)
                .onKeyEvent {
                    when (it.key) {
                        Key.DirectionCenter -> {
                            if (it.type == KeyEventType.KeyDown) onTune(PICKED)
                            true
                        }

                        Key.Back -> {
                            if (it.type == KeyEventType.KeyUp) backs?.onBackPressed()
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }.focusable(),
        )
    }

    private fun tap(code: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val at = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(at, at, ACTION_DOWN, code, 0))
        compose.waitForIdle()
        instrumentation.sendKeySync(KeyEvent(at, at + PRESS_MS, ACTION_UP, code, 0))
        compose.waitForIdle()
    }

    /**
     * The fake guide appears and disappears in the same recomposition as the state flip, so this covers key delivery
     * and recomposition latency on a slow emulator, not an animation.
     */
    private fun awaitGuide(open: Boolean) =
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithTag(GUIDE).fetchSemanticsNodes().isNotEmpty() == open
        }

    @Test
    fun backOpensGuideThenExits() {
        show(LiveState.Watching(null))
        tap(KEYCODE_BACK)
        awaitGuide(open = true)
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
        awaitGuide(open = true)
        assertEquals(0, exits)
        assertEquals(emptyList<LiveCommand>(), sent.toList())
    }

    @Test
    fun guideKeyOpensAndClosesTheGuide() {
        show(LiveState.Watching(tuneBanner))
        tap(KEYCODE_GUIDE)
        awaitGuide(open = true)
        tap(KEYCODE_GUIDE)
        awaitGuide(open = false)
        assertEquals(0, exits)
    }

    @Test
    fun surfaceIsNeverDestroyedAcrossOpenCloseReopen() {
        show(LiveState.Watching(null)) {
            AndroidView(factory = { SurfaceView(it).also { view -> view.holder.addCallback(surfaceLife) } })
        }
        compose.waitUntil(WAIT_MS) { "created" in surfaces }
        tap(KEYCODE_BACK)
        awaitGuide(open = true)
        tap(KEYCODE_GUIDE)
        awaitGuide(open = false)
        tap(KEYCODE_BACK)
        awaitGuide(open = true)
        tap(KEYCODE_GUIDE)
        awaitGuide(open = false)
        // Moved to the corner and back by its modifier only: the one surface lives on, never made again.
        assertEquals(listOf("created"), surfaces.toList())
    }

    @Test
    fun selectNowCellTunesAndClosesGuide() {
        show(LiveState.Watching(null))
        tap(KEYCODE_BACK)
        awaitGuide(open = true)
        tap(KEYCODE_DPAD_CENTER)
        awaitGuide(open = false)
        assertEquals(listOf<LiveCommand>(LiveCommand.Tune(PICKED)), sent.toList())
        assertEquals(0, exits)
    }

    private companion object {
        val PICKED = ChannelId("ch-picked")
        const val WAIT_MS = 5_000L
        const val GUIDE = "test:guide"
        const val PRESS_MS = 50L
    }
}
