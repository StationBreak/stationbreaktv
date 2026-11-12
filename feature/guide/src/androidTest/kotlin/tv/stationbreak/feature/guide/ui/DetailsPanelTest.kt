// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.domain.lineup.ScheduleFixtures
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSummary
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.LocalGuideColors
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
    private val colors = mutableStateOf(GuideColors.Dark)
    private val height = mutableStateOf(PANEL_HEIGHT_DP)

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
                CompositionLocalProvider(LocalGuideColors provides colors.value) {
                    DetailsPanel(panel, nowOf = { 0L }, modifier = Modifier.size(PANEL_WIDTH_DP.dp, height.value.dp))
                }
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
    fun anEpisodeOnNowKeepsItsTimeRowAndProgressBarInsideThePanel() {
        val info = ScheduleFixtures.episodeInfo(1, "Test Series", 1, 19)
        showOnNow(info, "Test Series")
        val panelBottom = compose.onNodeWithTag(GuideTags.PANEL).getBoundsInRoot().bottom
        val always =
            listOf(
                GuideTags.PANEL_CHANNEL,
                GuideTags.PANEL_TITLE,
                GuideTags.PANEL_EPISODE,
                GuideTags.PANEL_TIME,
                GuideTags.PANEL_PROGRESS,
            )
        for (tag in always) assertShownInside(tag, panelBottom)
        // By design the meta line is dropped whole when the episode line leaves no room for it; if it is drawn at
        // all, it must end inside the panel.
        if (isDisplayed(GuideTags.PANEL_META)) assertShownInside(GuideTags.PANEL_META, panelBottom)
    }

    @Test
    fun aMovieOnNowShowsItsMetaLineWithTheTimeRowAndProgressBar() {
        showOnNow(ScheduleFixtures.info(1, "Test Movie 1"), "Test Movie 1")
        val panelBottom = compose.onNodeWithTag(GuideTags.PANEL).getBoundsInRoot().bottom
        for (tag in listOf(GuideTags.PANEL_META, GuideTags.PANEL_TIME, GuideTags.PANEL_PROGRESS)) {
            assertShownInside(tag, panelBottom)
        }
        assertEquals("2018 · 22 min · Animation, Family", text(GuideTags.PANEL_META))
    }

    private fun showOnNow(
        info: ProgramInfo,
        title: String,
    ) {
        val cell = GuideCell(-MIN, 29 * MIN, CellKind.Program, title, info, null)
        val item = ItemDetails(ItemId("item-fit"), null, 2018, listOf("Animation", "Family"), 22.minutes)
        show(CellDetails(cell, 7, "Seven", item, loading = false))
        compose.mainClock.advanceTimeBy(SETTLED_MS)
    }

    private fun isDisplayed(tag: String): Boolean {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        return runCatching { node.assertIsDisplayed() }.isSuccess
    }

    private fun assertShownInside(
        tag: String,
        panelBottom: Dp,
    ) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        node.assertIsDisplayed()
        val bottom = node.getBoundsInRoot().bottom
        assertTrue("$tag ends at $bottom, panel at $panelBottom", bottom <= panelBottom)
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

    private fun rich(
        logo: ImageBitmap? = null,
        overview: String = "A description. ".repeat(40),
    ): CellDetails {
        val item =
            ItemDetails(
                ItemId("item-rich"),
                overview,
                2018,
                listOf("Animation", "Family"),
                22.minutes,
                officialRating = "TV-PG",
                criticRating = 68,
                communityRating = 6.6,
                media = MediaSummary(1920, 1080, 6, hasSubtitles = true),
            )
        val cell = GuideCell(0, 30 * MIN, CellKind.Program, "Program 0", null, null)
        return CellDetails(cell, 7, "Seven", item, loading = false, logo = logo)
    }

    private fun tag(index: Int) = compose.onNodeWithTag("${GuideTags.PANEL_TAG}$index", useUnmergedTree = true)

    private fun tagText(index: Int): String =
        tag(index)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    @Test
    fun theTagsFollowTheMetaLineInTheSetOrderAndOmitWhatIsUnknown() {
        show(rich())
        compose.mainClock.advanceTimeBy(SETTLED_MS)

        assertEquals(
            listOf("TV-PG", "HD", "5.1", "CC", "Critics 68%", "Audience 6.6"),
            (0..5).map(::tagText),
        )
        val meta = meta().getBoundsInRoot()
        val tags = compose.onNodeWithTag(GuideTags.PANEL_TAGS, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("the tags (${tags.top}) sit below the meta line (${meta.bottom})", tags.top >= meta.bottom)

        // An item with only some facts has only those tags, in the same order.
        val partial =
            ItemDetails(ItemId("p"), null, null, emptyList(), null, officialRating = "NR", communityRating = 7.0)
        show(withItem(partial))
        assertEquals(listOf("NR", "Audience 7.0"), (0..1).map(::tagText))
        compose.onAllNodesWithTag("${GuideTags.PANEL_TAG}2", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun anItemWithNoFactsHasNoTagRow() {
        show(withItem(ItemDetails(ItemId("bare"), "About", 2018, listOf("Drama"), null)))
        compose.onAllNodesWithTag(GuideTags.PANEL_TAGS, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun theDescriptionTakesEveryWholeLineTheHeightLeftHoldsAndEndsInAnEllipsis() {
        height.value = 300
        show(rich(overview = "A description. ".repeat(400)))
        compose.mainClock.advanceTimeBy(SETTLED_MS)

        val layouts = mutableListOf<TextLayoutResult>()
        overview()
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        val layout = layouts.single()
        assertTrue("only ${layout.lineCount} lines: the description is still capped", layout.lineCount > 2)
        assertTrue("the last line is cut with an ellipsis", layout.isLineEllipsized(layout.lineCount - 1))
        assertOverviewEndsInsidePanel()
    }

    private fun art(
        width: Int,
        height: Int,
    ): ImageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

    @Test
    fun aLogoStandsForTheTitleAndTheTitleStaysItsDescription() {
        show(rich(logo = art(600, 300)))
        compose.mainClock.advanceTimeBy(SETTLED_MS)

        compose.onNodeWithTag(GuideTags.PANEL_LOGO, useUnmergedTree = true).assertIsDisplayed()
        val title = compose.onNodeWithTag(GuideTags.PANEL_TITLE, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf("Program 0"), title.config[SemanticsProperties.ContentDescription])
        // No text title is drawn: the picture is it.
        compose.onAllNodesWithText("Program 0", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aTallLogoIsScaledToFortySixDpAndAWideOneToSeventyPercentOfThePanel() {
        show(rich(logo = art(600, 300)))
        val tall = compose.onNodeWithTag(GuideTags.PANEL_LOGO, useUnmergedTree = true).getBoundsInRoot()
        assertEquals(46f, tall.height.value, 0.5f)
        assertEquals(92f, tall.width.value, 0.5f)
        assertEquals("left-aligned", 0f, tall.left.value, 0.5f)

        show(rich(logo = art(4000, 100)))
        val wide = compose.onNodeWithTag(GuideTags.PANEL_LOGO, useUnmergedTree = true).getBoundsInRoot()
        assertEquals(PANEL_WIDTH_DP * 0.7f, wide.width.value, 0.5f)
        assertTrue("a wide logo is shorter than the cap (${wide.height})", wide.height.value < 46f)
    }

    @Test
    fun aLogoIsNeverScaledUp() {
        show(rich(logo = art(40, 20)))
        val small = compose.onNodeWithTag(GuideTags.PANEL_LOGO, useUnmergedTree = true).getBoundsInRoot()
        // At its own size in dp, whatever the density, which is at most 40 px wide.
        assertTrue("${small.width} dp wide", small.width.value <= 40f)
    }

    @Test
    fun inLightTheLogoSitsOnADarkPlateInsideTheSameBox() {
        colors.value = GuideColors.Light
        show(rich(logo = art(600, 300)))
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        val box = compose.onNodeWithTag(GuideTags.PANEL_TITLE, useUnmergedTree = true).getBoundsInRoot()
        val logo = compose.onNodeWithTag(GuideTags.PANEL_LOGO, useUnmergedTree = true).getBoundsInRoot()
        // The plate (the box) is at most 46 dp tall with the logo 6 dp inside it all round.
        assertTrue("the box is ${box.height} tall", box.height.value <= 46.5f)
        assertEquals(6f, logo.left.value - box.left.value, 0.5f)
        assertEquals(6f, logo.top.value - box.top.value, 0.5f)
    }

    private fun metaTop() = meta().getBoundsInRoot().top.value

    private fun tagsTop() = tag(0).getBoundsInRoot().top.value

    @Test
    fun theLinesBelowTheTitleDoNotMoveWhenALogoTakesItsPlace() {
        for (scheme in listOf(GuideColors.Dark, GuideColors.Light)) {
            colors.value = scheme
            panel.value = null
            show(rich())
            compose.mainClock.advanceTimeBy(SETTLED_MS)
            val metaWithText = metaTop()
            val tagsWithText = tagsTop()

            // The same cell gets its logo, of three shapes: the title block is 46 dp tall for each, plate or not.
            for (logo in listOf(art(600, 300), art(4000, 100), art(40, 20))) {
                show(rich(logo = logo))
                compose.mainClock.advanceTimeBy(SETTLED_MS)
                compose.onNodeWithTag(GuideTags.PANEL_LOGO, useUnmergedTree = true).assertIsDisplayed()
                assertEquals("meta line, ${logo.width} px logo", metaWithText, metaTop(), 0.5f)
                assertEquals("tags, ${logo.width} px logo", tagsWithText, tagsTop(), 0.5f)
            }

            // And a cell shown with its logo from the first frame has them in the same place.
            panel.value = null
            show(rich(logo = art(600, 300)))
            assertEquals("first shown with a logo", metaWithText, metaTop(), 0.5f)
        }
    }

    @Test
    fun aLogoThatArrivesForTheCellOnShowFadesInOverTheTextTitle() {
        show(rich())
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        assertEquals("Program 0", text(GuideTags.PANEL_TITLE))

        show(rich(logo = art(600, 300)))
        compose.mainClock.advanceTimeBy(60)
        // Mid-fade: the logo is the title node, and the text under it is not a second one.
        compose.onAllNodesWithTag(GuideTags.PANEL_TITLE, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag(GuideTags.PANEL_LOGO, useUnmergedTree = true).assertExists()

        compose.mainClock.advanceTimeBy(200)
        compose.onAllNodesWithText("Program 0", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag(GuideTags.PANEL_LOGO, useUnmergedTree = true).assertIsDisplayed()
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
