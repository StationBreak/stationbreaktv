// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // The shape of a library is its data: counts, minutes and years are written as numbers.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.lineup.curation.LaneBuilder
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Catalog 19 (#1030): The Cheap Seats anchors on the riff shows of The Mads and Total Riff Off by title, and Deep Space
 * Drift keeps riff content out of its candidates. The library is synthetic: the show tokens in the names are matcher
 * strings only, the rest is invented.
 */
class RiffShowsTest {
    private val scifi = listOf("Science Fiction")
    private val library = LibraryBuilder(19)
    private val riffed =
        library.series(
            TitleSpec(scifi, listOf("space"), emptyList(), "TV-PG", 90),
            5,
            24,
            name = "Mystery Science Theater Bravelmi",
        )
    private val opera =
        List(6) { library.series(TitleSpec(scifi, listOf("space", "space opera"), emptyList(), "TV-14", 44), 5, 22) }
    private val bare = TitleSpec(emptyList(), emptyList(), emptyList(), null, 118, 1970)
    private val mads = library.film(bare, "TheMadsAreBack-Torsenda From 10K Leagues")
    private val bestOf = library.film(bare, "TheBestOfTheMadsAreBack-Quinsel Of Shorts")
    private val totalRiffOff = library.film(bare, "TotalRiffOffEp2Dartovel")
    private val older = TitleSpec(emptyList(), emptyList(), emptyList(), null, 100, 1980)
    private val riffTrax = List(2) { library.film(older, "RiffTrax Invented ${it + 1}") }
    private val caped = library.film(bare, "CapedCrusaderTakesOver")

    init {
        library.shows(10, listOf("Comedy"), "TV-PG", 22, listOf("sitcom"), 2, 6, 1994)
        library.shows(12, listOf("Animation", "Kids"), "TV-Y", 11, listOf("kids"), 2, 6, 2008)
        library.shows(6, listOf("Drama"), "TV-14", 44, emptyList(), 2, 6, 2008)
    }

    private val snapshot = ExtrasFilter.filter(library.build()).snapshot
    private val lanes = AuditLanes(PlannedLineup.of(snapshot, CollectionNames(emptyMap()), null))
    private val report = LineupAudit.run(AuditInput(library.build(), null))

    private fun poolOf(key: String): Set<CanonicalId> {
        val lane = lanes.lanes.firstOrNull { it.channel.recipe.key == key } ?: return emptySet()
        return lane.cand.indices.mapTo(HashSet()) { lanes.titleOf(lane, it).id }
    }

    private fun homeOf(name: String) =
        report.titles
            .single { it.name == name }
            .home
            ?.channel

    private fun inPool(
        key: String,
        name: String,
    ) = library.titleIds.getValue(name) in poolOf(key)

    @Test
    fun theRiffSeriesHomesOnTheCheapSeatsAndIsNotOnDeepSpaceDrift() {
        assertTrue(inPool(CHEAP_SEATS, riffed))
        assertEquals(CHEAP_SEATS, homeOf(riffed))
        assertFalse(inPool(DRIFT, riffed), "a guest on Deep Space Drift")
    }

    @Test
    fun deepSpaceDriftStillHomesSpaceOpera() {
        for (name in opera) assertTrue(inPool(DRIFT, name), "$name is out")
        assertTrue(opera.any { homeOf(it) == DRIFT })
    }

    @Test
    fun filesWithNoMetadataEnterTheCheapSeatsByTitleAndLeaveOddsAndEnds() {
        for (name in listOf(mads, bestOf, totalRiffOff)) {
            assertTrue(inPool(CHEAP_SEATS, name), "$name is out of The Cheap Seats")
            assertEquals(CHEAP_SEATS, homeOf(name), name)
            assertFalse(inPool(DRIFT, name), name)
        }
    }

    @Test
    fun theShippedRiffTraxAnchorStillWorks() {
        for (name in riffTrax) {
            assertTrue(inPool(CHEAP_SEATS, name), name)
            assertFalse(inPool(DRIFT, name), name)
        }
    }

    @Test
    fun aBatmanLikeTitleWithNoRiffTokenIsNotPulledIn() {
        assertFalse(inPool(CHEAP_SEATS, caped))
        assertFalse(homeOf(caped) == CHEAP_SEATS)
    }

    @Test
    fun noKidsTierTitleIsOnEitherChannel() {
        for (key in listOf(CHEAP_SEATS, DRIFT)) {
            val lane = lanes.lanes.single { it.channel.recipe.key == key }
            for (p in lane.cand.indices) assertFalse(lanes.titleOf(lane, p).madeForKids, key)
        }
    }

    // The catalog 18 recipes, as the audit counts a change: the same lanes without the new anchors and the keep-out.
    @Test
    fun withoutTheKeepOutTheRiffSeriesIsADeepSpaceDriftCandidate() {
        val ch = lanes.channels.single { it.recipe.key == DRIFT }
        val theme = checkNotNull(ch.recipe.theme)
        val recipe = ch.recipe.copy(theme = theme.copy(keepOutTitles = emptyList()))
        val oldChannel = CurationChannel(ch.id, ch.number, recipe, ch.kid, ch.rules, ch.settings, ch.pool)
        val lane = LaneBuilder(snapshot, listOf(oldChannel))
        val old =
            lane.lanes
                .single()
                .cand
                .mapTo(HashSet()) { lane.titles[it].title.id }
        assertTrue(library.titleIds.getValue(riffed) in old, "the keep-out is what takes it out")
    }

    private companion object {
        const val CHEAP_SEATS = "the-cheap-seats"
        const val DRIFT = "deep-space-drift"
    }
}
