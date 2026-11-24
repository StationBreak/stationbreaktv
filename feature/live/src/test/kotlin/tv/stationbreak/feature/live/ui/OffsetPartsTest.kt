// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The split of an offset into hours, minutes and seconds that the banner words (zero units are dropped there). */
class OffsetPartsTest {
    private fun parts(seconds: Long) = OffsetParts.of(seconds * 1_000)

    @Test
    fun zero() = assertEquals(OffsetParts(0, 0, 0), parts(0))

    @Test
    fun oneSecond() = assertEquals(OffsetParts(0, 0, 1), parts(1))

    @Test
    fun tenSeconds() = assertEquals(OffsetParts(0, 0, 10), parts(10))

    @Test
    fun fiftyNineSeconds() = assertEquals(OffsetParts(0, 0, 59), parts(59))

    @Test
    fun aWholeMinuteHasNoSeconds() = assertEquals(OffsetParts(0, 1, 0), parts(60))

    @Test
    fun minutePlusSeconds() = assertEquals(OffsetParts(0, 1, 10), parts(70))

    @Test
    fun twoMinutes() = assertEquals(OffsetParts(0, 2, 0), parts(120))

    @Test
    fun anHourHasNoMinutesOrSeconds() = assertEquals(OffsetParts(1, 0, 0), parts(3_600))

    @Test
    fun anHourAndSeconds() = assertEquals(OffsetParts(1, 0, 10), parts(3_610))

    @Test
    fun hoursMinutesAndSeconds() = assertEquals(OffsetParts(1, 2, 10), parts(3_730))

    @Test
    fun roundsToTheNearestSecondAndNeverGoesNegative() {
        assertEquals(OffsetParts(0, 0, 10), OffsetParts.of(9_600))
        assertEquals(OffsetParts(0, 0, 9), OffsetParts.of(9_400))
        assertEquals(OffsetParts(0, 0, 0), OffsetParts.of(-5_000))
    }
}
