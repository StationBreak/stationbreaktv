// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.episodeInfo
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.id
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.info
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell

/**
 * Runs of short programmes (the guide's "Big B… Big B…" rows): which cells join a run, what the run is labelled, and
 * where its one label goes. On the reference screen the time columns are 780 dp for 120 minutes, 6.5 dp a minute.
 */
class ShortRunsTest {
    private val screen = DpSize(960.dp, 540.dp)
    private val geometry = GuideGeometry(screen, ScreenLayout.pictureRect(ScreenLayout.safeArea(screen)))
    private val minute = 60_000L
    private val origin = 1_700_000_000_000L - 1_700_000_000_000L % (30 * minute)

    private fun at(min: Long) = origin + min * minute

    private fun program(
        fromMin: Long,
        toMin: Long,
        info: ProgramInfo,
    ): GuideCell {
        val title = if (info.kind == ScheduleItemKind.Episode) info.seriesName ?: info.title else info.title
        return GuideCell(at(fromMin), at(toMin), CellKind.Program, title, info, id(1))
    }

    private fun short(
        fromMin: Long,
        toMin: Long,
    ) = program(fromMin, toMin, info(1, SHORT))

    private fun slate(
        fromMin: Long,
        toMin: Long,
    ) = GuideCell(at(fromMin), at(toMin), CellKind.Slate, null, null, null)

    /** Ten-minute shorts back to back from [fromMin]: 65 dp each, too narrow for the short's title. */
    private fun shorts(
        fromMin: Long,
        count: Int,
    ) = (0 until count).map { short(fromMin + it * 10L, fromMin + it * 10L + 10) }

    // A title 90 dp wide: cut in any cell under 115 dp (90 dp and the 25 dp the title is kept from the cell's edges).
    private val titleWidth = 90.dp

    private fun runsOf(cells: List<GuideCell>): List<CellRun> =
        shortRuns(cells) { cell ->
            runCut(CellPlace(cell.startMs, cell.endMs, origin, geometry).width) { titleWidth }
        }

    @Test
    fun theTitleIsCutWhereItWouldBeCutInTheCell() {
        // The cell keeps 12 dp from each edge for its title and its 0.5 dp inset on both sides.
        assertFalse(titleCut(115.dp) { 90.dp })
        assertTrue(titleCut(114.9.dp) { 90.dp })
        // Under the compact width there is no title at all, however short it is, and it is not measured.
        assertTrue(titleCut(47.dp) { error("a compact cell measures nothing") })
        assertFalse(titleCut(CompactBelow) { 1.dp })
    }

    @Test
    fun shortsOfOneTitleBackToBackAreOneRun() {
        val cells = shorts(60, 12)
        assertEquals(
            listOf(CellRun(first = 0, last = 11, title = SHORT, startMs = at(60), endMs = at(180))),
            runsOf(cells),
        )
        assertEquals(12, runsOf(cells).single().count)
    }

    @Test
    fun episodesOfOneSeriesAreOneRunUnderTheSeriesName() {
        val cells = (0 until 4).map { program(60L + it * 10, 70L + it * 10, episodeInfo(10 + it, SERIES, 1, it + 1)) }
        val run = runsOf(cells).single()
        assertEquals(SERIES, run.title)
        assertEquals(4, run.count)
    }

    @Test
    fun anotherProgrammeBreaksTheRun() {
        val cells = shorts(60, 3) + program(90, 100, info(2, "Another Short")) + shorts(100, 2)
        assertEquals(
            listOf(CellRun(0, 2, SHORT, at(60), at(90)), CellRun(4, 5, SHORT, at(100), at(120))),
            runsOf(cells),
        )
    }

    @Test
    fun aGapOrASlateBreaksTheRun() {
        val gap = shorts(60, 2) + shorts(85, 2)
        assertEquals(listOf(CellRun(0, 1, SHORT, at(60), at(80)), CellRun(2, 3, SHORT, at(85), at(105))), runsOf(gap))
        val slated = shorts(60, 2) + slate(80, 85) + shorts(85, 2)
        assertEquals(
            listOf(CellRun(0, 1, SHORT, at(60), at(80)), CellRun(3, 4, SHORT, at(85), at(105))),
            runsOf(slated),
        )
    }

