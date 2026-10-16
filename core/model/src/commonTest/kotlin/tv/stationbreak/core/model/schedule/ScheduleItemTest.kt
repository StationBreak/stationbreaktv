// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.CanonicalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScheduleItemTest {
    private val id = CanonicalId.parse("11111111111111111111111111111111")

    private fun item(
        premiereDate: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) = ScheduleItem(
        id = id,
        kind = ScheduleItemKind.Episode,
        runtimeMs = 1_800_000L,
        createdAtMs = 0L,
        deletedAtMs = null,
        premiereDate = premiereDate,
        productionYear = null,
        seriesId = null,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )

    @Test
    fun anItemKeepsWhatItWasGiven() {
        val movie =
            ScheduleItem(
                id = id,
                kind = ScheduleItemKind.Movie,
                runtimeMs = 7_200_000L,
                createdAtMs = -5L,
                deletedAtMs = 9L,
                premiereDate = "1969-07-20",
                productionYear = 1969,
                seriesId = null,
                seasonNumber = null,
                episodeNumber = null,
            )
        assertEquals(ScheduleItemKind.Movie, movie.kind)
        assertEquals(9L, movie.deletedAtMs)
        assertEquals("1969-07-20", movie.premiereDate)
        assertNull(movie.seriesId)
    }

    @Test
    fun aPremiereDateIsACalendarDateAsText() {
        item(premiereDate = "2020-01-01")
        for (bad in listOf("2020-1-1", "20200101", "2020-01-01T00:00:00Z", "", "yesterday")) {
            assertFailsWith<IllegalArgumentException>(bad) { item(premiereDate = bad) }
        }
    }

    @Test
    fun seasonAndEpisodeNumbersAreNotNegative() {
        item(seasonNumber = 0, episodeNumber = 0)
        assertFailsWith<IllegalArgumentException> { item(seasonNumber = -1) }
        assertFailsWith<IllegalArgumentException> { item(episodeNumber = -1) }
    }
}
