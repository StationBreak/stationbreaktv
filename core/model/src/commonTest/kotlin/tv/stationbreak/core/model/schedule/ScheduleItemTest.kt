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

    private val series = CanonicalId.parse("22222222222222222222222222222222")

    private fun movie(
        seriesId: CanonicalId? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) = ScheduleItem(
        id = id,
        kind = ScheduleItemKind.Movie,
        runtimeMs = 1L,
        createdAtMs = 0L,
        deletedAtMs = null,
        premiereDate = null,
        productionYear = null,
        seriesId = seriesId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )

    @Test
    fun aMovieHasNoSeriesSeasonOrEpisode() {
        movie()
        assertFailsWith<IllegalArgumentException> { movie(seriesId = series) }
        assertFailsWith<IllegalArgumentException> { movie(seasonNumber = 1) }
        assertFailsWith<IllegalArgumentException> { movie(episodeNumber = 1) }
    }

    @Test
    fun anOrphanEpisodeIsAcceptedAndIsAGroupOfOne() {
        val orphan = item(seasonNumber = 1, episodeNumber = 2)
        assertNull(orphan.seriesId)
        assertEquals(id, orphan.groupId)
        assertEquals(id, movie().groupId)
    }

    @Test
    fun anEpisodeWithASeriesIsGroupedBySeries() {
        val episode = item().copy(seriesId = series)
        assertEquals(series, episode.groupId)
    }

    @Test
    fun aProductionYearIsOneToNineThousandNineHundredNinetyNine() {
        for (y in listOf(1, 999, 1969, 9999)) accepts(y)
        for (y in listOf(0, -1, 10000)) assertFailsWith<IllegalArgumentException>("$y") { accepts(y) }
    }

    private fun accepts(year: Int) =
        ScheduleItem(
            id = id,
            kind = ScheduleItemKind.Movie,
            runtimeMs = 1L,
            createdAtMs = 0L,
            deletedAtMs = null,
            premiereDate = null,
            productionYear = year,
            seriesId = null,
            seasonNumber = null,
            episodeNumber = null,
        )

    @Test
    fun aPremiereDateHasARealMonthAndDay() {
        for (ok in listOf("2020-01-01", "2020-12-31", "0999-06-15")) item(premiereDate = ok)
        for (bad in listOf("2020-00-10", "2020-13-10", "2020-01-00", "2020-01-32", "2020-99-99")) {
            assertFailsWith<IllegalArgumentException>(bad) { item(premiereDate = bad) }
        }
    }

    @Test
    fun aPremiereDateIsTenAsciiCharactersWithAYearFromOne() {
        for (ok in listOf("0001-01-01", "9999-12-31", "2020-02-31")) item(premiereDate = ok)
        val bad = listOf("0000-01-01", " 2020-01-01", "2020-01-01 ", "2020-01-01\n", "\u0662020-01-01", "2020-01-01T10:00")
        for (value in bad) {
            assertFailsWith<IllegalArgumentException>(value) { item(premiereDate = value) }
        }
    }
}
