// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The shape of a library is its data: counts, minutes and years are written as numbers.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Catalog 26 (#1021): Short Stack (General 198) airs the `Short` genre or the `short film` tag, movies of at most 40
 * minutes, minus the kids genres and anime, on the Niche bar. The library is synthetic.
 */
class ShortStackPoolTest {
    private fun film(
        library: LibraryBuilder,
        genres: List<String>,
        minutes: Int,
        tags: List<String> = emptyList(),
    ) = library.film(TitleSpec(genres, tags, emptyList(), "PG-13", minutes))

    private class Built(
        val library: LibraryBuilder,
        val lanes: AuditLanes,
    ) {
        val poolIds: Set<CanonicalId> =
            lanes.lanes
                .firstOrNull { it.channel.recipe.key == KEY }
                ?.let { l -> l.cand.indices.mapTo(HashSet()) { lanes.titleOf(l, it).id } }
                .orEmpty()

        fun inPool(name: String) = library.titleIds.getValue(name) in poolIds
    }

    private fun build(fill: (LibraryBuilder) -> Unit): Built {
        val library = LibraryBuilder(26)
        fill(library)
        library.shows(10, listOf("Comedy"), "TV-PG", 22, listOf("sitcom"), 2, 6, 1994)
        library.shows(6, listOf("Drama"), "TV-14", 44, emptyList(), 2, 6, 2008)
        val snapshot = ExtrasFilter.filter(library.build()).snapshot
        return Built(library, AuditLanes(PlannedLineup.of(snapshot, CollectionNames(emptyMap()), null)))
    }

    // The selection stats: the channel homed over every candidate. A library this small is too thin for the planner to
    // switch a channel on, so "qualifies" is judged here, as the planner's selection judges it.
    private fun stats(built: Built) =
        LineupAudit
            .run(AuditInput(built.library.build(), null))
            .starters
            .single { it.key == KEY }
            .selection

    @Test
    fun shortFilmsUnderFortyMinutesEnterByGenreOrTag() {
        lateinit var genre: String
        lateinit var tagged: String
        val built =
            build {
                genre = film(it, listOf("Short"), 25)
                tagged = film(it, listOf("Drama"), 35, listOf("short film"))
            }
        assertTrue(built.inPool(genre), "$genre is out")
        assertTrue(built.inPool(tagged), "$tagged is out")
    }

    @Test
    fun aLongFilmASeriesKidsAndAnimeStayOut() {
        lateinit var long: String
        lateinit var series: String
        lateinit var kids: String
        lateinit var anime: String
        val built =
            build {
                long = film(it, listOf("Short"), 55)
                series = it.series(TitleSpec(listOf("Short"), emptyList(), emptyList(), "TV-PG", 22), 2, 6)
                kids = film(it, listOf("Short", "Kids"), 12)
                anime = film(it, listOf("Short", "Anime"), 20, listOf("anime"))
            }
        for (name in listOf(long, series, kids, anime)) assertFalse(built.inPool(name), "$name is in")
    }

    @Test
    fun theChannelQualifiesOnNicheHoursAndTitlesAndNotOnThree() {
        val enough = build { lib -> repeat(12) { film(lib, listOf("Short"), 35) } }
        assertEquals(
            198,
            enough.lanes.lanes
                .single { it.channel.recipe.key == KEY }
                .channel.recipe.number,
        )
        assertEquals(
            null,
            enough.lanes.lanes
                .single { it.channel.recipe.key == KEY }
                .channel.recipe.maxRating,
        )
        assertEquals(12, stats(enough)?.titles)
        assertTrue(stats(enough)?.qualifies == true, "twelve 35-minute shorts (7 h) miss the Niche bar")

        val three = build { lib -> repeat(3) { film(lib, listOf("Short"), 38) } }
        assertEquals(3, stats(three)?.titles)
        assertFalse(stats(three)?.qualifies == true, "three titles clear the Niche bar")
    }

    @Test
    fun noKidsTierTitleIsOnTheChannel() {
        val built =
            build { lib ->
                repeat(8) { film(lib, listOf("Short"), 30) }
                film(lib, listOf("Short", "Kids"), 10)
            }
        val lane = built.lanes.lanes.single { it.channel.recipe.key == KEY }
        for (p in lane.cand.indices) assertFalse(built.lanes.titleOf(lane, p).madeForKids)
    }

    private companion object {
        const val KEY = "short-stack"
    }
}
