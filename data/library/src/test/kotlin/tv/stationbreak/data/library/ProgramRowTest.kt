// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 23: the names of a program row never reach a log. */
class ProgramRowTest {
    @Test
    fun namesNeverReachToString() {
        val text = ProgramRow("id-1", "Episode", "Secret Item", "Secret Series", 1, 2).toString()
        assertFalse(text, text.contains("Secret"))
        assertTrue(text, text.contains("name=<name>") && text.contains("seriesName=<name>"))
        assertTrue(ProgramRow("id-1", "Movie", "Secret Item", null, null, null).toString().contains("seriesName=null"))
    }
}
