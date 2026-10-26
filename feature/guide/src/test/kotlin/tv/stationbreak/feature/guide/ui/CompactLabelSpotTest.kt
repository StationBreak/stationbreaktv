// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the floating title of a focused compact cell goes (spec 17): beside the cell on the side with more room, never
 * wider than that room, inside the time columns, and never over the cell (or its focus ring, drawn inside the cell).
 */
class CompactLabelSpotTest {
    // A 720 px viewport from 1000 to 1720 on the row; a gap of 4 px between the cell and its label.
    private val left = 1000
    private val right = 1720
    private val gap = 4

    private fun assertBesideTheCell(
        spot: LabelSpot,
        width: Int,
        cellLeft: Int,
        cellRight: Int,
    ) {
        val x = spot.x(width)
        assertTrue("inside the viewport: $x", x >= left && x + width <= right)
        assertTrue("clear of the cell: $x..${x + width}", x + width <= cellLeft - gap || x >= cellRight + gap)
    }

    @Test
    fun aCellAtTheLeftEdgeHasItsLabelOnTheRight() {
        val spot = compactLabelSpot(cellLeft = 1000, cellRight = 1040, viewportLeft = left, viewportRight = right, gap)
        assertEquals(right - 1044, spot.room)
        assertEquals(1044, spot.x(200))
        assertBesideTheCell(spot, 200, 1000, 1040)
    }

    @Test
    fun aCellAtTheRightEdgeHasItsLabelOnTheLeft() {
        val spot = compactLabelSpot(cellLeft = 1680, cellRight = 1720, viewportLeft = left, viewportRight = right, gap)
        assertEquals(1676 - left, spot.room)
        assertEquals(1676 - 200, spot.x(200))
        assertBesideTheCell(spot, 200, 1680, 1720)
    }

    @Test
    fun aLongTitleIsCappedToTheRoomOnItsSide() {
        // Mid-viewport, a little right of centre: the left has more room, and a title wider than it is cut to fit.
        val spot = compactLabelSpot(cellLeft = 1400, cellRight = 1440, viewportLeft = left, viewportRight = right, gap)
        assertEquals(1396 - left, spot.room)
        val width = spot.room // what a long title measures to once capped
        assertEquals(left, spot.x(width))
        assertBesideTheCell(spot, width, 1400, 1440)
    }

    @Test
    fun aCellThatBeganBeforeTheViewportStillGetsItsLabelOutsideIt() {
        // The cell pokes in from the left: its visible part is 1000 to 1010.
        val spot = compactLabelSpot(cellLeft = 970, cellRight = 1010, viewportLeft = left, viewportRight = right, gap)
        assertEquals(1014, spot.x(300))
        assertBesideTheCell(spot, 300, 970, 1010)
    }
}
