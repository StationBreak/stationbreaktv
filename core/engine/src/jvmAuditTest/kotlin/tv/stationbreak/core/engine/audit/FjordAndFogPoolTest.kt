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
 * Catalog 30 (#1026): Fjord & Fog (World 444) airs the Nordic tags and the Nordic networks (`DR1`, `SVT1`, `NRK1`,
 * `TV 2` exactly, anything starting `Yle`) filtered to `Crime`, `Mystery`, `Thriller` or `Drama`, minus the kids genres
 * and anime. The library is synthetic.
 */
class FjordAndFogPoolTest {
    private val library = LibraryBuilder(30)
    private val crime = listOf("Crime")
    private val noirSpec = TitleSpec(crime, listOf("nordic noir"), emptyList(), "TV-MA", 50)
    private val noir = (1..12).map { library.series(noirSpec, 2, 8) }
    private val sweden = library.series(TitleSpec(crime, listOf("sweden"), emptyList(), "TV-14", 50), 2, 8)
    private val dr1 = library.series(TitleSpec(listOf("Drama"), emptyList(), listOf("DR1"), "TV-14", 50), 2, 8)
    private val nrk1 = library.series(TitleSpec(listOf("Thriller"), emptyList(), listOf("NRK1"), "TV-14", 50), 2, 8)
    private val yle = library.series(TitleSpec(crime, emptyList(), listOf("Yle Areena"), "TV-14", 50), 2, 8)
    private val comedy = library.series(TitleSpec(listOf("Comedy"), emptyList(), listOf("SVT1"), "TV-14", 25), 2, 8)
    private val lookAlike = library.series(TitleSpec(crime, emptyList(), listOf("TV 2 Australia"), "TV-14", 50), 2, 8)
    private val exactTv2 = library.series(TitleSpec(crime, emptyList(), listOf("TV 2"), "TV-14", 50), 2, 8)
    private val kidsGenres = listOf("Crime", "Kids")
    private val kids = library.series(TitleSpec(kidsGenres, listOf("norway"), emptyList(), "TV-Y", 22), 2, 8)
    private val animeGenres = listOf("Crime", "Anime")
    private val anime = library.series(TitleSpec(animeGenres, listOf("nordic noir"), emptyList(), "TV-14", 24), 2, 8)

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
    fun aNordicTagOrNetworkTitleInOneOfTheGenresEnters() {
        for (name in noir + sweden + dr1 + nrk1 + yle + exactTv2) assertTrue(inPool(name), "$name is out")
    }

    @Test
    fun theWrongGenreAShortNameLookAlikeKidsAndAnimeStayOut() {
        for (name in listOf(comedy, lookAlike, kids, anime)) assertFalse(inPool(name), "$name is in")
    }

    @Test
    fun theChannelQualifiesOnALibraryThatFillsIt() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        assertEquals(444, lane.channel.recipe.number)
        assertEquals(null, lane.channel.recipe.maxRating)
        val homes = LineupAudit.run(AuditInput(library.build(), null)).titles.filter { it.home?.channel == KEY }
        assertTrue(homes.size >= noir.size, "only ${homes.size} titles home on Fjord & Fog")
    }

    @Test
    fun noKidsTierTitleIsOnTheChannel() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        for (p in lane.cand.indices) assertFalse(lanes.titleOf(lane, p).madeForKids)
    }

    private companion object {
        const val KEY = "fjord-and-fog"
    }
}
