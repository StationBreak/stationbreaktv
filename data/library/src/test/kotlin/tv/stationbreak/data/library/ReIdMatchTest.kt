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
        val live = film(2, ids = mapOf("imdb" to "tt9", "tmdb" to "5"), name = "other", year = null)
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
}
