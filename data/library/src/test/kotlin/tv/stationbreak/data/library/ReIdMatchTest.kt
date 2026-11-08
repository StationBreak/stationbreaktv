// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId

/** The conservative match of an item that came back under a new id (spec 26, Task 3.3): pure, on signals alone. */
class ReIdMatchTest {
    private fun id(n: Int) = CanonicalId.parse(hexId(n))

    @Suppress("LongParameterList") // One optional parameter per signal a test sets.
    private fun film(
        n: Int,
        name: String = "invented film",
        year: Int? = 1999,
        runtimeMin: Long? = 90,
        ids: Map<String, String> = emptyMap(),
        kind: String = "Movie",
    ) = Signals(id(n), kind, ids, null, null, null, name, year, runtimeMin?.let { it * 60_000 })

    @Suppress("LongParameterList") // One optional parameter per signal a test sets.
    private fun episode(
        n: Int,
        series: Int?,
        season: Int? = 1,
        number: Int? = 2,
        runtimeMin: Long? = 30,
        ids: Map<String, String> = emptyMap(),
    ) = Signals(
        id = id(n),
        kind = "Episode",
        providerIds = ids,
        seriesId = series?.let(::id),
        season = season,
        episode = number,
        foldedName = "invented episode",
        year = null,
        runtimeMs = runtimeMin?.let { it * 60_000 },
    )

    private fun show(
        n: Int,
        name: String = "invented show",
        ids: Map<String, String> = emptyMap(),
    ) = SeriesSignals(id(n), name, ids)

    private fun match(
        gone: List<Signals>,
        live: List<Signals>,
        liveSeries: List<SeriesSignals> = emptyList(),
        goneSeries: List<SeriesSignals> = emptyList(),
    ) = ReIdMatch.match(gone, live, liveSeries, goneSeries)

    @Test
    fun providerIdMatch() {
        val gone = film(1, ids = mapOf("imdb" to "tt1", "tmdb" to "5"), name = "old name", year = null)
        val live = film(2, ids = mapOf("tmdb" to "5", "tvdb" to "9"), name = "other", year = null)
        assertEquals(mapOf(id(1) to id(2)), match(listOf(gone), listOf(live)))
    }

    @Test
    fun sameKeyDifferentValue_isNotAMatch() {
        val gone = film(1, ids = mapOf("imdb" to "tt1"), name = "a", year = null)
        val live = film(2, ids = mapOf("imdb" to "tt2"), name = "b", year = null)
        assertEquals(emptyMap<CanonicalId, CanonicalId>(), match(listOf(gone), listOf(live)))
    }

    @Test
    fun providerIdSharedByTwoCandidates_ambiguous() {
        val gone = film(1, ids = mapOf("tmdb" to "5"))
        val live = listOf(film(2, ids = mapOf("tmdb" to "5")), film(3, ids = mapOf("tmdb" to "5")))
        assertEquals(emptyMap<CanonicalId, CanonicalId>(), match(listOf(gone), live))
    }

    @Test
    fun episodeSameSeriesId_seasonAndEpisode_match() {
        val gone = episode(1, series = 100)
        val live = listOf(episode(2, series = 100), episode(3, series = 100, number = 3), episode(4, series = 101))
        assertEquals(mapOf(id(1) to id(2)), match(listOf(gone), live))
    }

    @Test
    fun episodeMovedSeries_matchedBySeriesProviderId() {
        val gone = episode(1, series = 100)
        val live = listOf(episode(2, series = 200), episode(3, series = 201))
        val goneSeries = listOf(show(100, name = "old", ids = mapOf("tvdb" to "77")))
        val liveSeries = listOf(show(200, name = "new", ids = mapOf("tvdb" to "77")), show(201, name = "other"))
        assertEquals(mapOf(id(1) to id(2)), match(listOf(gone), live, liveSeries, goneSeries))
    }

