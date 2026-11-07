// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The meta line of the details panel: year, runtime and up to two genres, with no stray separator. */
class MetaLineTest {
    private val copy =
        MetaCopy(
            minutes = { "$it min" },
            hoursMinutes = { h, m -> "$h h $m min" },
            hours = { "$it h" },
        )

    @Test
    fun yearRuntimeAndTheFirstTwoGenres() {
        val genres = listOf("Animation", "Family", "Kids")
        assertEquals("2018 · 7 min · Animation, Family", metaLine(2018, 7.minutes, genres, copy))
    }

    @Test
    fun aRuntimeOverAnHourIsHoursAndMinutes() {
        assertEquals("1 h 40 min", metaLine(null, 100.minutes, emptyList(), copy))
    }

    @Test
    fun wholeHoursDropTheMinutes() {
        assertEquals("2 h", metaLine(null, 120.minutes, emptyList(), copy))
    }

    @Test
    fun nothingToSayIsNull() {
        assertNull(metaLine(null, null, emptyList(), copy))
    }

    @Test
    fun aLoneYearHasNoSeparator() {
        assertEquals("2018", metaLine(2018, null, emptyList(), copy))
    }

    @Test
    fun blankGenresCountAsNone() {
        assertNull(metaLine(null, null, listOf("", " "), copy))
    }

    @Test
    fun blankGenresAreSkippedBeforeTakingTwo() {
        assertEquals("Drama, Comedy", metaLine(null, null, listOf("", "Drama", "Comedy", "Kids"), copy))
    }

    @Test
    fun aRuntimeUnderAMinuteIsAbsent() {
        assertNull(metaLine(null, 30.seconds, emptyList(), copy))
    }

    @Test
    fun aRuntimeRoundsDownToWholeMinutes() {
        assertEquals("1 h 1 min", metaLine(null, 61.minutes + 59.seconds, emptyList(), copy))
    }
}
