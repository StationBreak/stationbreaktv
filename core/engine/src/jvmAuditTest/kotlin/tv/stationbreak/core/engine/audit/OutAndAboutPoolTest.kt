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
 * Catalog 27 (#1023): Out & About (Drama 284) airs the `lgbt`, `gay`, `lesbian`, `transgender` and `bisexual` tags,
 * minus the kids genres and anime. A tag matches whole and without regard to case, as every tag source does. The
 * library is synthetic.
 */
class OutAndAboutPoolTest {
    private val library = LibraryBuilder(27)
    private val drama = listOf("Drama")
    private val lgbt = (1..14).map { library.film(TitleSpec(drama, listOf("lgbt"), emptyList(), "R", 110)) }
    private val lesbian = library.film(TitleSpec(drama, listOf("lesbian"), emptyList(), "R", 105))
    private val transgender = library.film(TitleSpec(drama, listOf("transgender"), emptyList(), "PG-13", 100))
    private val bisexual = library.film(TitleSpec(drama, listOf("bisexual"), emptyList(), "R", 95))
    private val capital = library.film(TitleSpec(drama, listOf("Gay"), emptyList(), "R", 100))
    private val series = library.series(TitleSpec(drama, listOf("lgbt"), emptyList(), "TV-14", 45), 2, 8)
    private val plain = library.film(TitleSpec(drama, emptyList(), emptyList(), "PG-13", 100))
    private val longerTag = library.film(TitleSpec(drama, listOf("gay marriage", "lgbtq"), emptyList(), "R", 100))
    private val kids = library.film(TitleSpec(listOf("Animation", "Kids"), listOf("lgbt"), emptyList(), "G", 80))
    private val anime = library.film(TitleSpec(listOf("Anime"), listOf("lgbt", "anime"), emptyList(), "TV-14", 85))

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
    fun eachTagEntersAndACapitalisedTagMatches() {
        val tagged = lgbt + lesbian + transgender + bisexual + capital + series
        for (name in tagged) assertTrue(inPool(name), "$name is out")
    }

    @Test
    fun noTagKidsGenreAnimeOrLongerTagStaysOut() {
        // A tag matches whole: `gay marriage` and `lgbtq` are not `gay` and `lgbt`.
        for (name in listOf(plain, longerTag, kids, anime)) assertFalse(inPool(name), "$name is in")
    }

    @Test
    fun theChannelQualifiesOnALibraryThatFillsIt() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        assertEquals(284, lane.channel.recipe.number)
        assertEquals(null, lane.channel.recipe.maxRating)
        val homes = LineupAudit.run(AuditInput(library.build(), null)).titles.filter { it.home?.channel == KEY }
        assertTrue(homes.size >= lgbt.size, "only ${homes.size} titles home on Out & About")
    }

    @Test
    fun noKidsTierTitleIsOnTheChannel() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        for (p in lane.cand.indices) assertFalse(lanes.titleOf(lane, p).madeForKids)
    }

    private companion object {
        const val KEY = "out-and-about"
    }
}
