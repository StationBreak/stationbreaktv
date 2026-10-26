// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgramInfoOverlayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        overview: String? = null,
        year: Int? = null,
        genres: List<String> = emptyList(),
        runtimeMinutes: Int? = null,
        loading: Boolean = false,
        episodeLine: String? = "S1 E2 · Episode One",
    ) = compose.setContent {
        StationBreakTheme {
            ProgramInfoOverlay(
                channel = "07 Test Channel",
                title = "Test Show",
                episodeLine = episodeLine,
                slot = "8:00 – 8:30 PM",
                overview = overview,
                year = year,
                genres = genres,
                runtimeMinutes = runtimeMinutes,
                loading = loading,
            )
        }
    }

    // The panel is one merged unit for a screen reader, so its parts are found in the unmerged tree.
    private fun tag(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun text(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    @Test
    fun showsFallbackWithoutDetails() {
        show()
        tag(ProgramInfoTags.TITLE).assertIsDisplayed()
        text("S1 E2 · Episode One").assertIsDisplayed()
        text("8:00 – 8:30 PM").assertIsDisplayed()
        tag(ProgramInfoTags.OVERVIEW).assertDoesNotExist()
        tag(ProgramInfoTags.DETAILS).assertDoesNotExist()
        tag(ProgramInfoTags.LOADING).assertDoesNotExist()
    }

    @Test
    fun noEpisodeLineForAMovie() {
        show(episodeLine = null)
        tag(ProgramInfoTags.TITLE).assertIsDisplayed()
        tag(ProgramInfoTags.EPISODE).assertDoesNotExist()
        tag(ProgramInfoTags.SLOT).assertIsDisplayed()
    }

    @Test
    fun showsLoading() {
        show(loading = true)
        tag(ProgramInfoTags.LOADING).assertIsDisplayed()
        tag(ProgramInfoTags.TITLE).assertIsDisplayed()
        tag(ProgramInfoTags.OVERVIEW).assertDoesNotExist()
    }

    @Test
    fun showsAllDetails() {
        show(overview = "A test overview.", year = 1999, genres = listOf("Drama", "Comedy"), runtimeMinutes = 45)
        tag(ProgramInfoTags.OVERVIEW).assertIsDisplayed()
        text("A test overview.").assertIsDisplayed()
        tag(ProgramInfoTags.DETAILS).assertIsDisplayed()
        text("1999 · Drama, Comedy · 45 min").assertIsDisplayed()
        tag(ProgramInfoTags.LOADING).assertDoesNotExist()
    }

    @Test
    fun saysHowToCloseIt() {
        show()
        tag(ProgramInfoTags.CLOSE_HINT).assertIsDisplayed()
        text("Back to close").assertIsDisplayed()
    }

    @Test
    fun aLongOverviewStillLeavesRoomForTheCloseHint() {
        show(overview = "A test overview. ".repeat(40), year = 1999, genres = listOf("Drama"), runtimeMinutes = 45)
        tag(ProgramInfoTags.CLOSE_HINT).assertIsDisplayed()
    }

    @Test
    fun theWholePanelIsOneNodeForAScreenReader() {
        show(overview = "A test overview.")
        // In the merged tree (the default) the title and the slot are read from the same node.
        val title = compose.onNodeWithText("Test Show").fetchSemanticsNode()
        val slot = compose.onNodeWithText("8:00 – 8:30 PM").fetchSemanticsNode()
        assertEquals(title.id, slot.id)
        val texts = title.config[SemanticsProperties.Text].map { it.text }
        assertTrue(texts.toString(), "Test Show" in texts && "8:00 – 8:30 PM" in texts)
        compose.onAllNodesWithText("A test overview.").assertCountEquals(1)
    }
}
