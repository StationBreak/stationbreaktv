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
 * Catalog 20 (#1033): Prestige airs drama from the Apple TV, HBO, AMC and FX networks (with a Drama filter) and the
 * `prestige` and `drama series` tags, not the bare Drama genre. The library is synthetic: the network names are
 * matcher strings only, the rest is invented.
 */
class PrestigePoolTest {
    private val drama = listOf("Drama")
    private val library = LibraryBuilder(20)
    private val long =
        library.series(TitleSpec(drama + "Science Fiction", listOf("space"), emptyList(), "TV-14", 44), 8, 24)
    private val networks =
        listOf("HBO", "Apple TV", "Apple TV+", "AMC", "FX").map {
            library.series(TitleSpec(drama, emptyList(), listOf(it), "TV-MA", 55), 3, 10)
        }
    private val comedy = library.series(TitleSpec(listOf("Comedy"), emptyList(), listOf("HBO"), "TV-MA", 30), 3, 10)
    private val tagged = library.series(TitleSpec(drama, listOf("prestige"), emptyList(), "TV-MA", 50), 3, 10)
    private val british = library.series(TitleSpec(drama, emptyList(), listOf("BBC One"), "TV-14", 50), 3, 10)
    private val fxOther = library.series(TitleSpec(drama, emptyList(), listOf("FX Networks Asia"), "TV-14", 50), 3, 10)

    init {
        library.shows(10, listOf("Comedy"), "TV-PG", 22, listOf("sitcom"), 2, 6, 1994)
        library.shows(12, listOf("Animation", "Kids"), "TV-Y", 11, listOf("kids"), 2, 6, 2008)
        library.shows(6, drama, "TV-14", 44, emptyList(), 2, 6, 2008)
    }

    private val lanes: AuditLanes
    private val poolIds: Set<CanonicalId>
    private val report = LineupAudit.run(AuditInput(library.build(), null))

    init {
        val snapshot = ExtrasFilter.filter(library.build()).snapshot
        lanes = AuditLanes(PlannedLineup.of(snapshot, CollectionNames(emptyMap()), null))
        val lane = lanes.lanes.firstOrNull { it.channel.recipe.key == KEY }
        poolIds = lane?.let { l -> l.cand.indices.mapTo(HashSet()) { lanes.titleOf(l, it).id } }.orEmpty()
    }

    private fun inPool(name: String) = library.titleIds.getValue(name) in poolIds

    private fun homeOf(name: String) =
        report.titles
            .single { it.name == name }
            .home
            ?.channel

    @Test
    fun aLongDramaWithNoNetworkIsNeitherInThePoolNorAHome() {
        assertFalse(inPool(long))
        assertFalse(homeOf(long) == KEY)
    }

    @Test
    fun aDramaFromEachListedNetworkIsInThePoolAndPrestigeIsNotEmpty() {
        for (name in networks) assertTrue(inPool(name), "$name is out")
        assertTrue(networks.any { homeOf(it) == KEY }, "no network drama homes on Prestige")
    }

    @Test
    fun aComedyFromTheNetworkIsOut() {
        assertFalse(inPool(comedy))
    }

    @Test
    fun aPrestigeTaggedDramaIsInButTheChannelDoesNotRelyOnTheTagAlone() {
        assertTrue(inPool(tagged))
        assertTrue(poolIds.size > 1)
        assertTrue(networks.all { inPool(it) })
    }

    @Test
    fun aBritishNetworkDramaAndAnFxNamedOtherStudioAreNotPrestige() {
        assertFalse(inPool(british))
        assertFalse(inPool(fxOther), "Exact FX, not Contains")
        assertEquals(false, homeOf(british) == KEY)
    }

    @Test
    fun noKidsTierTitleIsOnPrestige() {
        val lane = lanes.lanes.single { it.channel.recipe.key == KEY }
        for (p in lane.cand.indices) assertFalse(lanes.titleOf(lane, p).madeForKids)
    }

    private companion object {
        const val KEY = "prestige"
    }
}
