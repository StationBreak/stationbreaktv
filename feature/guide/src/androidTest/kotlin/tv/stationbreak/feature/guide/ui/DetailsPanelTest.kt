// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.lineup.ScheduleFixtures
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.ui.StationBreakTheme
import tv.stationbreak.feature.guide.CellDetails
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import kotlin.time.Duration.Companion.minutes

/**
 * The details panel while the focus moves (spec 17): the title and the time follow every move at once, and the
 * description waits until the focus has rested, so a held key does not lay a few lines of text out on every step.
 */
@RunWith(AndroidJUnit4::class)
class DetailsPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val panel = MutableStateFlow<CellDetails?>(null)

    private fun details(
        startMin: Long,
        overview: String?,
    ): CellDetails {
        val cell = GuideCell(startMin * MIN, (startMin + 30) * MIN, CellKind.Program, "Program $startMin", null, null)
        val item = ItemDetails(ItemId("item-$startMin"), overview, null, emptyList(), null)
        return CellDetails(cell, 7, "Seven", item, loading = false)
    }

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StationBreakTheme {
                DetailsPanel(panel, nowOf = { 0L }, modifier = Modifier.size(PANEL_WIDTH_DP.dp, PANEL_HEIGHT_DP.dp))
            }
        }
    }

    private fun text(tag: String): String =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    private fun show(details: CellDetails) {
        panel.value = details
        compose.mainClock.advanceTimeByFrame()
    }

    private fun meta() = compose.onNodeWithTag(GuideTags.PANEL_META, useUnmergedTree = true)

    private fun withItem(item: ItemDetails?): CellDetails {
        val cell = GuideCell(0, 30 * MIN, CellKind.Program, "Program 0", null, null)
        return CellDetails(cell, 7, "Seven", item, loading = false)
    }

    @Test
    fun theMetaLineShowsWithTheItemWithoutWaitingForTheDescription() {
        val item = ItemDetails(ItemId("item-meta"), "About", 2018, listOf("Animation", "Family", "Kids"), 7.minutes)
        show(withItem(item))
        assertEquals("2018 · 7 min · Animation, Family", text(GuideTags.PANEL_META))
        overview().assertDoesNotExist()
        assertEquals("Program 0", text(GuideTags.PANEL_TITLE))
        compose.onNodeWithTag(GuideTags.PANEL_TIME, useUnmergedTree = true).assertExists()
    }

    @Test
    fun noMetaLineWithoutAnItemOrWithAnEmptyOne() {
        show(withItem(null))
        meta().assertDoesNotExist()
        show(withItem(ItemDetails(ItemId("item-bare"), "About", null, emptyList(), null)))
        meta().assertDoesNotExist()
        assertEquals("Program 0", text(GuideTags.PANEL_TITLE))
        compose.onNodeWithTag(GuideTags.PANEL_TIME, useUnmergedTree = true).assertExists()
    }

    @Test
    fun everyLineOfAnEpisodeOnNowWithAMetaLineEndsInsideThePanel() {
        val info = ScheduleFixtures.episodeInfo(1, "Test Series", 1, 19)
        val cell = GuideCell(-MIN, 29 * MIN, CellKind.Program, "Test Series", info, null)
        val item = ItemDetails(ItemId("item-fit"), null, 2018, listOf("Animation", "Family"), 22.minutes)
        show(CellDetails(cell, 7, "Seven", item, loading = false))
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        val panelBottom = compose.onNodeWithTag(GuideTags.PANEL).getBoundsInRoot().bottom
        for (tag in listOf(
            GuideTags.PANEL_CHANNEL,
            GuideTags.PANEL_TITLE,
            GuideTags.PANEL_EPISODE,
            GuideTags.PANEL_META,
            GuideTags.PANEL_TIME,
            GuideTags.PANEL_PROGRESS,
        )) {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            node.assertIsDisplayed()
            val bottom = node.getBoundsInRoot().bottom
            assertTrue("$tag ends at $bottom, panel at $panelBottom", bottom <= panelBottom)
        }
    }

    private fun overview() = compose.onNodeWithTag(GuideTags.PANEL_OVERVIEW, useUnmergedTree = true)

    @Test
    fun heldKeyShowsEachTitleAtOnceAndTheDescriptionOnlyOnceItRests() {
        show(details(0, "About zero"))
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        assertEquals("About zero", text(GuideTags.PANEL_OVERVIEW))

        // A held key: a move every 50 ms. Each title shows on the frame of its move; no description is laid out.
        for (start in listOf(30L, 60L, 90L)) {
            show(details(start, "About $start"))
            assertEquals("Program $start", text(GuideTags.PANEL_TITLE))
            overview().assertDoesNotExist()
            compose.mainClock.advanceTimeBy(STEP_MS)
            overview().assertDoesNotExist()
        }

        // The key let go: the last cell's description, once the focus has rested on it.
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        assertEquals("About 90", text(GuideTags.PANEL_OVERVIEW))
    }

    @Test
    fun backOnTheCellTheFocusRestedOnShowsItsDescriptionAtOnce() {
        // Rested on A, a step to B and straight back: A is the cell the focus last rested on, so no second wait.
        show(details(0, "About zero"))
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        show(details(30, "About 30"))
        overview().assertDoesNotExist()
        compose.mainClock.advanceTimeBy(STEP_MS)
        show(details(0, "About zero"))
        assertEquals("About zero", text(GuideTags.PANEL_OVERVIEW))
        // And B, never rested on, still waits after that.
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        show(details(30, "About 30"))
        overview().assertDoesNotExist()
    }

    @Test
    fun descriptionThatArrivesAfterTheFocusRestedShowsAtOnce() {
        // The dwell's fetch: the same cell, its details come later. The focus has long rested, so no second wait.
        show(details(0, null))
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        overview().assertDoesNotExist()
        show(details(0, "Fetched"))
        assertEquals("Fetched", text(GuideTags.PANEL_OVERVIEW))
    }

    @Test
    fun programThatLeftTheLibraryIsTitledUnavailableAndOneWithInfoKeepsItsTitle() {
        val gone = GuideCell(0, 30 * MIN, CellKind.Unavailable, null, null, ScheduleFixtures.id(1))
        show(CellDetails(gone, 7, "Seven", null, loading = false))
        assertEquals("Unavailable", text(GuideTags.PANEL_TITLE))
        show(details(30, null))
        assertEquals("Program 30", text(GuideTags.PANEL_TITLE))
    }

    private fun longTitled(
        episode: Boolean,
        overview: String,
    ): CellDetails {
        val long = "A very long title that needs a second line to be shown in full here"
        val info =
            if (episode) ScheduleFixtures.episodeInfo(1, long, 2, 3) else ScheduleFixtures.info(1, long)
        // On now (the progress bar shows) with a two-line title: the most the lines above the overview can take.
        val cell = GuideCell(-MIN, 30 * MIN, CellKind.Program, long, info, null)
        val item = ItemDetails(ItemId("item-long"), overview, null, emptyList(), null)
        return CellDetails(cell, 7, "Seven", item, loading = false)
    }

    private fun assertOverviewEndsInsidePanel() {
        val panelBounds = compose.onNodeWithTag(GuideTags.PANEL).getBoundsInRoot()
        val nodes = compose.onAllNodesWithTag(GuideTags.PANEL_OVERVIEW, useUnmergedTree = true).fetchSemanticsNodes()
        // With room for no whole line the overview is left out; otherwise it ends inside the panel.
        if (nodes.isNotEmpty()) {
            val bottom = overview().getBoundsInRoot().bottom
            assertTrue("overview ends at $bottom, panel at ${panelBounds.bottom}", bottom <= panelBounds.bottom)
        }
    }

    @Test
    fun aLongDescriptionEndsInsideThePanelAsTheButtonTakesTheFoot() {
        show(details(0, "A long description. ".repeat(30)))
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        overview().assertExists()
        assertOverviewEndsInsidePanel()
    }

    @Test
    fun aTwoLineTitleWithAProgressBarLeavesTheOverviewWholeLinesOrNone() {
        show(longTitled(episode = false, overview = "A long description. ".repeat(30)))
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        assertOverviewEndsInsidePanel()
    }

    @Test
    fun anEpisodeLineWithATwoLineTitleLeavesTheOverviewWholeLinesOrNone() {
        show(longTitled(episode = true, overview = "A long description. ".repeat(30)))
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        assertOverviewEndsInsidePanel()
    }

    private companion object {
        // The guide's panel on a 960 x 540 dp screen: the band left of the picture, less the menu row (a 40 dp button
        // and the gap above it).
        const val PANEL_WIDTH_DP = 502
        const val PANEL_HEIGHT_DP = 150

        const val MIN = 60_000L
        const val STEP_MS = 50L

        // Past the wait (about 150 ms), with a frame to spare.
        const val SETTLED_MS = 200L
    }
}
