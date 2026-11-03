// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.FilterKind
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/rules-format.md, "Filters": genres, years and kind narrow the pool; the ceiling still applies. */
class ItemFiltersTest {
    private val show = idOf(0x10)

    private fun filters(
        genres: List<String>? = null,
        years: IntRange? = null,
        kind: FilterKind? = null,
    ) = Filters(genres, years?.first, years?.last, null, null, kind)

    private fun libraryRules(
        filters: Filters?,
        maxRating: String? = null,
    ): ChannelRules = rules(listOf(Source.Library(LIB)), null, maxRating, formatVersion = 2, filters = filters)

    private fun idsOf(
        rules: ChannelRules,
        vararg items: RuleItem,
        shows: List<RuleSeries> = emptyList(),
    ) = PoolEvaluator.evaluate(rules, snapshot(items = items.toList(), series = shows)).items.map { it.id }

    @Test
    fun genresPassAnyOfTheChosen() {
        val comedy = movie(idOf(1), genres = listOf("Comedy"))
        val drama = movie(idOf(2), genres = listOf("Drama"))
        val action = movie(idOf(3), genres = listOf("Action"))

        val ids = idsOf(libraryRules(filters(genres = listOf("comedy", "DRAMA"))), comedy, drama, action)

        assertEquals(listOf(idOf(1), idOf(2)), ids)
    }

    @Test
    fun anEpisodeTakesItsSeriesGenres() {
        val comedyShow = series(show, genres = listOf("Comedy"))
        val inherits = episode(idOf(1), show)
        val noSeries = episode(idOf(2), null)

        val rules = libraryRules(filters(genres = listOf("Comedy")))
        val ids = idsOf(rules, inherits, noSeries, shows = listOf(comedyShow))

        assertEquals(listOf(idOf(1)), ids)
    }

    @Test
    fun anItemWithNoYearFailsAYearFilter() {
        val dated = movie(idOf(1), year = 1990)
        val undated = movie(idOf(2))

        assertEquals(listOf(idOf(1)), idsOf(libraryRules(filters(years = 1980..1999)), dated, undated))
    }

    @Test
    fun yearBoundsAreInclusive() {
        val items = listOf(1979, 1980, 1999, 2000).mapIndexed { i, year -> movie(idOf(i + 1), year = year) }

        val ids = idsOf(libraryRules(filters(years = 1980..1999)), *items.toTypedArray())

        assertEquals(listOf(idOf(2), idOf(3)), ids)
    }

    @Test
    fun kindMovieKeepsOnlyFilms() {
        val film = movie(idOf(1))
        val ep = episode(idOf(2), null)

        assertEquals(listOf(idOf(1)), idsOf(libraryRules(filters(kind = FilterKind.Movie)), film, ep))
        assertEquals(listOf(idOf(2)), idsOf(libraryRules(filters(kind = FilterKind.Episode)), film, ep))
    }

    @Test
    fun filtersAreAndedWithEachOtherAndTheCeiling() {
        val passes = movie(idOf(1), genres = listOf("Comedy"), year = 1990, rating = "PG")
        val wrongYear = movie(idOf(2), genres = listOf("Comedy"), year = 2010, rating = "PG")
        val wrongGenre = movie(idOf(3), genres = listOf("Drama"), year = 1990, rating = "PG")
        val tooStrong = movie(idOf(4), genres = listOf("Comedy"), year = 1990, rating = "R")
        val rules = libraryRules(filters(genres = listOf("Comedy"), years = 1980..1999, kind = FilterKind.Movie), "PG")

        val pool = PoolEvaluator.evaluate(rules, snapshot(items = listOf(passes, wrongYear, wrongGenre, tooStrong)))

        assertEquals(listOf(idOf(1)), pool.items.map { it.id })
        assertEquals(2, pool.counts.filteredOut)
        assertEquals(1, pool.counts.blockedByCeiling)
    }

    @Test
    fun filteredOutIsCounted() {
        val items = listOf(movie(idOf(1), year = 1990), movie(idOf(2), year = 2010), movie(idOf(3)))

        val pool = PoolEvaluator.evaluate(libraryRules(filters(years = 1980..1999)), snapshot(items = items))

        assertEquals(2, pool.counts.filteredOut)
        val counts = pool.counts
        assertEquals(
            items.size,
            pool.items.size + counts.excluded + counts.heldOut + counts.childrensRatingOut + counts.filteredOut +
                counts.blockedByCeiling,
        )
    }

    @Test
    fun anEmptyFiltersObjectChangesNothing() {
        val items = listOf(movie(idOf(1)), episode(idOf(2), null, year = 1950))
        val plain = PoolEvaluator.evaluate(libraryRules(null), snapshot(items = items))
        val noParts = libraryRules(Filters(null, null, null, null, null, null))
        val empty = PoolEvaluator.evaluate(noParts, snapshot(items = items))

        assertEquals(plain, empty)
        assertEquals(0, empty.counts.filteredOut)
    }
}
