// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.ProgramInfoTags
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveInfo
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.NextProgram
import java.util.concurrent.CopyOnWriteArrayList

/** The banner's progress and "Next" line, the actions banner's buttons and the info overlay on screen (spec 17). */
@RunWith(AndroidJUnit4::class)
class LiveBannerActionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sent = CopyOnWriteArrayList<LiveCommand>()
    private val id = CanonicalId.parse("1".padStart(32, '0'))
    private val movie = ProgramInfo(id, ScheduleItemKind.Movie, "Test Movie A", null, null, null)

    private fun banner(
        actions: Boolean = false,
        hasLast: Boolean = false,
        next: NextProgram? = NextProgram("Test Movie B", START + 30 * MINUTE),
    ) = Banner(
        4,
        "Test Channel",
        BannerLabel.Now,
        "Test Movie A",
        movie,
        START,
        START + 30 * MINUTE,
        null,
        next,
        actions,
        hasLast,
    )

    private fun show(
        banner: Banner,
        info: LiveInfo? = null,
    ) {
        compose.setContent {
            StationBreakTheme {
                LiveScreen(LiveState.Watching(banner.takeIf { info == null }), info, { sent += it }) {}
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun progressAndNextAreShown() {
        show(banner())
        compose.onNodeWithTag(LiveTags.BANNER_PROGRESS).assertExists()
        val next = compose.onNodeWithTag(LiveTags.BANNER_NEXT)
        next.assertExists()
        val text = next.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(text, text.startsWith("Next: Test Movie B at "))
    }

    @Test
    fun progressReadsTheClockAndSaysHowLongIsLeft() {
        compose.setContent {
            StationBreakTheme { CableBanner(banner(), clock = { START + 12 * MINUTE }) }
        }
        compose.waitForIdle()
        val config = compose.onNodeWithTag(LiveTags.BANNER_PROGRESS).fetchSemanticsNode().config
        assertEquals(0.4f, config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        assertEquals(listOf("18 min left"), config[SemanticsProperties.ContentDescription])
    }

    @Test
    fun noNextLineWhenNothingFollows() {
        show(banner(next = null))
        compose.onNodeWithTag(LiveTags.BANNER_PROGRESS).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER_NEXT).assertDoesNotExist()
    }

    @Test
    fun aTuneBannerHasNoButtons() {
        show(banner())
        compose.onNodeWithTag(LiveTags.BANNER_ACTIONS).assertDoesNotExist()
    }

    @Test
    fun theActionsBannerOpensOnInfo() {
        show(banner(actions = true, hasLast = true))
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LAST).assertExists()
    }

    @Test
    fun lastIsLeftOutWhenThereIsNoChannelToGoBackTo() {
        show(banner(actions = true, hasLast = false))
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LAST).assertDoesNotExist()
    }

    /**
     * The app's own key path: [LiveKeysRoot] takes the keys first, as on the TV. Select asks for the actions banner,
     * which this stand-in machine then shows, so the buttons appear the way they do after a real press.
     */
    private fun showKeysRoot() {
        compose.setContent {
            var state by remember { mutableStateOf<LiveState>(LiveState.Watching(banner())) }
            val machine = { command: LiveCommand ->
                sent += command
                if (command == LiveCommand.ShowBanner) {
                    state = LiveState.Watching(banner(actions = true, hasLast = true))
                }
            }
            StationBreakTheme { LiveKeysRoot(state, null, machine) {} }
        }
        compose.waitForIdle()
    }

    /** One remote press: down, a frame or more, then up, as a viewer's thumb does. */
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { keyDown(key) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { keyUp(key) }
        compose.waitForIdle()
    }

    @Test
    fun selectWithNoBannerOpensTheActionsBannerOnInfoAndNothingMore() {
        showKeysRoot()
        press(Key.DirectionCenter)
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        // The release of that same press must not reach the Info button it moved the focus to.
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowBanner), sent.toList())
    }

    @Test
    fun aHeldSelectOpensTheActionsBannerAndNeverClicksInfo() {
        showKeysRoot()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downAt = SystemClock.uptimeMillis()
        // A held key: the first down, auto-repeats with a rising count, then one release, through the real input path.
        for (repeat in 0..2) {
            val down = KeyEvent(downAt, downAt + repeat * REPEAT_MS, ACTION_DOWN, KEYCODE_DPAD_CENTER, repeat)
            instrumentation.sendKeySync(down)
            compose.waitForIdle()
        }
        val release = KeyEvent(downAt, downAt + 3 * REPEAT_MS, ACTION_UP, KEYCODE_DPAD_CENTER, 0)
        instrumentation.sendKeySync(release)
        compose.waitForIdle()
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        // Neither a repeat nor the release reached the Info button the first down moved the focus to.
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowBanner), sent.toList())
    }

    @Test
    fun lastActionTunesPreviousChannel() {
        showKeysRoot()
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_LAST).assertIsFocused()
        sent.clear()
        press(Key.DirectionCenter)
        assertEquals(listOf<LiveCommand>(LiveCommand.Previous), sent.toList())
    }

    @Test
    fun infoActionOpensTheOverlay() {
        showKeysRoot()
        press(Key.DirectionCenter)
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        sent.clear()
        press(Key.DirectionCenter)
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowInfo), sent.toList())
    }

    @Test
    fun enterWorksLikeSelectOnAButton() {
        showKeysRoot()
        press(Key.Enter)
        compose.onNodeWithTag(LiveTags.BANNER_ACTION_INFO).assertIsFocused()
        sent.clear()
        press(Key.Enter)
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowInfo), sent.toList())
    }

    @Test
    fun theOverlayReplacesTheBannerAndShowsItsPlaceholdersWhileLoading() {
        show(banner(), LiveInfo(banner(), null, loading = true))
        compose.onNodeWithTag(LiveTags.INFO).assertExists()
        compose.onNodeWithTag(ProgramInfoTags.LOADING, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(LiveTags.BANNER).assertDoesNotExist()
    }

    private companion object {
        const val MINUTE = 60_000L
        const val START = 1_800_000_000_000L
        const val REPEAT_MS = 50L
    }
}
