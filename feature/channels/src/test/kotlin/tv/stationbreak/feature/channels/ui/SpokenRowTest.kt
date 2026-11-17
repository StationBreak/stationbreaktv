// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenRowTest {
    // Stands in for the string resource the screen passes: a language words the join itself.
    private val comma: (String, String) -> String = { a, b -> "$a, $b" }
    private val sentence: (String, String) -> String = { a, b -> "$a. $b" }

    @Test
    fun aRowSpeaksItsReasonsAfterItsName() {
        val reason = "Fits, but it isn't in your lineup right now"
        assertEquals(
            "9, Charlie, (Kids), Nothing to air today, $reason",
            spokenRow(9, "Charlie", "(Kids)", "Nothing to air today", reason, comma),
        )
    }

    @Test
    fun missingPartsAreLeftOut() {
        assertEquals("Alpha", spokenRow(null, "Alpha", "", null, null, comma))
        assertEquals("6, Alpha", spokenRow(6, "Alpha", "", null, null, comma))
    }

    @Test
    fun spokenPartsJoinsThePresentPartsAndSpeaksOneAlone() {
        assertEquals("Switch on? Yes.", spokenParts("Switch on?", "Yes.", join = { a, b -> "$a $b" }))
        assertEquals("Watch", spokenParts(null, "Watch", join = sentence))
        assertEquals("Watch", spokenParts("Watch", null, join = sentence))
        assertEquals("", spokenParts(null, null, join = sentence))
        assertEquals("One. Two", spokenParts("One", "Two", join = sentence))
    }
}
