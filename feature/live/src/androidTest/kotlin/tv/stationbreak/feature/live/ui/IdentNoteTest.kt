// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.ChannelChangeStyle.Stan
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.CardNote
import tv.stationbreak.feature.live.LiveState
import kotlin.math.hypot
import kotlin.math.min

/**
 * The off-air screen's reason (spec 26, #633): Stan's detail line, in two lines at most, inside the safe area, never
 * under the 10 sp floor. And the no-libraries card's line, which stays on the test card: at most three lines, and the
 * whole box inside the centre disc.
 */
@RunWith(AndroidJUnit4::class)
class IdentNoteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theNoteIsTwoLinesAtMostInsideTheSafeAreaAndNotUnderTheFloor() {
        compose.setContent {
            StationBreakTheme { LiveScreen(LiveState.Ident(null, null, CardNote.NotInLibrary), changeStyle = Stan) {} }
        }
        compose.waitForIdle()

        val note = compose.onNodeWithTag(LiveTags.STAN_DETAIL)
        val results = mutableListOf<TextLayoutResult>()
        val action = note.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("the line has a text layout", action != null && action(results))
        val laid = results.first()
        assertTrue("at most two lines, was ${laid.lineCount}", laid.lineCount <= 2)
        assertTrue("nothing is cut off", !laid.hasVisualOverflow)
        assertTrue("not under 10 sp", laid.layoutInput.style.fontSize.value >= 10f)
        // The text is placed at its baseline, so its right edge is what to hold to the safe area.
        val screen = compose.onNodeWithTag(LiveTags.STAN_OFF_AIR).fetchSemanticsNode().boundsInRoot
        val safeRight = screen.left + screen.width * (1f - 0.05f)
        val box = note.fetchSemanticsNode().boundsInRoot
        assertTrue("the line ends at ${box.right}, past the safe area's $safeRight", box.right <= safeRight + 1f)
    }

    // The no-libraries card's line (spec 26) is set as the reason is, under the same rule.
    @Test
    fun theNoLibrariesLineIsThreeLinesAtMostAndInsideTheDisc() {
        compose.setContent { StationBreakTheme { LiveScreen(LiveState.NoLibraries, changeStyle = Stan) {} } }
        compose.waitForIdle()

        assertThreeLinesAtMostInsideTheDisc(LiveTags.NO_LIBRARIES)
    }

    // The study's longest headline, "WELL, THAT'S" in Ranchers at its full size, would reach past the safe area's edge:
    // the screen measures it and shrinks the headline to the column. Every line of copy is on one line, inside the safe
    // area, and over the 10 sp floor.
    @Test
    fun theLongestHeadlineFitsItsColumnInsideTheSafeArea() {
        compose.setContent {
            StationBreakTheme { LiveScreen(LiveState.TechnicalDifficulties(null), changeStyle = Stan) {} }
        }
        compose.waitForIdle()

        val screen = compose.onNodeWithTag(LiveTags.STAN_STAGE).fetchSemanticsNode().boundsInRoot
        val safeRight = screen.left + screen.width * (1f - SAFE_FRACTION)
        for (tag in listOf(LiveTags.STAN_HEADLINE, LiveTags.STAN_STATUS, LiveTags.STAN_DETAIL)) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().forEach { node ->
                val results = mutableListOf<TextLayoutResult>()
                val action = node.config[SemanticsActions.GetTextLayoutResult].action
                assertTrue("$tag has a text layout", action != null && action(results))
                val laid = results.first()
                assertTrue("$tag is on one line, was ${laid.lineCount}", laid.lineCount == 1)
                assertTrue("$tag is not cut off", !laid.hasVisualOverflow)
                assertTrue("$tag is not under 10 sp", laid.layoutInput.style.fontSize.value >= 10f)
                val end = node.boundsInRoot.right
                assertTrue("$tag ends at $end, past the safe area's $safeRight", end <= safeRight + 1f)
            }
        }
    }

    private fun assertThreeLinesAtMostInsideTheDisc(tag: String) {
        val note = compose.onNodeWithTag(tag)
        val results = mutableListOf<TextLayoutResult>()
        val action = note.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("the line has a text layout", action != null && action(results))
        assertTrue("at most three lines, was ${results.first().lineCount}", results.first().lineCount <= 3)
        assertTrue("nothing is cut off", !results.first().hasVisualOverflow)

        // The card is drawn centred at 1024 x 576 units, scaled to fit; its disc is radius 150 about (512, 288).
        val card = compose.onNodeWithTag(LiveTags.TEST_CARD).fetchSemanticsNode().boundsInRoot
        val unit = min(card.width / CARD_W, card.height / CARD_H)
        val cx = card.left + card.width / 2
        val cy = card.top + card.height / 2 + (288f - CARD_H / 2) * unit
        val box = note.fetchSemanticsNode().boundsInRoot
        listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom).forEach {
            val distance = hypot(it.first - cx, it.second - cy)
            assertTrue("corner $it is ${distance / unit} units from the disc centre", distance <= 150f * unit)
        }
    }

    private companion object {
        const val SAFE_FRACTION = 0.05f
        const val CARD_W = 1024f
        const val CARD_H = 576f
    }
}