    @Test
    fun aRunReachesTheEdgesOfTheRow() {
        // The first cell began before the origin and the last ends the row: both are in the run.
        val cells = listOf(short(-5, 5)) + shorts(5, 3)
        assertEquals(listOf(CellRun(0, 3, SHORT, at(-5), at(35))), runsOf(cells))
    }

    @Test
    fun aSingleCutCellIsNotARun() {
        val cells = listOf(program(60, 70, info(2, "A Movie"))) + short(70, 80) + program(80, 90, info(3, "Other"))
        assertEquals(emptyList<CellRun>(), runsOf(cells))
    }

    @Test
    fun cellsWideEnoughForTheTitleAreLeftAlone() {
        // 20 minutes is 130 dp: room for the 90 dp title, so each says it in full.
        val wide = (0 until 3).map { short(60L + it * 20, 80L + it * 20) }
        assertEquals(emptyList<CellRun>(), runsOf(wide))
        // A wide one in the middle splits the narrow ones either side; one narrow cell alone is no run.
        val mixed = shorts(60, 2) + short(80, 100) + shorts(100, 1)
        assertEquals(listOf(CellRun(0, 1, SHORT, at(60), at(80))), runsOf(mixed))
    }

    @Test
    fun theTitleIsMeasuredOnlyForCellsBesideOneOfTheSameTitle() {
        val asked = mutableListOf<Long>()
        val cells = listOf(program(60, 70, info(2, "A Movie"))) + shorts(70, 2) + program(90, 100, info(3, "Other"))
        shortRuns(cells) { cell ->
            asked += cell.startMs
            true
        }
        assertEquals(listOf(at(70), at(80)), asked)
    }

    @Test
    fun slatesBreaksAndUnavailableProgrammesNeverRun() {
        val unavailable = GuideCell(at(70), at(75), CellKind.Unavailable, null, null, id(1))
        val breaks = GuideCell(at(75), at(77), CellKind.Break, null, null, null)
        val cells = listOf(slate(60, 65), slate(65, 70), unavailable, unavailable.copy(at(75), at(80)), breaks)
        assertEquals(emptyList<CellRun>(), shortRuns(cells) { true })
    }

    @Test
    fun aCellInARunWritesNoTitleUnlessItIsFocused() {
        assertFalse(titleShown(compact = false, inRun = true, focused = false))
        assertTrue(titleShown(compact = false, inRun = true, focused = true))
        assertTrue(titleShown(compact = false, inRun = false, focused = false))
    }

    @Test
    fun aCellTooNarrowForTextIsBlankFocusedOrNot() {
        // A gap, a filler or a slate under the compact width: no title and no "…", only its fill.
        assertFalse(titleShown(compact = true, inRun = false, focused = false))
        assertFalse(titleShown(compact = true, inRun = false, focused = true))
        assertFalse(titleShown(compact = true, inRun = true, focused = true))
    }

    // The room a label needs in these tests: a count and some of the title, about what " · 12" and 23 dp come to.
    private val need = 50.dp

    private fun runPlace(
        cells: List<GuideCell>,
        needs: Dp = need,
    ) = RunPlace(shortRuns(cells) { true }.single(), cells, origin, geometry, needs)

    private fun assertDp(
        expected: Dp,
        actual: Dp,
    ) = assertEquals(expected.value, actual.value, 0.01f)

    private fun wide(minutes: Int): Dp = (780f * minutes / 120).dp

    @Test
    fun theLabelSpansTheRunInView() {
        val place = runPlace(shorts(60, 12))
        assertDp(0.dp, place.labelLeft(at(60), focusStartMs = null))
        assertDp(wide(120), place.labelRight(at(60), focusStartMs = null))
        assertTrue(place.labelShown(at(60), null))
    }

