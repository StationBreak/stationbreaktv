// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which visible band the focused-row tint covers. The rect itself is drawn, not composed. */
class FocusedRowTintTest {
    @Test
    fun theBandIsTheFocusedRowInsideTheWindow() {
        assertEquals(0, focusedRowBand(row = 2, firstVisibleRow = 2, rowCount = 8))
        assertEquals(3, focusedRowBand(row = 5, firstVisibleRow = 2, rowCount = 8))
    }

    @Test
    fun aRowOutsideTheWindowIsNotTinted() {
        assertNull(focusedRowBand(row = 1, firstVisibleRow = 2, rowCount = 8))
        assertNull(focusedRowBand(row = 8, firstVisibleRow = 2, rowCount = 8))
    }

    @Test
    fun theLastShortWindowDoesNotTintAnEmptyBand() {
        // Six slots, but only the two rows at the end of the list are real.
        assertEquals(1, focusedRowBand(row = 8, firstVisibleRow = 7, rowCount = 9))
        assertNull(focusedRowBand(row = 9, firstVisibleRow = 7, rowCount = 9))
    }
}
