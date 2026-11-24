// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The shape of a library is its data: counts, minutes and years are written as numbers.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.ratingsafety.SyntheticAdultSlice
import tv.stationbreak.core.model.RuleSnapshot

/**
 * Where the gate's frozen fixtures (`core/engine/src/audit/gate/fixtures`) come from: generated from invented titles,
 * written once as `audit-snapshot` files, and held in step by [GateFixturesTest]. `lineupGateGoldens` writes them
 * again. Another fixture (an anonymized household snapshot, the 2026-11-20 mock) is a file dropped in that folder.
 */
internal object GateFixtureSources {
    fun all(): Map<String, AuditInput> =
        buildMap {
            put("anilist-only", aniList())
            put("edge-cases", edgeCases())
            put("non-us-ratings", nonUsRatings())
        }

    // A run of [count] films that share their facts; the length and the year move on for each.
    @Suppress("LongParameterList") // The facts a run of films shares.
    private fun LibraryBuilder.films(
        count: Int,
        genres: List<String>,
        rating: String?,
        tags: List<String> = emptyList(),
        studios: List<String> = emptyList(),
        minutes: Int = 100,
        firstYear: Int = 1995,
    ) = repeat(count) { film(TitleSpec(genres, tags, studios, rating, minutes + it, firstYear + it)) }

    // One series with its own facts, for an edge case.
    @Suppress("LongParameterList") // The facts of one title.
    private fun LibraryBuilder.show(
        genres: List<String>,
        rating: String?,
        tags: List<String> = emptyList(),
        studios: List<String> = emptyList(),
        minutes: Int = 22,
        year: Int = 2010,
        specials: Int = 0,
    ) = series(TitleSpec(genres, tags, studios, rating, minutes, year), 2, 6, specials = specials)

    // An AniList-only server: no ratings at all, the genres and tags AniList sends. Every title is Adult by the tier
    // ladder (unrated), so no kids channel may take the kids-looking ones: Little Spirits is starved, by design.
    private fun aniList(): AuditInput {
        val b = LibraryBuilder(6)
        b.shows(10, listOf("Comedy", "Slice of Life", "Fantasy"), "", 24, listOf("Kids", "Magic"), 1, 6, 2010)
        b.shows(14, listOf("Action", "Adventure", "Fantasy"), "", 24, listOf("Male Protagonist"), 1, 12, 2004)
        b.shows(10, listOf("Drama", "Romance"), "", 24, listOf("School", "Love Triangle"), 1, 6, 2011)
        b.shows(8, listOf("Mecha", "Sci-Fi"), "", 24, listOf("Military", "Space"), 1, 12, 1998)
        b.shows(6, listOf("Comedy", "Ecchi"), "", 24, listOf("Nudity", "Harem"), 1, 6, 2013)
        b.shows(6, listOf("Horror", "Psychological"), "", 24, listOf("Gore"), 1, 6, 2009)
        b.shows(6, listOf("Sports", "Comedy"), "", 24, listOf("Male Protagonist"), 1, 12, 2014)
        b.films(12, listOf("Fantasy", "Adventure"), null, listOf("Magic"), listOf("Studio Invented"))
        // AniList sends no rating: the file holds null, not an empty string (shows() takes a String).
        return AuditInput(unrated(b.build()), null)
    }

    // A small library of the edge cases, over enough of an ordinary one for the planner to fill a lineup, with the
    // SYNTHETIC adult slice of the rating-safety tests added (never real titles).
    private fun edgeCases(): AuditInput {
        val b = LibraryBuilder(5)
        b.shows(10, listOf("Comedy"), "TV-PG", 22, listOf("sitcom"), 2, 6, 1994)
        b.shows(6, listOf("Drama"), "TV-14", 44, emptyList(), 2, 6, 2008)
        b.shows(4, listOf("Crime", "Drama"), "TV-MA", 46, emptyList(), 2, 6, 2010)
        b.shows(16, listOf("Animation", "Kids"), "TV-Y", 11, listOf("kids"), 2, 6, 2008)
        b.shows(8, listOf("Family", "Kids"), "TV-Y7", 22, listOf("kids"), 2, 6, 2012)
        b.shows(6, listOf("Animation", "Anime"), "TV-14", 24, listOf("anime"), 1, 12, 2002)
        b.films(10, listOf("Family", "Adventure"), "PG", minutes = 96, firstYear = 1999)
        b.films(8, listOf("Comedy", "Romance"), "PG-13", firstYear = 2001)
        b.films(6, listOf("Horror"), "R", listOf("slasher"), minutes = 98, firstYear = 1985)
        // Season 0 specials: extras the filter takes out, on a kids series and a grown-up one.
        b.show(listOf("Animation", "Kids"), "TV-Y", listOf("kids"), minutes = 11, year = 2015, specials = 3)
        b.show(listOf("Drama"), "TV-14", minutes = 44, year = 2012, specials = 4)
        // NR made for kids: kid genres, no rating. Unrated is Adult by the ladder, so no kids channel may take them.
        b.shows(3, listOf("Animation", "Kids"), "NR", 11, listOf("kids"), 2, 6, 2016)
        b.films(2, listOf("Animation", "Family", "Kids"), "NR", minutes = 80, firstYear = 2017)
        // Commentary riffs with no metadata at all: a bad-movie channel may take them by name; Odds & Ends 199 may not.
        repeat(4) {
            val bare = TitleSpec(emptyList(), emptyList(), emptyList(), null, 85 + it, 2008)
            b.film(bare, "RiffTrax: Invented Feature ${it + 1}")
        }
        // "Sunrise Productions" is not the Sunrise anime studio.
        b.shows(2, listOf("Drama"), "TV-14", 44, emptyList(), 2, 6, 2006)
        b.show(listOf("Drama"), "TV-14", studios = listOf("Sunrise Productions"), minutes = 44, year = 2007)
        b.show(listOf("Animation", "Anime"), "TV-14", listOf("anime"), listOf("Sunrise"), minutes = 24, year = 2003)
        // Horror tagged family or kids.
        b.show(listOf("Horror", "Family"), "TV-PG", year = 2010)
        b.show(listOf("Horror", "Kids"), "TV-Y7", listOf("kids"), year = 2011)
        b.films(1, listOf("Horror", "Family"), "PG", minutes = 92, firstYear = 2012)
        return AuditInput(unrated(withAdultSlice(b.build())), null)
    }