    @Test
    fun theLabelIsPinnedToThePartInViewWhenScrolled() {
        // The run is 60 to 180; the viewport at 120 shows its second half, from 390 dp of the run to its end.
        val place = runPlace(shorts(60, 12))
        assertDp(wide(60), place.labelLeft(at(120), null))
        assertDp(wide(120), place.labelRight(at(120), null))
        // Viewport at 0: only 60 to 120 of the run shows.
        assertDp(0.dp, place.labelLeft(at(0), null))
        assertDp(wide(60), place.labelRight(at(0), null))
    }

    @Test
    fun theLabelStepsAsideForTheFocusedCellToTheWiderSide() {
        val place = runPlace(shorts(60, 12))
        // Focus on the third short (80 to 90): the nine after it are wider than the two before.
        assertDp(wide(30), place.labelLeft(at(60), at(80)))
        assertDp(wide(120), place.labelRight(at(60), at(80)))
        // Focus on the tenth (150 to 160): the nine before it.
        assertDp(0.dp, place.labelLeft(at(60), at(150)))
        assertDp(wide(90), place.labelRight(at(60), at(150)))
        // Focus elsewhere on the row (or another row's cell at the same time) leaves the whole run.
        assertDp(0.dp, place.labelLeft(at(60), at(20)))
        assertDp(wide(120), place.labelRight(at(60), at(20)))
    }

    @Test
    fun onATieTheLabelTakesTheSideAfterTheFocusedCell() {
        val place = runPlace(shorts(60, 3))
        assertDp(wide(20), place.labelLeft(at(60), at(70)))
        assertDp(wide(30), place.labelRight(at(60), at(70)))
    }

    @Test
    fun theSideIsChosenFromThePartInView() {
        // The viewport at 120 shows 390 to 780 dp of the run. Focus on 130 to 140 (455 to 520 dp): 65 dp in view
        // before it, 260 after, so after it. Focus on 170 to 180 (715 to 780 dp): nothing in view after, so before.
        val place = runPlace(shorts(60, 12))
        assertDp(wide(80), place.labelLeft(at(120), at(130)))
        assertDp(wide(120), place.labelRight(at(120), at(130)))
        assertDp(wide(60), place.labelLeft(at(120), at(170)))
        assertDp(wide(110), place.labelRight(at(120), at(170)))
    }

    @Test
    fun aRunThatBeganBeforeTheOriginIsPlacedFromIt() {
        // The first short airs from 5 minutes before the origin: the run and that cell start at the origin.
        val cells = listOf(short(-5, 5)) + shorts(5, 3)
        val place = runPlace(cells)
        assertDp(0.dp, place.span.x)
        assertDp(wide(35), place.span.width)
        // Focused, its drawn 32.5 dp is what the label steps past.
        assertDp(wide(5), place.labelLeft(at(0), at(-5)))
    }

    @Test
    fun aCellRaisedToTheLeastWidthIsSteppedPastAtItsDrawnWidth() {
        // Two-minute cells are 13 dp of time, drawn 20 dp wide: the second (13 dp in) ends at 33 dp, not 26.
        val cells = (0 until 4).map { short(60L + it * 2, 62L + it * 2) }
        val place = runPlace(cells)
        assertDp(33.dp, place.labelLeft(at(60), at(62)))
    }

    @Test
    fun noLabelWhereTheRoomLeftIsUnderWhatItNeeds() {
        // Two 65 dp shorts: 130 dp less the 25 dp of insets leaves 105 dp, over the 50 needed.
        val place = runPlace(shorts(60, 2))
        assertTrue(place.labelShown(at(60), null))
        // Focus on the first leaves the second: 65 dp, 40.5 dp once inset. Under 50, so no label; over 40, a label.
        assertFalse(place.labelShown(at(60), at(60)))
        assertTrue(runPlace(shorts(60, 2), needs = 40.dp).labelShown(at(60), at(60)))
        // Scrolled so 7 minutes (45.5 dp) of the run show: too little for any.
        assertFalse(place.labelShown(at(73), null))
        // Room exactly what is needed is enough.
        assertTrue(runPlace(shorts(60, 2), needs = 105.dp).labelShown(at(60), null))
        assertFalse(runPlace(shorts(60, 2), needs = 105.5.dp).labelShown(at(60), null))
    }

