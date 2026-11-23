// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumberBandTest {
    @Test
    fun theBandsRunFromTheEnginesYoungSplit() {
        assertEquals(1..33, NumberBand.Tots.numbers)
        assertEquals(34..66, NumberBand.Kids.numbers)
        assertEquals(67..99, NumberBand.Teens.numbers)
        assertEquals(100, NumberBand.GrownUps.numbers.first)
    }

    @Test
    fun everyBoundaryLandsInItsBand() {
        assertEquals(NumberBand.Tots, bandOf(1))
        assertEquals(NumberBand.Tots, bandOf(33))
        assertEquals(NumberBand.Kids, bandOf(34))
        assertEquals(NumberBand.Kids, bandOf(66))
        assertEquals(NumberBand.Teens, bandOf(67))
        assertEquals(NumberBand.Teens, bandOf(99))
        assertEquals(NumberBand.GrownUps, bandOf(100))
        assertEquals(NumberBand.GrownUps, bandOf(599))
        assertEquals(NumberBand.GrownUps, bandOf(600))
        assertEquals(NumberBand.GrownUps, bandOf(999))
        assertEquals(NumberBand.GrownUps, bandOf(Int.MAX_VALUE))
    }

    @Test
    fun noNumberOrOneBelowOneHasNoBand() {
        assertNull(bandOf(null))
        assertNull(bandOf(0))
        assertNull(bandOf(-5))
    }
}
