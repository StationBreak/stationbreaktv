// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model.schedule

import tv.stationbreak.core.model.CanonicalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * `ScheduleItem.problem` is the one place the item checks live (spec 09, Granny M1): the init requires it to find
 * nothing, and a caller that must not throw (the library cache's as-of read) asks it first.
 */
class ScheduleItemProblemTest {
    private val id = CanonicalId.parse("11111111111111111111111111111111")
    private val series = CanonicalId.parse("22222222222222222222222222222222")

    @Suppress("LongParameterList") // Mirrors the fields under test, all with defaults.
    private fun problem(
        kind: ScheduleItemKind = ScheduleItemKind.Episode,
        premiereDate: String? = null,
        productionYear: Int? = null,
        seriesId: CanonicalId? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) = ScheduleItem.problem(kind, premiereDate, productionYear, seriesId, seasonNumber, episodeNumber)

    @Suppress("LongParameterList") // Mirrors the fields under test, all with defaults.
    private fun build(
        kind: ScheduleItemKind = ScheduleItemKind.Episode,
        premiereDate: String? = null,
        productionYear: Int? = null,
        seriesId: CanonicalId? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) = ScheduleItem(
        id,
        kind,
        1_800_000L,
        0L,
        null,
        premiereDate,
        productionYear,
        seriesId,
        seasonNumber,
        episodeNumber,
    )

    @Test
    fun aValidItemHasNoProblem() {
        assertNull(problem())
        assertNull(problem(premiereDate = "0001-01-01", productionYear = 1, seasonNumber = 0, episodeNumber = 0))
        assertNull(problem(premiereDate = "9999-12-31", productionYear = 9999, seriesId = series))
        assertNull(problem(kind = ScheduleItemKind.Movie))
    }

    @Test
    fun eachProblemIsNamed() {
        val dates = listOf("2020-1-1", "20200101", "2020-01-01T00:00:00Z", "", "yesterday", "0000-01-01", "2020-13-01")
        for (bad in dates) {
            assertEquals(ScheduleItemProblem.PremiereDateMalformed, problem(premiereDate = bad), bad)
        }
        for (bad in listOf(0, -1, 10_000)) {
            assertEquals(ScheduleItemProblem.ProductionYearOutOfRange, problem(productionYear = bad), "$bad")
        }
        assertEquals(ScheduleItemProblem.SeasonNegative, problem(seasonNumber = -1))
        assertEquals(ScheduleItemProblem.EpisodeNegative, problem(episodeNumber = -1))
        assertEquals(ScheduleItemProblem.MovieHasSeriesData, problem(kind = ScheduleItemKind.Movie, seriesId = series))
        assertEquals(ScheduleItemProblem.MovieHasSeriesData, problem(kind = ScheduleItemKind.Movie, seasonNumber = 1))
        assertEquals(ScheduleItemProblem.MovieHasSeriesData, problem(kind = ScheduleItemKind.Movie, episodeNumber = 1))
    }

    @Test
    fun theFirstProblemInTheOrderOfTheChecksIsReported() {
        assertEquals(
            ScheduleItemProblem.PremiereDateMalformed,
            problem(premiereDate = "x", productionYear = 0, seasonNumber = -1),
        )
        assertEquals(ScheduleItemProblem.ProductionYearOutOfRange, problem(productionYear = 0, seasonNumber = -1))
        assertEquals(ScheduleItemProblem.SeasonNegative, problem(seasonNumber = -1, episodeNumber = -1))
    }

    @Test
    fun theInitRefusesExactlyWhatProblemFindsWithTheProblemsMessage() {
        val cases =
            listOf(
                { build(premiereDate = "x") } to problem(premiereDate = "x"),
                { build(productionYear = 0) } to problem(productionYear = 0),
                { build(seasonNumber = -1) } to problem(seasonNumber = -1),
                { build(episodeNumber = -1) } to problem(episodeNumber = -1),
                { build(kind = ScheduleItemKind.Movie, seasonNumber = 1) } to
                    problem(kind = ScheduleItemKind.Movie, seasonNumber = 1),
            )
        for ((make, found) in cases) {
            val refused = assertFailsWith<IllegalArgumentException> { make() }
            assertEquals(found?.message, refused.message)
        }
    }

    @Test
    fun theMessagesAreTheOnesTheInitAlwaysHad() {
        val date = "A premiere date is YYYY-MM-DD, with a year from 0001 to 9999"
        assertEquals(date, ScheduleItemProblem.PremiereDateMalformed.message)
        assertEquals("A production year is 1 to 9999", ScheduleItemProblem.ProductionYearOutOfRange.message)
        assertEquals("A season number is not negative", ScheduleItemProblem.SeasonNegative.message)
        assertEquals("An episode number is not negative", ScheduleItemProblem.EpisodeNegative.message)
        assertEquals("A movie has no series, season or episode number", ScheduleItemProblem.MovieHasSeriesData.message)
    }
}
