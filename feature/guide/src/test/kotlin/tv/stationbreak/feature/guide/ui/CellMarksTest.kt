// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.ui.ScreenLayout

/**
 * What a cell draws over its fill (spec 17): the focus ring and the chevrons. On the reference screen the time columns
 * are 720 dp for 90 minutes, 8 dp a minute, and every cell is inset 1 dp, so its own coordinates run from 0 to its
 * width less 2 dp.
 */
class CellMarksTest {
    private val screen = DpSize(960.dp, 540.dp)
    private val geometry = GuideGeometry(screen, ScreenLayout.pictureRect(ScreenLayout.safeArea(screen)))
    private val minute = 60_000L
    private val origin = 1_700_000_000_000L - 1_700_000_000_000L % (30 * minute)

    private fun place(
        fromMin: Long,
        toMin: Long,
    ) = CellPlace(origin + fromMin * minute, origin + toMin * minute, origin, geometry)

    private fun at(min: Long) = origin + min * minute

    // Widths come from float arithmetic: equal to a hundredth of a dp.
    private fun assertDp(
        expected: Dp,
        actual: Dp,
    ) = assertEquals(expected.value, actual.value, 0.01f)

    @Test
    fun aFocusedCellThatRunsPastTheRightEdgeHasItsRingClosedThereAndNoChevron() {
        // From 60 to 120 minutes with the viewport on 0 to 90: 30 minutes (240 dp) of it show.
        val cell = place(60, 120)
        val focused = cell.marks(at(0), focused = true)
        val ring = checkNotNull(focused.ring)
        assertDp(0.dp, ring.start)
        // The ring's right side is drawn at the viewport's edge, inside the time columns (and so the safe area).
        assertDp(239.dp, ring.endInclusive)
        // The ring owns that edge: no "›" inside it.
        assertFalse(focused.later)

        val plain = cell.marks(at(0), focused = false)
        assertNull(plain.ring)
        assertTrue(plain.later)
    }

    @Test
    fun aFocusedCellThatBeganBeforeTheLeftEdgeHasItsRingClosedThereAndNoChevron() {
        // From 0 to 60 minutes with the viewport scrolled to 30: its second half (240 to 480 dp) shows.
        val cell = place(0, 60)
        val focused = cell.marks(at(30), focused = true)
        val ring = checkNotNull(focused.ring)
        assertDp(239.dp, ring.start)
        assertDp(478.dp, ring.endInclusive)
        assertFalse(focused.earlier)
        assertTrue(cell.marks(at(30), focused = false).earlier)
    }

    @Test
    fun aFocusedCellInsideTheViewportHasItsRingAllRound() {
        val ring = checkNotNull(place(30, 60).marks(at(0), focused = true).ring)
        assertDp(0.dp, ring.start)
        assertDp(238.dp, ring.endInclusive)
    }

    @Test
    fun aLongCellScrolledToASliverIsCompactThere() {
        // From 0 to 33 minutes, with the viewport scrolled to 30: 3 minutes (24 dp) of it show, too few for a title.
        val cell = place(0, 33)
        assertFalse(cell.compactIn(at(0)))
        assertTrue(cell.compactIn(at(30)))
        assertEquals(CellMarks(ring = null, earlier = false, later = false), cell.marks(at(30), false))
        // At its full width it has its title and its chevron as before.
        assertTrue(place(0, 120).marks(at(0), false).later)
    }

    private val sec = 1_000L

    private fun placeMs(
        fromMs: Long,
        toMs: Long,
    ) = CellPlace(origin + fromMs, origin + toMs, origin, geometry)

    @Test
    fun aCompactCellWithRoomForOneShowsAnEllipsisAcrossItsWidth() {
        // 8 dp a minute: 5 minutes is 40 dp, compact, with room for a "…" over all of it.
        val range = place(70, 75).ellipsisIn(at(60))
        assertDp(0.dp, range!!.start)
        assertDp(40.dp, range.endInclusive)
    }

    @Test
    fun theEllipsisNeedsAtLeast32DpOfTheCell() {
        // 4 minutes is exactly 32 dp, the least; a second short of it is not enough, a second over is.
        assertTrue(place(70, 74).ellipsisIn(at(60)) != null)
        assertTrue(placeMs(70 * minute, 74 * minute + sec).ellipsisIn(at(60)) != null)
        assertNull(placeMs(70 * minute, 74 * minute - sec).ellipsisIn(at(60)))
        // 3 minutes (24 dp) and the 20 dp minimum (a 2-minute cell) are too narrow even for it.
        assertNull(place(70, 73).ellipsisIn(at(60)))
        assertNull(place(70, 72).ellipsisIn(at(60)))
    }

    @Test
    fun aCellWithRoomForItsTitleHasNoEllipsis() {
        // 10 minutes is 80 dp, the least that has a title.
        assertNull(place(60, 70).ellipsisIn(at(60)))
        assertNull(place(60, 120).ellipsisIn(at(60)))
    }

    @Test
    fun aLongCellScrolledToASliverHasTheEllipsisInThePartInView() {
        // From 0 to 33 minutes with the viewport at 30: only 3 minutes (24 dp) show, too few even for a "…".
        assertNull(place(0, 33).ellipsisIn(at(30)))
        // From 0 to 35: 5 minutes (40 dp) show: the cell's last 40 dp, from 240 dp to its end at 280 dp.
        val range = place(0, 35).ellipsisIn(at(30))!!
        assertDp(240.dp, range.start)
        assertDp(280.dp, range.endInclusive)
        // The same at the other edge: a cell from 85 to 120 with the viewport at 0 (90 minutes) shows its first 5.
        val right = place(85, 120).ellipsisIn(at(0))!!
        assertDp(0.dp, right.start)
        assertDp(40.dp, right.endInclusive)
        // Before it is cut that far, its title shows instead.
        assertNull(place(0, 35).ellipsisIn(at(20)))
    }

    @Test
    fun aCompactCellDrawsNothingButItsFill() {
        // Five minutes, and a 4-minute sliver at the left edge: no text, no tick, no chevrons; the gap between cells
        // is their divider.
        assertEquals(CellMarks(ring = null, earlier = false, later = false), place(70, 75).marks(at(0), false))
        assertEquals(CellMarks(ring = null, earlier = false, later = false), place(-26, 4).marks(at(0), false))
    }
}
