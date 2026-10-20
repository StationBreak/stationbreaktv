// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Test

class StandardOffsetTest {
    private fun minutes(ms: Int) = standardOffsetMinutes(ms)

    private fun hours(h: Double) = (h * 3_600_000).toInt()

    @Test
    fun wholeAndQuarterHourZonesAreKept() {
        assertEquals(0, minutes(0))
        assertEquals(-300, minutes(hours(-5.0)))
        assertEquals(330, minutes(hours(5.5)))
        assertEquals(345, minutes(hours(5.75)))
        assertEquals(-210, minutes(hours(-3.5)))
    }

    @Test
    fun otherOffsetsRoundDownToAQuarterHour() {
        assertEquals(0, minutes(7 * 60_000))
        assertEquals(15, minutes(29 * 60_000))
        // Floor, so a negative offset goes to the more negative quarter hour.
        assertEquals(-15, minutes(-1 * 60_000))
        assertEquals(-30, minutes(-16 * 60_000))
        assertEquals(-60, minutes(-46 * 60_000))
    }

    @Test
    fun theDeviceZoneGivesAValidOffset() {
        assertEquals(0, standardOffsetMinutes() % 15)
    }
}
