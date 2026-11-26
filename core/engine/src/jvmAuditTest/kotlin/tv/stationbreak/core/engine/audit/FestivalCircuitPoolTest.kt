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
 * Catalog 28 (#1024): Festival Circuit (Genre 380) airs the `Indie` genre or the `independent film` and `sundance`
 * tags, movies only, minus the kids genres and anime. The library is synthetic.
 */
class FestivalCircuitPoolTest {
    private val library = LibraryBuilder(28)
    private val drama = listOf("Drama")
    private val indie = (1..12).map { library.film(TitleSpec(listOf("Indie"), emptyList(), emptyList(), "R", 100)) }
    private val tagged = library.film(TitleSpec(drama, listOf("independent film"), emptyList(), "R", 95))
    private val sundance = library.film(TitleSpec(drama, listOf("sundance"), emptyList(), "PG-13", 105))
    private val indieSeries = library.series(TitleSpec(listOf("Indie"), emptyList(), emptyList(), "TV-14", 30), 2, 8)
    private val plain = library.film(TitleSpec(drama, emptyList(), emptyList(), "PG-13", 100))
    private val kidsGenres = listOf("Animation", "Kids")
    private val kids = library.film(TitleSpec(kidsGenres, listOf("independent film"), emptyList(), "G", 80))
    private val anime = library.film(TitleSpec(listOf("Anime"), listOf("independent film"), emptyList(), "TV-14", 85))

    init {
        library.shows(10, listOf("Comedy"), "TV-PG", 22, listOf("sitcom"), 2, 6, 1994)
        library.shows(12, listOf("Animation", "Kids"), "TV-Y", 11, listOf("kids"), 2, 6, 2008)
        library.shows(6, listOf("Drama"), "TV-14", 44, emptyList(), 2, 6, 2008)
    }

    private val lanes: AuditLanes
    private val poolIds: Set<CanonicalId>

    init {
        val snapshot = ExtrasFilter.filter(library.build()).snapshot
        lanes = AuditLanes(PlannedLineup.of(snapshot, CollectionNames(emptyMap()), null))
        val lane = lanes.lanes.firstOrNull { it.channel.recipe.key == KEY }
        poolIds = lane?.let { l -> l.cand.indices.mapTo(HashSet()) { lanes.titleOf(l, it).id } }.orEmpty()
    }

    private fun inPool(name: String) = library.titleIds.getValue(name) in poolIds

    @Test
    fun anIndieGenreFilmAndEachTagEnter() {
        for (name in indie + tagged + sundance) assertTrue(inPool(name), "$name is out")
    }

    @Test
    fun aSeriesAPlainFilmKidsAndAnimeStayOut() {
        for (name in listOf(indieSeries, plain, kids, anime)) assertFalse(inPool(name), "$name is in")
    }

    @Test
    fun theChannelQualifiesOnALibraryThatFillsIt() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        assertEquals(380, lane.channel.recipe.number)
        assertEquals(null, lane.channel.recipe.maxRating)
        val homes = LineupAudit.run(AuditInput(library.build(), null)).titles.filter { it.home?.channel == KEY }
        assertTrue(homes.size >= indie.size, "only ${homes.size} titles home on Festival Circuit")
    }

    @Test
    fun noKidsTierTitleIsOnTheChannel() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        for (p in lane.cand.indices) assertFalse(lanes.titleOf(lane, p).madeForKids)
    }

    private companion object {
        const val KEY = "festival-circuit"
    }
}