    @Test
    fun episodeMovedSeries_matchedByUniqueName() {
        val gone = episode(1, series = 100)
        val live = listOf(episode(2, series = 200), episode(3, series = 201))
        val goneSeries = listOf(show(100, name = "same show"))
        val liveSeries = listOf(show(200, name = "same show"), show(201, name = "different"))
        assertEquals(mapOf(id(1) to id(2)), match(listOf(gone), live, liveSeries, goneSeries))
    }

    @Test
    fun twoSeriesSameName_ambiguous_noMatch() {
        val gone = episode(1, series = 100)
        val live = listOf(episode(2, series = 200), episode(3, series = 201))
        val goneSeries = listOf(show(100, name = "same show"))
        val liveSeries = listOf(show(200, name = "same show"), show(201, name = "same show"))
        assertEquals(emptyMap<CanonicalId, CanonicalId>(), match(listOf(gone), live, liveSeries, goneSeries))
    }

    @Test
    fun episodeSeriesNotATombstone_andNotTheSameId_noMatch() {
        val gone = episode(1, series = 100)
        val live = listOf(episode(2, series = 200))
        val liveSeries = listOf(show(200, name = "invented show"))
        assertEquals(emptyMap<CanonicalId, CanonicalId>(), match(listOf(gone), live, liveSeries))
    }

    @Test
    fun emptySeriesName_isNeverAMatch() {
        val gone = episode(1, series = 100)
        val live = listOf(episode(2, series = 200))
        val goneSeries = listOf(show(100, name = ""))
        val liveSeries = listOf(show(200, name = ""))
        assertEquals(emptyMap<CanonicalId, CanonicalId>(), match(listOf(gone), live, liveSeries, goneSeries))
    }

    @Test
    fun filmTitleYear_match() {
        val gone = film(1, name = "invented film", year = 1999)
        val live = listOf(film(2, name = "invented film", year = 1999), film(3, name = "invented film", year = 2001))
        assertEquals(mapOf(id(1) to id(2)), match(listOf(gone), live))
    }

    @Test
    fun filmWithoutYearOrName_noMatch() {
        assertEquals(
            emptyMap<CanonicalId, CanonicalId>(),
            match(listOf(film(1, year = null)), listOf(film(2, year = null))),
        )
        assertEquals(
            emptyMap<CanonicalId, CanonicalId>(),
            match(listOf(film(1, name = "")), listOf(film(2, name = ""))),
        )
    }

    @Test
    fun twoGoneOneCandidate_noMatch() {
        val gone = listOf(film(1), film(2))
        assertEquals(emptyMap<CanonicalId, CanonicalId>(), match(gone, listOf(film(3))))
    }

    @Test
    fun runtimeTolerance_isTwoMinutesOrTwoPercent() {
        // 90 min: 2 min wins over 1.8 min.
        assertEquals(mapOf(id(1) to id(2)), match(listOf(film(1, runtimeMin = 90)), listOf(film(2, runtimeMin = 92))))
        // 200 min: 2% is 4 min, more than 2 min.
        assertEquals(mapOf(id(1) to id(2)), match(listOf(film(1, runtimeMin = 200)), listOf(film(2, runtimeMin = 204))))
    }

    @Test
    fun runtimeOutsideTolerance_noMatch() {
        assertEquals(
            emptyMap<CanonicalId, CanonicalId>(),
            match(listOf(film(1, runtimeMin = 90)), listOf(film(2, runtimeMin = 93))),
        )
        assertEquals(
            emptyMap<CanonicalId, CanonicalId>(),
            match(listOf(film(1, runtimeMin = 90)), listOf(film(2, runtimeMin = null))),
        )
    }

    @Test
    fun differentKind_noMatch() {
        val gone = film(1, ids = mapOf("tmdb" to "5"))
        val live = film(2, ids = mapOf("tmdb" to "5"), kind = "Episode")
        assertEquals(emptyMap<CanonicalId, CanonicalId>(), match(listOf(gone), listOf(live)))
    }

    @Test
    fun aMatchNeverChangesWhatTheOtherGoneItemsGet() {
        val gone = listOf(film(1, name = "first film"), film(2, name = "second film"))
        val live = listOf(film(3, name = "first film"), film(4, name = "second film"))
        assertEquals(mapOf(id(1) to id(3), id(2) to id(4)), match(gone, live))
    }