    // #1123: an ordinary library with titles that carry the ratings of other countries (the DVB-style ages), a bare age
    // and a string no table reads. The engine admits what its tables read; the oracle reads them on its own.
    private fun nonUsRatings(): AuditInput {
        val b = LibraryBuilder(7)
        b.shows(10, listOf("Comedy"), "TV-PG", 22, listOf("sitcom"), 2, 6, 1994)
        b.shows(6, listOf("Drama"), "TV-14", 44, emptyList(), 2, 6, 2008)
        b.shows(16, listOf("Animation", "Kids"), "TV-Y", 11, listOf("kids"), 2, 6, 2008)
        b.films(10, listOf("Family", "Adventure"), "PG", minutes = 96, firstYear = 1999)
        b.films(8, listOf("Comedy", "Romance"), "PG-13", firstYear = 2001)
        val ratings = listOf("DE-6", "DE-12", "DE-16", "DE-18", "6", "AU-PG", "GP", "PG13", "Rated PG", "SG-PG13")
        for (rating in ratings + "ZZ-invented") {
            b.shows(4, listOf("Animation", "Kids"), rating, 11, listOf("kids"), 2, 6, 2014)
            b.films(3, listOf("Family"), rating, minutes = 90, firstYear = 2015)
        }
        // #987: the board ratings (BBFC, FSK, Eirin, Australia, Singapore) in their spellings, one series and one film
        // each; the Explicit ones may air on After Hours alone, and no rating of 15 or older may reach a young band.
        for (rating in BOARD_RATINGS + EXPLICIT_BOARD_RATINGS) {
            b.shows(1, listOf("Animation", "Kids"), rating, 11, listOf("kids"), 1, 2, 2016)
            b.films(1, listOf("Family"), rating, minutes = 92, firstYear = 2017)
        }
        return AuditInput(unrated(b.build()), null)
    }

    private val BOARD_RATINGS =
        listOf(
            "U",
            "ALL",
            "GA",
            "ATP",
            "UNIVERSAL",
            "GB-U",
            "UK:U",
            "GB-PG",
            "12A",
            "GB-12A",
            "GB-15",
            "UK:15",
            "FSK-0",
            "FSK 6",
            "ab 6",
            "FSK-12",
            "FSK 16",
            "Germany:FSK 16",
            "DE:FSK 16",
            "DE-0",
            "Keine Jugendfreigabe",
            "JP-G",
            "Japan:G",
            "JP-PG12",
            "PG12",
            "JP-R15+",
            "R15+",
            "Australia:G",
            "AU-M",
            "MA 15+",
            "AU-MA15+",
            "KR-19",
            "JP-19",
            "JP-20",
            "FSK-7",
            "GB-12PG",
            "US:PG",
            "US:R",
        )

    // Explicit by name: only After Hours may air them (#1078), including the full country names (#987).
    private val EXPLICIT_BOARD_RATINGS =
        listOf(
            "GB-R18",
            "UK:R18",
            "United Kingdom:R18",
            "AU-X18+",
            "Australia:X18+",
            "Australia:R18+",
            "Japan:R18+",
            "AU-R",
            "SG-R21",
            "Singapore:R21",
        )

    // The builder's "" rating is what a series spec holds for no rating; the file stores it as none.
    private fun unrated(snapshot: RuleSnapshot): RuleSnapshot =
        snapshot.copy(
            items = snapshot.items.map { it.copy(officialRating = it.officialRating?.ifEmpty { null }) },
            series =
                snapshot.series.mapValues { (_, s) ->
                    s.copy(officialRating = s.officialRating?.ifEmpty { null })
                },
        )

    private fun withAdultSlice(base: RuleSnapshot): RuleSnapshot {
        val slice = SyntheticAdultSlice.build()
        return base.copy(
            libraries = base.libraries + slice.libraries,
            items = (base.items + slice.items).sortedBy { it.item.id.value },
            series = base.series + slice.series,
        )
    }
}
