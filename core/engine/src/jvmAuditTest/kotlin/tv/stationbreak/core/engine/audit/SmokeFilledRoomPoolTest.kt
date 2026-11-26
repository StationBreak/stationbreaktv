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
 * Catalog 25 (#1022): Smoke-Filled Room (Drama 272) airs the politics, political thriller, president, election and
 * white house tags, never a Documentary, minus the kids genres and anime. The library is synthetic.
 */
class SmokeFilledRoomPoolTest {
    private val library = LibraryBuilder(25)
    private val drama = listOf("Drama")
    private val thrillers =
        (1..14).map { library.film(TitleSpec(drama, listOf("political thriller"), emptyList(), "R", 120)) }
    private val ballot = library.film(TitleSpec(drama, listOf("election"), emptyList(), "PG-13", 115))
    private val oval = library.film(TitleSpec(drama, listOf("president", "white house"), emptyList(), "PG-13", 130))
    private val campaignSeries =
        library.series(TitleSpec(drama, listOf("politics"), emptyList(), "TV-14", 50), 2, 8)
    private val documentary =
        library.film(TitleSpec(listOf("Documentary"), listOf("politics"), emptyList(), "PG", 90))
    private val kids =
        library.film(TitleSpec(listOf("Animation", "Kids"), listOf("president"), emptyList(), "G", 80))
    private val anime = library.film(TitleSpec(listOf("Anime"), listOf("president", "anime"), emptyList(), "TV-14", 85))
    private val otherMovie = library.film(TitleSpec(drama, emptyList(), emptyList(), "PG-13", 100))

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
    fun eachPoliticalTagEnters() {
        for (name in thrillers + ballot + oval + campaignSeries) assertTrue(inPool(name), "$name is out")
    }

    @Test
    fun aDocumentaryKidsGenreAnimeAndAPlainDramaStayOut() {
        val out = listOf(documentary, kids, anime, otherMovie)
        for (name in out) assertFalse(inPool(name), "$name is in")
    }

    @Test
    fun theChannelQualifiesOnALibraryThatFillsIt() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        assertEquals(272, lane.channel.recipe.number)
        assertEquals(null, lane.channel.recipe.maxRating)
        val homes = LineupAudit.run(AuditInput(library.build(), null)).titles.filter { it.home?.channel == KEY }
        assertTrue(homes.size >= thrillers.size, "only ${homes.size} titles home on Smoke-Filled Room")
    }

    @Test
    fun noKidsTierTitleIsOnTheChannel() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        for (p in lane.cand.indices) assertFalse(lanes.titleOf(lane, p).madeForKids)
    }

    private companion object {
        const val KEY = "smoke-filled-room"
    }
}
