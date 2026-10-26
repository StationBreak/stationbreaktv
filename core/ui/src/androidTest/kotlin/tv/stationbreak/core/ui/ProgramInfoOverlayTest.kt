// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
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

    /** What a test varies on the panel; everything else stays at the fallback. */
    private data class Details(
        val overview: String? = null,
        val year: Int? = null,
        val genres: List<String> = emptyList(),
        val runtimeMinutes: Int? = null,
        val loading: Boolean = false,
        val episodeLine: String? = "S1 E2 · Episode One",
    )

    private fun show(details: Details = Details()) =
        compose.setContent {
            StationBreakTheme {
                ProgramInfoOverlay(
                    channel = "07 Test Channel",
                    title = "Test Show",
                    episodeLine = details.episodeLine,
                    slot = "8:00 – 8:30 PM",
                    overview = details.overview,
                    year = details.year,
                    genres = details.genres,
                    runtimeMinutes = details.runtimeMinutes,
                    loading = details.loading,
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
        show(Details(episodeLine = null))
        tag(ProgramInfoTags.TITLE).assertIsDisplayed()
        tag(ProgramInfoTags.EPISODE).assertDoesNotExist()
        tag(ProgramInfoTags.SLOT).assertIsDisplayed()
    }

    @Test
    fun showsLoading() {
        show(Details(loading = true))
        tag(ProgramInfoTags.LOADING).assertIsDisplayed()
        tag(ProgramInfoTags.TITLE).assertIsDisplayed()
        tag(ProgramInfoTags.OVERVIEW).assertDoesNotExist()
    }

    @Test
    fun showsAllDetails() {
        show(
            Details(
                overview = "A test overview.",
                year = 1999,
                genres = listOf("Drama", "Comedy"),
                runtimeMinutes = 45,
            ),
        )
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
        show(
            Details(
                overview = "A test overview. ".repeat(40),
                year = 1999,
                genres = listOf("Drama"),
                runtimeMinutes = 45,
            ),
        )
        tag(ProgramInfoTags.CLOSE_HINT).assertIsDisplayed()
    }

    @Test
    fun theWholePanelIsOneNodeForAScreenReader() {
        show(Details(overview = "A test overview."))
        // In the merged tree (the default) the title and the slot are read from the same node.
        val title = compose.onNodeWithText("Test Show").fetchSemanticsNode()
        val slot = compose.onNodeWithText("8:00 – 8:30 PM").fetchSemanticsNode()
        assertEquals(title.id, slot.id)
        val texts = title.config[SemanticsProperties.Text].map { it.text }
        assertTrue(texts.toString(), "Test Show" in texts && "8:00 – 8:30 PM" in texts)
        compose.onAllNodesWithText("A test overview.").assertCountEquals(1)
    }

    @Test
    fun aScreenReaderAnnouncesThePanelAsItOpens() {
        show()
        val panel = compose.onNodeWithText("Test Show").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, panel.config[SemanticsProperties.LiveRegion])
    }
}
