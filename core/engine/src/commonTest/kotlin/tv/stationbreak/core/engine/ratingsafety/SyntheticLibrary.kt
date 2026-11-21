// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.ratingsafety

import tv.stationbreak.core.engine.episode
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.series
import tv.stationbreak.core.engine.snapshot
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot

/**
 * A deterministic synthetic library for the rating-safety leak check (#992): every combination of the ratings, tag
 * sets, genre sets and studio sets below, once as a movie and once as an episode of a series that carries the
 * combination, plus episodes whose series is missing or absent. Nothing in it is real; no server is contacted.
 */
internal object SyntheticLibrary {
    val RATINGS: List<String?> =
        listOf(
            null,
            "",
            " ",
            "\t",
            "NR",
            "Not Rated",
            "Approved",
            "Passed",
            "G",
            "TV-Y",
            "TV-G",
            "TV-Y7",
            "TV-Y7-FV",
            "PG",
            "TV-PG",
            "GP",
            "PG-13",
            "PG13",
            "TV-14",
            "TV-14-V",
            "R",
            "TV-MA",
            "NC-17",
            "TV-X",
            "XXX",
            "6",
            "6+",
            "12",
            "16",
            "18+",
            "Rated PG",
            "TV-14/TV-MA",
            "U",
            "12A",
            "AU PG",
            "FSK 16",
            "MA15+",
            "DE-12",
            "gb/15",
            "U/A 13+",
            "B",
            "B-15",
            "10AP",
            "T",
        )

    val TAG_SETS: List<List<String>> =
        listOf(
            emptyList(),
            listOf("kids"),
            listOf("Children"),
            listOf("nudity"),
            listOf("hentai"),
            listOf("Sex"),
            listOf("gore"),
            listOf("kids", "nudity"),
            listOf("kids", "gore"),
            listOf("friendship"),
        )

    val GENRE_SETS: List<List<String>> =
        listOf(
            emptyList(),
            listOf("Kids"),
            listOf("Animation", "Family"),
            listOf("Family"),
            listOf("Animation"),
            listOf("Drama"),
            listOf("Animation", "Adult"),
            listOf("Horror"),
        )

    val STUDIO_SETS: List<List<String>> = listOf(emptyList(), listOf("Nickelodeon"), listOf("Studio Ghibli"))

    private const val UNKNOWN_SERIES_EVERY = 5
    private const val NO_SERIES_EVERY = 7
    private const val OWN_RATING_STRIDE = 11

    private class Combo(
        val rating: String?,
        val tags: List<String>,
        val genres: List<String>,
        val studios: List<String>,
    )

    private val combos: List<Combo> =
        RATINGS.flatMap { rating ->
            TAG_SETS.flatMap { tags ->
                GENRE_SETS.flatMap { genres -> STUDIO_SETS.map { studios -> Combo(rating, tags, genres, studios) } }
            }
        }

    /** The library. Items are in ascending ID order, as a snapshot needs. */
    fun build(): RuleSnapshot {
        val items = ArrayList<RuleItem>()
        val shows = ArrayList<RuleSeries>()
        val missingSeries = idOf(0x7FFF_0000)
        var n = 0
        for ((index, c) in combos.withIndex()) {
            items += movie(idOf(++n), rating = c.rating, genres = c.genres, tags = c.tags, studios = c.studios)
            val showId = idOf(++n)
            shows += series(showId, rating = c.rating, genres = c.genres, tags = c.tags, studios = c.studios)
            items += episode(idOf(++n), showId, rating = RATINGS[(index * OWN_RATING_STRIDE) % RATINGS.size])
            if (index % UNKNOWN_SERIES_EVERY == 0) {
                items += episode(idOf(++n), missingSeries, rating = c.rating, genres = c.genres, tags = c.tags)
            }
            if (index % NO_SERIES_EVERY == 0) {
                items += episode(idOf(++n), null, rating = c.rating, genres = c.genres, tags = c.tags)
            }
        }
        return snapshot(items = items, series = shows)
    }
}
