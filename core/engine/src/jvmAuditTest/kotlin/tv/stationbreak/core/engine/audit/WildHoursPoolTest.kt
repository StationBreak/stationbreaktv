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
 * Catalog 29 (#1025): Wild Hours (General 192) airs the `nature`, `wildlife`, `animals` and `ocean` tags filtered to
 * the `Documentary` genre, minus the kids genres and anime. The library is synthetic.
 */
class WildHoursPoolTest {
    private val library = LibraryBuilder(29)
    private val doc = listOf("Documentary")
    private val nature = (1..12).map { library.film(TitleSpec(doc, listOf("nature"), emptyList(), "G", 90)) }
    private val wildlife = library.film(TitleSpec(doc, listOf("wildlife"), emptyList(), "PG", 85))
    private val ocean = library.film(TitleSpec(doc, listOf("ocean"), emptyList(), "PG", 80))
    private val series = library.series(TitleSpec(doc, listOf("animals"), emptyList(), "TV-G", 50), 2, 6)
    private val untagged = library.film(TitleSpec(doc, listOf("history"), emptyList(), "PG-13", 95))
    private val drama = library.film(TitleSpec(listOf("Drama"), listOf("nature"), emptyList(), "PG-13", 100))
    private val kidsGenres = listOf("Documentary", "Kids")
    private val kids = library.film(TitleSpec(kidsGenres, listOf("nature"), emptyList(), "TV-Y", 60))
    private val animeGenres = listOf("Documentary", "Anime")
    private val anime = library.film(TitleSpec(animeGenres, listOf("nature"), emptyList(), "TV-14", 85))

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
    fun aDocumentaryWithAnyOfTheNatureTagsEnters() {
        for (name in nature + wildlife + ocean + series) assertTrue(inPool(name), "$name is out")
    }

    @Test
    fun anUntaggedDocumentaryANatureDramaKidsAndAnimeStayOut() {
        for (name in listOf(untagged, drama, kids, anime)) assertFalse(inPool(name), "$name is in")
    }

    @Test
    fun theChannelQualifiesOnALibraryThatFillsIt() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        assertEquals(192, lane.channel.recipe.number)
        assertEquals(null, lane.channel.recipe.maxRating)
        val homes = LineupAudit.run(AuditInput(library.build(), null)).titles.filter { it.home?.channel == KEY }
        assertTrue(homes.size >= nature.size, "only ${homes.size} titles home on Wild Hours")
    }

    @Test
    fun noKidsTierTitleIsOnTheChannel() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        for (p in lane.cand.indices) assertFalse(lanes.titleOf(lane, p).madeForKids)
    }

    private companion object {
        const val KEY = "wild-hours"
    }
}
