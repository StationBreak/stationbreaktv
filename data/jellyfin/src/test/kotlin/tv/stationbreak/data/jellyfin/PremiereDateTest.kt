// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 09 Unit 1: the premiere date is the UTC calendar date of `PremiereDate`, as `YYYY-MM-DD` (rules format v1). */
class PremiereDateTest {
    @Test
    fun aTimeNearMidnightWithAnOffsetGivesTheUtcDate() {
        assertEquals("2001-03-05", premiereDateOf("2001-03-04T23:30:00-05:00"))
        assertEquals("2001-03-03", premiereDateOf("2001-03-04T00:30:00+05:30"))
    }

    @Test
    fun anInstantAtMidnightStaysOnItsDay() {
        assertEquals("2020-01-01", premiereDateOf("2020-01-01T00:00:00Z"))
        assertEquals("2019-12-31", premiereDateOf("2019-12-31T23:59:59.9999999Z"))
    }

    @Test
    fun jellyfinsSevenFractionDigitsAreRead() {
        assertEquals("1999-12-31", premiereDateOf("1999-12-31T00:00:00.0000000Z"))
    }

    @Test
    fun aDateBefore1970Works() {
        assertEquals("1969-07-20", premiereDateOf("1969-07-20T20:17:00Z"))
        assertEquals("1923-01-01", premiereDateOf("1923-01-01T00:00:00.0000000Z"))
    }

    @Test
    fun anAbsentDateHasNoPremiereDate() {
        assertNull(premiereDateOf(null))
    }

    @Test
    fun aMalformedDateHasNoPremiereDate() {
        assertNull(premiereDateOf("not a date"))
        assertNull(premiereDateOf(""))
        assertNull(premiereDateOf("2001-13-45T00:00:00Z"))
        assertNull(premiereDateOf("2001-03-04"))
    }

    @Test
    fun aYearOutsideTheFormatsRangeHasNoPremiereDateAndNeverThrows() {
        assertNull(premiereDateOf("+999999999-12-31T23:59:59-18:00"))
        assertNull(premiereDateOf("+10000-01-01T00:00:00Z"))
        assertNull(premiereDateOf("9999-12-31T23:59:59-05:00"), "the UTC year is 10000")
        assertNull(premiereDateOf("-0001-06-01T00:00:00Z"))
        assertNull(premiereDateOf("0000-06-01T00:00:00Z"))
        assertNull(premiereDateOf("0001-01-01T00:00:00+01:00"), "the UTC year is 0")
        assertEquals("0001-01-01", premiereDateOf("0001-01-01T00:00:00Z"))
        assertEquals("9999-12-31", premiereDateOf("9999-12-31T23:59:59Z"))
    }

    @Test
    fun aTimeWithNoOffsetIsReadAsUtc() {
        assertEquals("2001-03-04", premiereDateOf("2001-03-04T00:00:00"))
        assertEquals("2001-03-04", premiereDateOf("2001-03-04T23:59:59.1234567"))
    }

    @Test
    fun theItemCarriesItsPremiereDate() {
        val dto = ItemDto(id = "i1", premiereDate = "2001-03-04T23:30:00-05:00")

        assertEquals("2001-03-05", toMediaItem(dto).premiereDate)
        assertNull(toMediaItem(ItemDto(id = "i2")).premiereDate)
    }
}
