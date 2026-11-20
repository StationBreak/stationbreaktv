// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

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
            titleCut(CellPlace(cell.startMs, cell.endMs, origin, geometry).width) { titleWidth }
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

    private fun runPlace(cells: List<GuideCell>) = RunPlace(runsOf(cells).single(), cells, origin, geometry)

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
        assertTrue(place.continuesEarlier(at(120)))
        // Viewport at 0: only 60 to 120 of the run shows, and it runs on past the right edge.
        assertDp(0.dp, place.labelLeft(at(0), null))
        assertDp(wide(60), place.labelRight(at(0), null))
        assertTrue(place.continuesLater(at(0)))
    }

    @Test
    fun theLabelStepsAsideForTheFocusedCellToTheWiderSide() {
        val cells = shorts(60, 12)
        val place = runPlace(cells)
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
    fun noLabelWhereTooLittleOfTheRunIsLeft() {
        val place = runPlace(shorts(60, 2))
        assertTrue(place.labelShown(at(60), null))
        // Focus on one of two 65 dp cells leaves 65 dp: still room.
        assertTrue(place.labelShown(at(60), at(60)))
        // Scrolled so 7 minutes (45.5 dp) of the run show: under the compact width, so no label.
        assertFalse(place.labelShown(at(73), null))
        // Two 5-minute fillers (32.5 dp each): 65 dp together, but one focused leaves 32.5 dp.
        val fillers = listOf(short(60, 65), short(65, 70))
        val small = RunPlace(shortRuns(fillers) { true }.single(), fillers, origin, geometry)
        assertTrue(small.labelShown(at(60), null))
        assertFalse(small.labelShown(at(60), at(65)))
    }

    private companion object {
        const val SHORT = "Test Short"
        const val SERIES = "Test Cartoons"
    }
}
