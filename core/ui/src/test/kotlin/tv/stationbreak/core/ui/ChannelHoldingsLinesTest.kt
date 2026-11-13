// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The names line of a channel's holdings (#757): comma-separated, then the count not listed. */
class ChannelHoldingsLinesTest {
    @Test
    fun namesAreCommaSeparatedAndTheCountFollows() {
        assertEquals("A, B, C + 31 more", namesLine(listOf("A", "B", "C"), "+ 31 more"))
    }

    @Test
    fun noCountLeavesJustTheNames() {
        assertEquals("A, B", namesLine(listOf("A", "B"), null))
    }

    @Test
    fun noNamesLeavesJustTheCount() {
        assertEquals("+ 3 more", namesLine(emptyList(), "+ 3 more"))
    }
}
