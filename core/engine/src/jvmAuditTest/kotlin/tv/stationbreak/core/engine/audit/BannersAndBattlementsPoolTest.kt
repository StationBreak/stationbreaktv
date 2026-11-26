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
 * Catalog 22 (#1020): Banners & Battlements (Drama 276) airs the History genre and the Rome, Greece, medieval and
 * knight tags, never a Documentary, minus the kids genres and anime. The library is synthetic.
 */
class BannersAndBattlementsPoolTest {
    private val library = LibraryBuilder(22)
    private val history = listOf("History")
    private val epics = (1..14).map { library.film(TitleSpec(history + "Drama", emptyList(), emptyList(), "R", 140)) }
    private val lower = library.film(TitleSpec(listOf("history"), emptyList(), emptyList(), "PG-13", 120))
    private val knight = library.film(TitleSpec(listOf("Drama"), listOf("knight"), emptyList(), "PG-13", 125))
    private val rome = library.film(TitleSpec(listOf("Drama"), listOf("ancient rome"), emptyList(), "R", 150))
    private val medievalSeries =
        library.series(TitleSpec(listOf("Drama"), listOf("medieval"), emptyList(), "TV-14", 50), 2, 8)
    private val documentary = library.film(TitleSpec(history + "Documentary", emptyList(), emptyList(), "PG", 90))
    private val tagDocumentary =
        library.film(TitleSpec(listOf("Documentary"), listOf("ancient greece"), emptyList(), "PG", 80))
    private val kids = library.film(TitleSpec(history + "Animation" + "Kids", emptyList(), emptyList(), "G", 80))
    private val anime = library.film(TitleSpec(history + "Anime", listOf("anime"), emptyList(), "TV-14", 85))
    private val otherMovie = library.film(TitleSpec(listOf("Drama"), emptyList(), emptyList(), "PG-13", 100))

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
    fun aHistoryFeatureAKnightDramaAndTheOtherTagsEnter() {
        for (name in epics + lower + knight + rome + medievalSeries) assertTrue(inPool(name), "$name is out")
    }

    @Test
    fun aDocumentaryKidsGenreAnimeAndAPlainDramaStayOut() {
        val out = listOf(documentary, tagDocumentary, kids, anime, otherMovie)
        for (name in out) assertFalse(inPool(name), "$name is in")
    }

    @Test
    fun theChannelQualifiesOnALibraryThatFillsIt() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        assertEquals(276, lane.channel.recipe.number)
        assertEquals(null, lane.channel.recipe.maxRating)
        val homes = LineupAudit.run(AuditInput(library.build(), null)).titles.filter { it.home?.channel == KEY }
        assertTrue(homes.size >= epics.size, "only ${homes.size} titles home on Banners & Battlements")
    }

    @Test
    fun noKidsTierTitleIsOnTheChannel() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        for (p in lane.cand.indices) assertFalse(lanes.titleOf(lane, p).madeForKids)
    }

    private companion object {
        const val KEY = "banners-and-battlements"
    }
}
