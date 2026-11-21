// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.ratingsafety

import tv.stationbreak.core.engine.episode
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.series
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries

/**
 * The never-in-kids seed list of #992: titles that must never reach a kids or family channel. Metadata only (rating,
 * tags, genres, studios), written by hand to match what stock scrapers typically return; no media. Each has a
 * metadata-stripped copy (rating, tags and genres removed) so a missing-metadata path cannot let it in. Do not add
 * other real titles here: use the synthetic library in [SyntheticLibrary] instead.
 */
internal object NeverInKids {
    /** One fixture: an item and, for an episode, its series. */
    data class Fixture(
        val title: String,
        val entry: RuleItem,
        val series: RuleSeries?,
    ) {
        /** The same title with its rating, tags and genres removed (on the series too); studios stay. */
        fun stripped(): Fixture =
            copy(
                title = "$title (stripped)",
                entry = entry.copy(officialRating = null, tags = emptyList(), genres = emptyList()),
                series = series?.copy(officialRating = null, tags = emptyList(), genres = emptyList()),
            )
    }

    private var next = 0x9000

    private fun film(
        title: String,
        rating: String?,
        tags: List<String>,
        genres: List<String>,
        studios: List<String>,
    ) = Fixture(title, movie(idOf(next++), rating = rating, genres = genres, tags = tags, studios = studios), null)

    private fun show(
        title: String,
        rating: String?,
        tags: List<String>,
        genres: List<String>,
        studios: List<String>,
    ): Fixture {
        val showId = idOf(next++)
        val known = series(showId, rating = rating, genres = genres, tags = tags, studios = studios)
        return Fixture(title, episode(idOf(next++), showId, rating = rating), known)
    }

    private val animated = listOf("Animation")
    private val topcraft = listOf("Topcraft")

    /** The seed list, in the order of #992. */
    val SEED: List<Fixture> =
        listOf(
            film("Devil Hunter Yohko", "NR", listOf("nudity", "demon"), animated + "Action", listOf("Madhouse")),
            film("Perfect Blue", "R", listOf("stalker", "nudity"), animated + "Thriller", listOf("Madhouse")),
            film("Akira", "R", listOf("gore", "cyberpunk"), animated + "Science Fiction", listOf("TMS")),
            film("Fritz the Cat", "X", listOf("adult animation", "sex"), animated + "Comedy", emptyList()),
            show("Elfen Lied", "TV-MA", listOf("gore", "nudity"), animated + "Horror", listOf("Arms")),
            show("Berserk", "TV-MA", listOf("gore", "dark fantasy"), animated + "Action", listOf("OLM")),
            film("Sleepers", "R", listOf("kids", "revenge"), listOf("Crime", "Drama"), emptyList()),
            film("La Blue Girl", null, listOf("hentai"), animated + "Fantasy", emptyList()),
            film("Grave of the Fireflies", "NR", listOf("orphan"), animated + "Drama" + "War", listOf("Studio Ghibli")),
            film("Nausicaä of the Valley of the Wind", "PG", listOf("nudity"), animated + "Adventure", topcraft),
        )

    /** The seed list and the stripped copy of each. */
    val ALL: List<Fixture> = SEED + SEED.map { it.stripped() }
}