    private val none = emptyMap<CanonicalId, CanonicalId>()

    @Test
    fun collectionIdShared_noMatch() {
        val gone = film(1, name = "first film", ids = mapOf("tmdbcollection" to "7"), year = null)
        val live = film(2, name = "second film", ids = mapOf("tmdbcollection" to "7"), year = null)
        assertEquals(none, match(listOf(gone), listOf(live)))
    }

    @Test
    fun aSeriesIdOtherThanAnIdentityKey_neverResolvesAMovedSeries() {
        val gone = episode(1, series = 100)
        val live = listOf(episode(2, series = 200))
        val goneSeries = listOf(show(100, name = "old", ids = mapOf("tmdbcollection" to "7")))
        val liveSeries = listOf(show(200, name = "new", ids = mapOf("tmdbcollection" to "7")))
        assertEquals(none, match(listOf(gone), live, liveSeries, goneSeries))
    }

    @Test
    fun imdbShared_butTmdbConflicts_noMatch() {
        val gone = film(1, name = "first film", year = null, ids = mapOf("imdb" to "tt1", "tmdb" to "5"))
        val live = film(2, name = "second film", year = null, ids = mapOf("imdb" to "tt1", "tmdb" to "6"))
        assertEquals(none, match(listOf(gone), listOf(live)))
    }

    @Test
    fun identityConflict_blocksTheFilmTitleAndYear() {
        val gone = film(1, ids = mapOf("tmdb" to "5"))
        val live = film(2, ids = mapOf("tmdb" to "6"))
        assertEquals(none, match(listOf(gone), listOf(live)))
    }

    @Test
    fun identityConflict_blocksTheSeriesName() {
        val gone = episode(1, series = 100)
        val live = listOf(episode(2, series = 200))
        val goneSeries = listOf(show(100, name = "same show", ids = mapOf("tvdb" to "1")))
        val liveSeries = listOf(show(200, name = "same show", ids = mapOf("tvdb" to "2")))
        assertEquals(none, match(listOf(gone), live, liveSeries, goneSeries))
    }

    @Test
    fun identityConflict_onTheEpisodeItself_noMatch() {
        val gone = episode(1, series = 100, ids = mapOf("tvdb" to "1"))
        val live = listOf(episode(2, series = 100, ids = mapOf("tvdb" to "2")))
        assertEquals(none, match(listOf(gone), live))
    }

    @Test
    fun idsPresentButNoneShared_fallsThroughToTheTitleAndYear() {
        val gone = film(1, ids = mapOf("tmdb" to "5"))
        val live = film(2, ids = mapOf("imdb" to "tt9"))
        assertEquals(mapOf(id(1) to id(2)), match(listOf(gone), listOf(live)))
    }

    @Test
    fun seriesNameDecides_whenTheSeriesShareNoIdentityKey() {
        val gone = episode(1, series = 100)
        val live = listOf(episode(2, series = 200))
        val goneSeries = listOf(show(100, name = "same show", ids = mapOf("tmdb" to "1")))
        val liveSeries = listOf(show(200, name = "same show", ids = mapOf("tvdb" to "2")))
        assertEquals(mapOf(id(1) to id(2)), match(listOf(gone), live, liveSeries, goneSeries))
    }

    @Test
    fun severalProviderIdSeriesMatches_neverFallBackToTheName() {
        val gone = episode(1, series = 100)
        val live = listOf(episode(2, series = 202))
        val goneSeries = listOf(show(100, name = "same show", ids = mapOf("tvdb" to "1")))
        val liveSeries =
            listOf(
                show(200, name = "one", ids = mapOf("tvdb" to "1")),
                show(201, name = "two", ids = mapOf("tvdb" to "1")),
                show(202, name = "same show"),
            )
        assertEquals(none, match(listOf(gone), live, liveSeries, goneSeries))
    }

