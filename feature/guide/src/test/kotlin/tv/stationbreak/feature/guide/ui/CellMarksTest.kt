// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

    @Test
    fun aCompactCellDrawsNothingButItsFill() {
        // Five minutes, and a 4-minute sliver at the left edge: no text, no tick, no chevrons; the gap between cells
        // is their divider.
        assertEquals(CellMarks(ring = null, earlier = false, later = false), place(70, 75).marks(at(0), false))
        assertEquals(CellMarks(ring = null, earlier = false, later = false), place(-26, 4).marks(at(0), false))
    }
}
