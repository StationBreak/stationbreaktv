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
 * SYNTHETIC. The adult slice of the synthetic library (#1078, #1035): invented titles carrying the metadata stock
 * scraper plugins return (a rating, genres and TMDb keywords as tags), one for every title the Explicit tier must catch
 * and one for every control it must leave alone. Every title is made up; none is a real film or series, and nothing
 * here comes from a real library. No server is contacted.
 */
internal object SyntheticAdultSlice {
    /** One synthetic title: its item (an episode for a series) and, for a series, the series. */
    data class Fixture(
        val title: String,
        val entry: RuleItem,
        val series: RuleSeries?,
    )

    private var next = 0xA000
    private val all = ArrayList<Fixture>()

    private fun film(
        title: String,
        rating: String?,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        year: Int? = null,
    ): Fixture =
        Fixture(title, movie(idOf(next++), rating = rating, genres = genres, tags = tags, year = year), null)
            .also { all += it }

    // A series with one episode that has no metadata of its own, so the series decides (as scrapers leave it).
    private fun show(
        title: String,
        rating: String?,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ): Fixture {
        val showId = idOf(next++)
        val known = series(showId, rating = rating, genres = genres, tags = tags)
        return Fixture(title, episode(idOf(next++), showId), known).also { all += it }
    }

    /** Every title that must be Explicit and air on After Hours alone. */
    val EXPLICIT: List<Fixture> =
        listOf(
            film("Synthetic Seventies Sexploitation Comedy", "R", listOf("Comedy"), listOf("sexploitation"), 1974),
            film("Synthetic Eighties Sexsploitation Drama", "X", listOf("Drama"), listOf("sexsploitation"), 1982),
            film("Synthetic Softcore Cable Film", "R", listOf("Drama"), listOf("softcore", "erotic movie"), 1994),
            film("Synthetic Unrated Softcore Cable Film", "NR", listOf("Romance"), listOf("softcore"), 1997),
            film("Synthetic Modern Adult Feature", "XXX", listOf("Adult"), listOf("porn")),
            film("Synthetic Unrated Adult Video", "NR", emptyList(), listOf("pornography")),
            film("Synthetic Unlabelled Adult Video", null, emptyList(), listOf("porn")),
            film("Synthetic British Import Feature", "GB-R18", listOf("Drama"), emptyList()),
            film("Synthetic Australian Import Feature", "AU-X18+", emptyList(), emptyList()),
            show("Synthetic Hentai Series", "XXX", listOf("Anime", "Hentai"), listOf("tentacles")),
            show("Synthetic Tag-Only Hentai Series", null, listOf("Anime", "Fantasy"), listOf("hentai")),
            film("Synthetic NC-17 Dancer Drama", "NC-17", listOf("Drama"), listOf("dancer", "nudity")),
        )

    /** The controls: not Explicit, Adult, so grown-up channels only (never Teen, Family or the young leftovers). */
    val CONTROLS: List<Fixture> =
        listOf(
            film("Synthetic Summer Camp Sex Comedy", "R", listOf("Comedy"), listOf("sex comedy", "nudity"), 1981),
            film("Synthetic Ancient War Epic", "R", listOf("Action", "War"), listOf("nudity")),
            film("Synthetic Barbarian Sword Epic", "R", listOf("Adventure", "Fantasy"), listOf("nudity"), 1982),
            film("Synthetic Detective Erotic Thriller", "R", listOf("Thriller"), listOf("erotic thriller"), 1992),
            film("Synthetic High School Teen Comedy", "R", listOf("Comedy"), listOf("teen sex comedy"), 1982),
            film("Synthetic Big City Hustler Drama", "R", listOf("Drama"), listOf("hustler"), 1969),
            film("Synthetic Dystopian Youth Gang Drama", "R", listOf("Science Fiction"), listOf("dystopia"), 1971),
            film("Synthetic Valley Film Industry Drama", "R", listOf("Drama"), listOf("porn industry", "porn star")),
            show("Synthetic TV-MA Ecchi Anime", "TV-MA", listOf("Anime", "Comedy"), listOf("ecchi", "nudity", "harem")),
            show("Synthetic TV-MA Erotic-Tag Anime", "TV-MA", listOf("Anime", "Fantasy"), listOf("erotic")),
            show("Synthetic TV-14 Ecchi Anime", "TV-14", listOf("Anime", "Comedy", "Ecchi"), listOf("nudity")),
            film("Synthetic Windswept Valley Adventure", "PG", listOf("Animation", "Adventure"), listOf("nudity")),
            film("Synthetic PG-13 Sex Comedy", "PG-13", listOf("Comedy"), listOf("sex comedy")),
        )

    /** One file with no metadata at all: Adult, not Explicit; off young channels and out of Odds & Ends 199. */
    val NO_METADATA: Fixture = film("Synthetic Test Clip", null)

    /** A teen and a family title, so the young pools are not empty for want of anything to take. */
    val YOUNG: List<Fixture> =
        listOf(
            film("Synthetic Family Adventure", "PG", listOf("Adventure")),
            film("Synthetic Teen Adventure", "PG-13", listOf("Adventure")),
            show("Synthetic Teen Anime", "TV-14", listOf("Anime", "Action"), listOf("anime")),
        )

    /** Every fixture, in ID order. */
    val ALL: List<Fixture> = all.toList()

    /** The slice as a snapshot of its own. */
    fun build(): RuleSnapshot = snapshot(items = ALL.map { it.entry }, series = ALL.mapNotNull { it.series })
}
