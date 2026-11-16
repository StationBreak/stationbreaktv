// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenRowTest {
    @Test
    fun aRowSpeaksItsReasonsAfterItsName() {
        val reason = "Fits, but stronger channels were picked for you"
        assertEquals(
            "9, Charlie, (Kids), Nothing to air today, $reason",
            spokenRow(9, "Charlie", "(Kids)", "Nothing to air today", reason),
        )
    }

    @Test
    fun missingPartsAreLeftOut() {
        assertEquals("Alpha", spokenRow(null, "Alpha", "", null, null))
        assertEquals("6, Alpha", spokenRow(6, "Alpha", "", null, null))
    }
}
