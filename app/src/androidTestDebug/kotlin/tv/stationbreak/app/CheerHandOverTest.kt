// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.app.nav.CheerCover
import tv.stationbreak.app.nav.GraphCoverHost
import tv.stationbreak.app.nav.SetupHandOver
import tv.stationbreak.core.ui.LocalCovered
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.onboarding.firstsync.FirstSyncCheerStill
import tv.stationbreak.feature.onboarding.firstsync.rememberFirstSyncCheerFrame
import kotlin.time.Duration.Companion.seconds

/**
 * Setup's cheer held over live TV (the graph's [GraphCoverHost] with its [CheerCover], [SetupHandOver] and
 * [FirstSyncCheerStill]), over a stand-in for live TV that stands by until the test says its picture is up, and shows
 * the first-visit note as the guide does. While the tune holds nothing beneath can be reached; the picture lifts the
 * still and the note is a fresh polite region; a key or Back under the still lifts it and goes no further; and the
 * wait lifts it with the tune still held.
 *
 * The real live TV and guide are not in this graph: a channel on the air needs a built lineup, and the app's Hilt
 * tests have no way to seed one. What live TV tells the cover is proven by `StandByOverTest` in feature:live.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CheerHandOverTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    private val handOver = SetupHandOver(WAIT)
    private var standingBy by mutableStateOf(true)
    private var keysBeneath by mutableIntStateOf(0)

    // Whether the stand-in has asked for the focus yet. Live TV is built after the cheer ends and asks only once it is
    // started, so it never asks before the cover begins; a test can hold the ask back for longer.
    private var liveTakesFocus by mutableStateOf(false)
    private var liveAsksWithTheCheer = true

    // Whether the stand-in holds the focus: read from the node itself, since a covered node is in no merged tree.
    private var liveFocused = false

    @Before
    fun inject() = hilt.inject()

    /** The graph's cover over the stand-in, then the cheer ends: the still is up from that moment. */
    private fun handOver() {
        compose.setContent {
            StationBreakTheme {
                val cheerFrame = rememberFirstSyncCheerFrame()
                LaunchedEffect(handOver.covering) { if (handOver.covering) handOver.hold() }
                GraphCoverHost(
                    listOf(remember { CheerCover(handOver) }),
                    overlays = {
                        if (handOver.covering) {
                            Box(Modifier.testTag(STILL)) { FirstSyncCheerStill(cheerFrame) }
                        }
                    },
                ) { StandInLive() }
            }
        }
        compose.runOnIdle { handOver.begin() }
        compose.waitForIdle()
        if (liveAsksWithTheCheer) {
            compose.runOnIdle { liveTakesFocus = true }
            compose.waitForIdle()
        }
    }

    /** Live TV as the cover sees it: a stand-by until the picture, the first-visit note, and the focus. */
    @Composable
    private fun StandInLive() {
        val focus = remember { FocusRequester() }
        var note by remember { mutableStateOf(true) }
        LaunchedEffect(liveTakesFocus) { if (liveTakesFocus) focus.requestFocus() }
        LaunchedEffect(standingBy) { if (!standingBy) handOver.end() }
        Column(
            Modifier
                .fillMaxSize()
                .testTag(BENEATH)
                .focusRequester(focus)
                .onFocusChanged { liveFocused = it.isFocused }
                .onKeyEvent {
                    keysBeneath++
                    note = false
                    false
                }.focusable(),
        ) {
            if (standingBy) Text("Please stand by", Modifier.testTag(STAND_BY))
            if (note) {
                key(LocalCovered.current) {
                    Text(
                        "Here are your channels",
                        Modifier.testTag(NOTE).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }

    // What a screen reader can reach: the merged tree. The unmerged tree still lists the nodes a cover's
    // clearAndSetSemantics has replaced, so it would find the hidden stand-by card and note.
    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun press(keyCode: Int) {
        compose.awaitWindowFocus()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    @Test
    fun whileTheTuneHoldsNoStandByAndNoNoteCanBeReached() {
        handOver()

        assertEquals("the still is up", 1, count(STILL))
        assertEquals("no stand-by card", 0, count(STAND_BY))
        assertEquals("no guide note", 0, count(NOTE))
    }

    @Test
    fun thePictureLiftsTheStillAndTheNoteIsANewPoliteRegionWithTheFocusBeneath() {
        handOver()

        compose.runOnIdle { standingBy = false }
        compose.waitUntil(TIMEOUT_MS) { count(STILL) == 0 }

        val note = compose.onNodeWithTag(NOTE).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, note.config[SemanticsProperties.LiveRegion])
        compose.onNodeWithTag(BENEATH).assertIsFocused()
    }

    @Test
    fun selectUnderTheStillLiftsItAndReachesNothingBeneath() {
        handOver()

        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals("lifted", 0, count(STILL))
        assertEquals("no key reached live TV: nothing tuned", 0, keysBeneath)
        assertEquals("the first-visit note is still there", 1, count(NOTE))
    }

    @Test
    fun backBeforeLiveTvHasTheFocusStillLiftsTheStillAndStaysInTheApp() {
        // The cheer's focused headline is gone and live TV has not asked for the focus yet: the cover holds it, so
        // Back is the cover's and never leaves the app on the first run.
        liveAsksWithTheCheer = false
        handOver()
        assertFalse("live TV has not taken the focus yet", liveFocused)

        press(KeyEvent.KEYCODE_BACK)

        assertFalse("Back before live TV has the focus never leaves the app", compose.activity.isFinishing)
        assertEquals("lifted", 0, count(STILL))
        assertEquals(0, keysBeneath)

        // Live TV's own request, when it comes, still wins.
        compose.runOnIdle { liveTakesFocus = true }
        compose.waitForIdle()
        compose.onNodeWithTag(BENEATH).assertIsFocused()
    }

    @Test
    fun backUnderTheStillLiftsItAndStaysInTheApp() {
        handOver()

        press(KeyEvent.KEYCODE_BACK)

        assertEquals("lifted", 0, count(STILL))
        assertFalse("Back under the still never leaves the app", compose.activity.isFinishing)
        assertEquals(0, keysBeneath)
    }

    @Test
    fun afterTheWaitTheStillLiftsWithTheTuneStillHeld() {
        handOver()

        compose.waitUntil(WAIT.inWholeMilliseconds + TIMEOUT_MS) { count(STILL) == 0 }

        // Live TV's own stand-by shows, as it did before the hand-over.
        assertEquals(1, count(STAND_BY))
    }

    private companion object {
        val WAIT = 2.seconds
        const val TIMEOUT_MS = 5_000L
        const val STILL = "cheerHandOver.still"
        const val BENEATH = "cheerHandOver.live"
        const val STAND_BY = "cheerHandOver.standBy"
        const val NOTE = "cheerHandOver.note"
    }
}
