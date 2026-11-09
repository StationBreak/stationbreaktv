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
 * are 780 dp for 90 minutes (the 84 dp channel column comes out of the 864 dp safe width), 8.67 dp a minute, and every
 * cell is inset 0.5 dp, so its own coordinates run from 0 to its width less 1 dp.
 */
class CellMarksTest {
    private val screen = DpSize(960.dp, 540.dp)
    private val geometry = GuideGeometry(screen, ScreenLayout.pictureRect(ScreenLayout.safeArea(screen)))
    private val minute = 60_000L

    // The width of [minutes] on the reference screen: the 864 dp safe width less the 84 dp column is 780 dp for
    // 90 minutes.
    private fun wide(minutes: Int): Dp = (780f * minutes / 90).dp

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
        // From 60 to 120 minutes with the viewport on 0 to 90: 30 minutes (260 dp) of it show.
        val cell = place(60, 120)
        val focused = cell.marks(at(0), focused = true)
        val ring = checkNotNull(focused.ring)
        assertDp(0.dp, ring.start)
        // The fill runs on past the viewport's edge to the screen's, as the cell's surface does; its text and chevron
        // stay inside the safe area.
        assertDp(wide(30) + geometry.bleed - 0.5.dp, ring.endInclusive)
        // The ring owns that edge: no "›" inside it.
        assertFalse(focused.later)

        val plain = cell.marks(at(0), focused = false)
        assertNull(plain.ring)
        assertTrue(plain.later)
    }

    @Test
    fun aFocusedCellThatBeganBeforeTheLeftEdgeHasItsRingClosedThereAndNoChevron() {
        // From 0 to 60 minutes with the viewport scrolled to 30: its second half (260 to 520 dp) shows.
        val cell = place(0, 60)
        val focused = cell.marks(at(30), focused = true)
        val ring = checkNotNull(focused.ring)
        assertDp(wide(30) - 0.5.dp, ring.start)
        assertDp(wide(60) - 1.dp, ring.endInclusive)
        assertFalse(focused.earlier)
        assertTrue(cell.marks(at(30), focused = false).earlier)
    }

    @Test
    fun aFocusedCellInsideTheViewportHasItsRingAllRound() {
        val ring = checkNotNull(place(30, 60).marks(at(0), focused = true).ring)
        assertDp(0.dp, ring.start)
        assertDp(wide(30) - 1.dp, ring.endInclusive)
    }

    @Test
    fun aLongCellScrolledToASliverIsCompactThere() {
        // From 0 to 33 minutes, with the viewport scrolled to 30: 3 minutes (26 dp) of it show, too few for a title.
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
        // 5 minutes is 43.3 dp, compact, with room for a "…" over all of it.
        val range = place(70, 75).ellipsisIn(at(60))
        assertDp(0.dp, range!!.start)
        assertDp(wide(5), range.endInclusive)
    }

    @Test
    fun theEllipsisNeedsAtLeast32DpOfTheCell() {
        // 32 dp, the least, is 221.5 seconds here: 3 minutes (26 dp) is short of it, 4 minutes (34.7 dp) is over, and
        // 222 seconds (32.07 dp) is enough where 221 (31.9 dp) is not.
        assertTrue(place(70, 75).ellipsisIn(at(60)) != null)
        assertTrue(place(70, 74).ellipsisIn(at(60)) != null)
        assertTrue(placeMs(70 * minute, 70 * minute + 222 * sec).ellipsisIn(at(60)) != null)
        assertNull(placeMs(70 * minute, 70 * minute + 221 * sec).ellipsisIn(at(60)))
        assertNull(place(70, 73).ellipsisIn(at(60)))
        // 2 minutes (17.3 dp) is too narrow even for it.
        assertNull(place(70, 72).ellipsisIn(at(60)))
    }

    @Test
    fun aCellWithRoomForItsTitleHasNoEllipsis() {
        // 6 minutes is 52 dp, over the 48 dp least that has a title; 5 minutes (43.3 dp) is compact.
        assertNull(place(60, 66).ellipsisIn(at(60)))
        assertFalse(place(60, 66).compactIn(at(60)))
        assertTrue(place(60, 65).compactIn(at(60)))
        assertNull(place(60, 120).ellipsisIn(at(60)))
    }

    @Test
    fun aLongCellScrolledToASliverHasTheEllipsisInThePartInView() {
        // From 0 to 33 minutes with the viewport at 30: only 3 minutes (26 dp) show, too few even for a "…".
        assertNull(place(0, 33).ellipsisIn(at(30)))
        // From 0 to 35: 5 minutes (43.3 dp) show: the cell's last 43.3 dp, from 260 dp to its end at 303 dp.
        val range = place(0, 35).ellipsisIn(at(30))!!
        assertDp(wide(30), range.start)
        assertDp(wide(35), range.endInclusive)
        // The same at the other edge: a cell from 85 to 120 with the viewport at 0 (90 minutes) shows its first 5.
        val right = place(85, 120).ellipsisIn(at(0))!!
        assertDp(0.dp, right.start)
        assertDp(wide(5), right.endInclusive)
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
