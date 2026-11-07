// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.live.CardNote
import tv.stationbreak.feature.live.LiveState
import kotlin.math.hypot
import kotlin.math.min

/** The ident card's reason (spec 26, #633): at most two lines, and the whole box inside the centre disc. */
@RunWith(AndroidJUnit4::class)
class IdentNoteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theNoteIsTwoLinesAtMostAndInsideTheDisc() {
        compose.setContent { StationBreakTheme { LiveScreen(LiveState.Ident(null, null, CardNote.NotInLibrary)) {} } }
        compose.waitForIdle()

        val note = compose.onNodeWithTag(LiveTags.IDENT_NOTE)
        val results = mutableListOf<TextLayoutResult>()
        val action = note.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("the note has a text layout", action != null && action(results))
        assertTrue("at most two lines, was ${results.first().lineCount}", results.first().lineCount <= 2)
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
        const val CARD_W = 1024f
        const val CARD_H = 576f
    }
}
