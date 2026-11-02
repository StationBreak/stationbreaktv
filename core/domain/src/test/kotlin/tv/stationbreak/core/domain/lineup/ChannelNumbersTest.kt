// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.Band
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelNumbersTest {
    @Test
    fun theBandsAreThePlannersAndTheEditorsOwn() {
        assertEquals(1..999, ChannelNumbers.all)
        assertEquals(1..99, ChannelNumbers.kids)
        assertEquals(100, Band.General.numbers.first)
    }

    @Test
    fun aNumberIsFirstOfferedAtTheStartOfTheRightBand() {
        assertEquals(100, ChannelNumbers.startFor(kid = false))
        assertEquals(1, ChannelNumbers.startFor(kid = true))
    }
}