    @Test
    fun anUnknownSeasonOrEpisode_onEitherSide_noMatch() {
        val live = listOf(episode(2, series = 100, season = null, number = null))
        assertEquals(none, match(listOf(episode(1, series = 100, season = null, number = null)), live))
        val noSeason = episode(2, series = 100, season = null)
        val noNumber = episode(2, series = 100, number = null)
        assertEquals(none, match(listOf(episode(1, series = 100, season = null)), listOf(noSeason)))
        assertEquals(none, match(listOf(episode(1, series = 100, number = null)), listOf(noNumber)))
        assertEquals(none, match(listOf(episode(1, series = 100)), listOf(episode(2, series = 100, season = null))))
    }

    @Test
    fun anEpisodeWithoutASeries_noMatch() {
        assertEquals(none, match(listOf(episode(1, series = null)), listOf(episode(2, series = null))))
    }

    @Test
    fun twoPercentBoundary() {
        assertEquals(none, match(listOf(film(1, runtimeMin = 200)), listOf(film(2, runtimeMin = 205))))
        assertEquals(mapOf(id(1) to id(2)), match(listOf(film(1, runtimeMin = 200)), listOf(film(2, runtimeMin = 196))))
    }

    @Test
    fun aRuntimeFailure_doesNotFallThroughToALooserRule() {
        val gone = film(1, ids = mapOf("tmdb" to "5"), runtimeMin = 90)
        val live = listOf(film(2, ids = mapOf("tmdb" to "5"), runtimeMin = 120), film(3, runtimeMin = 90))
        assertEquals(none, match(listOf(gone), live))
    }

    @Test
    fun oneCandidateChosenByRulesOfTwoGoneItems_isTheMatchOfNeither() {
        val byId = film(1, name = "first", ids = mapOf("tmdb" to "5"), year = 1990)
        val byTitle = film(2, name = "target", year = 1999)
        val target = film(3, name = "target", ids = mapOf("tmdb" to "5"), year = 1999)
        assertEquals(none, match(listOf(byId, byTitle), listOf(target)))
    }

    @Test
    fun anAmbiguousGoneItem_voidsEveryCandidateItSaw() {
        val ambiguous = film(1, name = "twin", year = 1999)
        val unique = film(2, name = "other", ids = mapOf("tmdb" to "5"), year = 1980)
        val twinWithId = film(4, name = "twin", ids = mapOf("tmdb" to "5"), year = 1999)
        val live = listOf(film(3, name = "twin", year = 1999), twinWithId)
        assertEquals(none, match(listOf(ambiguous, unique), live))
    }

    @Test
    fun aBlankName_neverMatches() {
        assertEquals(none, match(listOf(film(1, name = "  ")), listOf(film(2, name = "  "))))
        val goneSeries = listOf(show(100, name = " "))
        val liveSeries = listOf(show(200, name = " "))
        val live = listOf(episode(2, series = 200))
        assertEquals(none, match(listOf(episode(1, series = 100)), live, liveSeries, goneSeries))
    }

    @Test
    fun aContradictedFilm_besideAnotherOfTheSameTitleAndYear_noMatch() {
        val gone = film(1, ids = mapOf("tmdb" to "5"))
        val live = listOf(film(2, ids = mapOf("tmdb" to "6")), film(3))
        assertEquals(none, match(listOf(gone), live))
    }

    @Test
    fun aContradictedEpisode_besideAnotherOfTheSameSeriesAndNumber_noMatch() {
        val gone = episode(1, series = 100, ids = mapOf("tvdb" to "1"))
        val live = listOf(episode(2, series = 100, ids = mapOf("tvdb" to "2")), episode(3, series = 100))
        assertEquals(none, match(listOf(gone), live))
    }

    @Test
    fun aContradictedSeries_besideAnotherOfTheName_isNotResolved() {
        val gone = episode(1, series = 100)
        val live = listOf(episode(2, series = 200), episode(3, series = 201))
        val goneSeries = listOf(show(100, name = "same show", ids = mapOf("tvdb" to "1")))
        val contradicted = show(200, name = "same show", ids = mapOf("tvdb" to "2"))
        val liveSeries = listOf(contradicted, show(201, name = "same show"))
        assertEquals(none, match(listOf(gone), live, liveSeries, goneSeries))
    }
}
