// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixedPointTest {
    @Test
    fun log2OfPowersOfTwoIsExact() {
        assertEquals(65_536L, log2Q16(2))
        assertEquals(131_072L, log2Q16(4))
        assertEquals(196_608L, log2Q16(8))
        assertEquals(655_360L, log2Q16(1024))
    }

    @Test
    fun log2IsMonotonic() {
        var previous = log2Q16(1)
        for (x in 2L..5_000L) {
            val now = log2Q16(x)
            assertTrue(now >= previous, "log2 fell at $x")
            previous = now
        }
    }

    @Test
    fun specificityValues() {
        assertEquals(6_553_600L, specificityQ16(0))
        assertEquals(3_276_800L, specificityQ16(2))
        assertEquals(2_184_533L, specificityQ16(6))
    }
}
