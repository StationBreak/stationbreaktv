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
 * Catalog 21 (#1019): Couch Premiere (General 194) airs movies of TMDb's `TV Movie` genre, minus the kids genres and
 * anime every grown-up recipe leaves out. The library is synthetic.
 */
class CouchPremierePoolTest {
    private val library = LibraryBuilder(21)
    private val bare = (1..14).map { library.film(TitleSpec(listOf("TV Movie"), emptyList(), emptyList(), null, 92)) }
    private val lower = library.film(TitleSpec(listOf("tv movie"), emptyList(), emptyList(), "PG-13", 90))
    private val mixed = library.film(TitleSpec(listOf("TV Movie", "Romance"), emptyList(), emptyList(), "TV-14", 88))
    private val kids =
        library.film(TitleSpec(listOf("TV Movie", "Animation", "Kids"), emptyList(), emptyList(), "G", 80))
    private val anime = library.film(TitleSpec(listOf("TV Movie", "Anime"), listOf("anime"), emptyList(), "TV-14", 85))
    private val series = library.series(TitleSpec(listOf("TV Movie"), emptyList(), emptyList(), "TV-PG", 45), 2, 8)
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
    fun aMovieWithOnlyTheTvMovieGenreEntersAndSoDoesOneInOtherLetterCase() {
        for (name in bare + lower + mixed) assertTrue(inPool(name), "$name is out")
    }

    @Test
    fun kidsGenreAnimeSeriesAndOtherMoviesStayOut() {
        for (name in listOf(kids, anime, series, otherMovie)) assertFalse(inPool(name), "$name is in")
    }

    @Test
    fun theChannelQualifiesOnALibraryThatFillsIt() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        assertEquals(194, lane.channel.recipe.number)
        assertEquals(null, lane.channel.recipe.maxRating)
        val homes = LineupAudit.run(AuditInput(library.build(), null)).titles.filter { it.home?.channel == KEY }
        assertTrue(homes.size >= bare.size, "only ${homes.size} titles home on Couch Premiere")
    }

    @Test
    fun noKidsTierTitleIsOnTheChannel() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        for (p in lane.cand.indices) assertFalse(lanes.titleOf(lane, p).madeForKids)
    }

    private companion object {
        const val KEY = "couch-premiere"
    }
}