    @Test
    fun chevronRoomOnlyWhereTheEdgeCellDrawsAChevron() {
        // The run is 60 to 180. With the viewport at 8 (to 128) the short from 120 crosses the right edge with 8
        // minutes (52 dp) in view, so it draws "›" and the label leaves room for it; at 5 (to 125) only 32.5 dp of it
        // shows, compact, no chevron, and no room is kept though the run goes on.
        val place = runPlace(shorts(60, 12))
        assertTrue(place.laterRoom(at(8), null))
        assertFalse(place.laterRoom(at(5), null))
        assertTrue(place.labelRight(at(5), null) == place.span.visibleRight(at(5)))
        // The same at the left edge: from 62 the short from 60 shows 52 dp and draws "‹"; from 65, 32.5 dp, none.
        assertTrue(place.earlierRoom(at(62), null))
        assertFalse(place.earlierRoom(at(65), null))
        // A focused edge cell draws no chevron, and the label is beside it, not at the edge.
        assertFalse(place.earlierRoom(at(62), at(60)))
        // No room where the run does not reach the edge at all.
        assertFalse(place.laterRoom(at(60), null))
    }

    private class CountingWidths {
        val asked = mutableListOf<String>()
        val widths =
            TitleWidths(
                measure = { text ->
                    asked += text
                    text.length * CHAR_PX
                },
                density = Density(1f),
            )
    }

    @Test
    fun rowRunsMarkTheCellsInARunAndMeasureEachTextOnce() {
        val counting = CountingWidths()
        val cells = listOf(program(60, 90, info(2, "A Movie"))) + shorts(90, 3) + program(120, 150, info(3, "Other"))
        val runs = rowRuns(cells, origin, geometry, counting.widths) { " · $it" }
        assertEquals(listOf(false, true, true, true, false), cells.indices.map { runs.inRun(it) })
        assertEquals(listOf(" · 3"), runs.counts)
        // "Test Short" is 70 dp here: the count's 28 dp and the least of the title, 23 dp.
        assertDp(28.dp + MinLabelTitle, runs.places.single().need)
        assertEquals(listOf(SHORT, " · 3"), counting.asked)
        // The grid keeps what it measured: a row paged back into view measures nothing again.
        rowRuns(cells, origin, geometry, counting.widths) { " · $it" }
        assertEquals(listOf(SHORT, " · 3"), counting.asked)
    }

    @Test
    fun aShortTitleNeedsOnlyItsOwnWidth() {
        val counting = CountingWidths()
        val cells = (0 until 3).map { program(60L + it * 5, 65L + it * 5, info(1, "Hi")) }
        val runs = rowRuns(cells, origin, geometry, counting.widths) { " · $it" }
        assertDp(28.dp + 14.dp, runs.places.single().need)
    }

    @Test
    fun episodesWithTheirSecondLineAreNotARun() {
        // Half-hour episodes of a series whose name is longer than its 195 dp cells: each keeps its own cut title
        // and its "S3 E4" line, as before runs.
        val name = "A Series With a Name Far Too Long to Fit Its Half-Hour Cell"
        val cells = (0 until 4).map { program(60L + it * 30, 90L + it * 30, episodeInfo(20 + it, name, 3, 4 + it)) }
        val runs = rowRuns(cells, origin, geometry, CountingWidths().widths) { " · $it" }
        assertEquals(emptyList<CellRun>(), runs.runs)
        assertFalse(runs.inRun(0))
        val width = CellPlace(cells[0].startMs, cells[0].endMs, origin, geometry).width
        assertEquals("S3 E4", secondLineOf(cells[0].info, width.value, 1f, RowHeight.value))
        // The same series in ten-minute slots, which have no second line, is one run.
        val short = (0 until 4).map { program(60L + it * 10, 70L + it * 10, episodeInfo(20 + it, name, 3, 4 + it)) }
        assertEquals(4, rowRuns(short, origin, geometry, CountingWidths().widths) { " · $it" }.runs.single().count)
        assertEquals(null, secondLineOf(short[0].info, wide(10).value, 1f, RowHeight.value))
    }

    private companion object {
        const val SHORT = "Test Short"
        const val SERIES = "Test Cartoons"
        const val CHAR_PX = 7
    }
}
