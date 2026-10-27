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

/**
 * What a cell draws over its fill (spec 17): the focus ring and the chevrons. On the reference screen the time columns
 * are 720 dp for 90 minutes, 8 dp a minute, and every cell is inset 1 dp, so its own coordinates run from 0 to its
 * width less 2 dp.
 */
class CellMarksTest {
    private val geometry = GuideGeometry(DpSize(960.dp, 540.dp))
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

    @Test
    fun aCompactCellDrawsNothingButItsFill() {
        // Five minutes, and a 4-minute sliver at the left edge: no text, no tick, no chevrons; the gap between cells
        // is their divider.
        assertEquals(CellMarks(ring = null, earlier = false, later = false), place(70, 75).marks(at(0), false))
        assertEquals(CellMarks(ring = null, earlier = false, later = false), place(-26, 4).marks(at(0), false))
    }
}
