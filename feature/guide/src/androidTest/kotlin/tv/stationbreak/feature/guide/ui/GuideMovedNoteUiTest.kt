// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.lineup.MovedChannel
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.ProvideGuideColors

/**
 * The note of moved channels (#1107). A row's inner text is read through the unmerged tree (the row is one node for a
 * screen reader), and a scroll goes to a row's tag: a scroll matches merged semantics, so it cannot find inner text.
 */
@RunWith(AndroidJUnit4::class)
class GuideMovedNoteUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var dismissals = 0

    // The TV's own screen, so the list has the room (and the need to scroll) it has on a TV.
    private fun show(moved: List<MovedChannel>) {
        dismissals = 0
        compose.setContent {
            ProvideGuideColors(GuideColors.Dark) {
                Box(Modifier.size(960.dp, 540.dp)) { GuideMovedNote(moved, onDone = { dismissals++ }) }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onNodeWithTag(GuideTags.MOVED_NOTE).assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun many(count: Int) = (1..count).map { MovedChannel("Channel $it", it, 100 + it) }

    @Test
    fun oneChannelReadsAsOneRowUnderASingularTitle() {
        show(listOf(MovedChannel("Test One", 47, 100)))
        compose.onNodeWithTag(GuideTags.MOVED_TITLE, useUnmergedTree = true).assertTextEquals("A channel moved")
        compose
            .onNodeWithTag(GuideTags.MOVED_REASON, useUnmergedTree = true)
            .assertTextEquals(
                "Channels now keep to the band that fits what they show. These were below theirs, " +
                    "so they moved up. Nothing was removed.",
            )
        compose.onNodeWithTag(GuideTags.MOVED_NAME + 0, useUnmergedTree = true).assertTextEquals("Test One")
        compose.onNodeWithTag(GuideTags.MOVED_NUMBERS + 0, useUnmergedTree = true).assertTextEquals("47 → 100")
    }

    @Test
    fun severalChannelsKeepTheOrderTheyAreGivenWithAPluralTitle() {
        show(listOf(MovedChannel("Test Two", 12, 34), MovedChannel("Test One", 47, 100)))
        compose.onNodeWithTag(GuideTags.MOVED_TITLE, useUnmergedTree = true).assertTextEquals("Channels moved")
        compose.onNodeWithTag(GuideTags.MOVED_NAME + 0, useUnmergedTree = true).assertTextEquals("Test Two")
        compose.onNodeWithTag(GuideTags.MOVED_NAME + 1, useUnmergedTree = true).assertTextEquals("Test One")
    }

    @Test
    fun aScreenReaderHearsTheTitleTheReasonAndEachRowInWords() {
        show(listOf(MovedChannel("Test Two", 12, 34), MovedChannel("Test One", 47, 100)))
        compose
            .onNodeWithTag(GuideTags.MOVED_NOTE)
            .assertContentDescriptionEquals(
                "Channels moved. Channels now keep to the band that fits what they show. These were below theirs, " +
                    "so they moved up. Nothing was removed.. Test Two, was channel 12, now channel 34. " +
                    "Test One, was channel 47, now channel 100",
            )
        compose
            .onNodeWithTag(GuideTags.MOVED_ROW + 1)
            .assertContentDescriptionEquals("Test One, was channel 47, now channel 100")
    }

    @Test
    fun theListScrollsPastFourRowsWhileOkStaysFocusedAndOnScreen() {
        show(many(8))
        compose.onNodeWithTag(GuideTags.MOVED_ROW + 0).assertIsDisplayed()
        compose.onNodeWithTag(GuideTags.MOVED_ROW + 7).assertIsNotDisplayed()
        compose.onNodeWithTag(GuideTags.MOVED_OK).assertIsSelected().assertIsDisplayed()

        repeat(8) { press(Key.DirectionDown) }

        compose.onNodeWithTag(GuideTags.MOVED_ROW + 7).assertIsDisplayed()
        compose.onNodeWithTag(GuideTags.MOVED_ROW + 0).assertIsNotDisplayed()
        compose.onNodeWithTag(GuideTags.MOVED_OK).assertIsSelected().assertIsDisplayed()
        assertEquals("nothing was dismissed by scrolling", 0, dismissals)

        repeat(8) { press(Key.DirectionUp) }
        compose.onNodeWithTag(GuideTags.MOVED_ROW + 0).assertIsDisplayed()
    }

    @Test
    fun aTouchScrollReachesTheLastRow() {
        show(many(8))
        compose.onNodeWithTag(GuideTags.MOVED_LIST).performScrollToNode(hasTestTag(GuideTags.MOVED_ROW + 7))
        compose.onNodeWithTag(GuideTags.MOVED_ROW + 7).assertIsDisplayed()
        compose.onNodeWithTag(GuideTags.MOVED_OK).assertIsSelected().assertIsDisplayed()
    }

    // A direction or Menu is kept, so none reaches the guide under the sheet, but it answers nothing.
    private fun dismissesWith(key: Key) {
        show(many(2))
        press(Key.DirectionLeft)
        press(Key.DirectionRight)
        press(Key.Menu)
        assertEquals("a direction or Menu is not an answer", 0, dismissals)
        press(key)
        assertEquals("$key dismisses", 1, dismissals)
    }

    @Test
    fun selectDismisses() = dismissesWith(Key.DirectionCenter)

    @Test
    fun enterDismisses() = dismissesWith(Key.Enter)

    @Test
    fun backDismisses() = dismissesWith(Key.Back)

    @Test
    fun aClickOnOkDismissesToo() {
        show(many(1))
        compose.onNodeWithTag(GuideTags.MOVED_OK).performClick()
        assertEquals(1, dismissals)
    }
}
