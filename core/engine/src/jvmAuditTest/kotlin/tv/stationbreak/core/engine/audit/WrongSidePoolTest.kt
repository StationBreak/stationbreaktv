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
 * Catalog 24 (#1017): Wrong Side (Drama 210) airs the Crime genre without the Mystery genre and without the
 * `detective`, `whodunit` and `police procedural` tags, minus the kids genres and anime. The library is synthetic.
 */
class WrongSidePoolTest {
    private val library = LibraryBuilder(24)
    private val crime = listOf("Crime", "Drama")
    private val capers = (1..14).map { library.film(TitleSpec(crime, emptyList(), emptyList(), "R", 110)) }
    private val crimeSeries = library.series(TitleSpec(crime, emptyList(), emptyList(), "TV-14", 45), 2, 8)
    private val mystery = library.film(TitleSpec(crime + "Mystery", emptyList(), emptyList(), "PG-13", 105))
    private val detective = library.film(TitleSpec(crime, listOf("detective"), emptyList(), "PG-13", 105))
    private val whodunit = library.film(TitleSpec(crime, listOf("whodunit"), emptyList(), "PG-13", 105))
    private val procedural = library.film(TitleSpec(crime, listOf("police procedural"), emptyList(), "R", 105))
    private val kids = library.film(TitleSpec(crime + "Animation" + "Kids", emptyList(), emptyList(), "G", 80))
    private val anime = library.film(TitleSpec(crime + "Anime", listOf("anime"), emptyList(), "TV-14", 85))
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
    fun aCrimeFilmAndACrimeSeriesWithoutAMysteryEnter() {
        for (name in capers + crimeSeries) assertTrue(inPool(name), "$name is out")
    }

    @Test
    fun aMysteryTheDetectiveTagsKidsGenreAnimeAndAPlainDramaStayOut() {
        val out = listOf(mystery, detective, whodunit, procedural, kids, anime, otherMovie)
        for (name in out) assertFalse(inPool(name), "$name is in")
    }

    @Test
    fun theChannelQualifiesOnALibraryThatFillsIt() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        assertEquals(210, lane.channel.recipe.number)
        assertEquals(null, lane.channel.recipe.maxRating)
        val homes = LineupAudit.run(AuditInput(library.build(), null)).titles.filter { it.home?.channel == KEY }
        assertTrue(homes.size >= capers.size, "only ${homes.size} titles home on Wrong Side")
    }

    @Test
    fun noKidsTierTitleIsOnTheChannel() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        for (p in lane.cand.indices) assertFalse(lanes.titleOf(lane, p).madeForKids)
    }

    private companion object {
        const val KEY = "wrong-side"
    }
}
