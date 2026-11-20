// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A hint row read out as one line: the keys in words where the caps show arrows. */
class KeyHintTest {
    @Test
    fun aHintRowReadsItsPairsInOrder_withTheSpokenKeys() {
        val pairs =
            listOf(
                KeyHintPair("◂ ▸", "Change", spokenKey = "Left or right"),
                KeyHintPair("OK", "Next", round = true),
                KeyHintPair("Back", "Close"),
            )
        assertEquals("Left or right Change   OK Next   Back Close", spokenHint(pairs))
        assertEquals("", spokenHint(emptyList()))
    }
}
