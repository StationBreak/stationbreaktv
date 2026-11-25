// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/** #1153: one episode in a small collection must not make a channel the whole series' home. */
class CurationAnchorShareTest {
    private class SmallAnchor(
        episodesInCollection: Int,
    ) {
        val lib = Library()
        val series = lib.show(eps = 20, genres = listOf("Comedy"))
        val episodes = lib.itemsOf(listOf(series))
        val own = lib.film(ms = 6 * HOUR, genres = listOf("Romance"))
        val box = idOf(0x0F00)
        val big = channel("ch-big", 102, listOf("Comedy"), episodes)
        val small: CurationChannel

        init {
            val inBox = episodes.take(episodesInCollection)
            lib.collections += collection(box, episodes = inBox.mapTo(HashSet()) { it.id })
            val pool = inBox + lib.itemsOf(listOf(own))
            small = channel("ch-small", 162, listOf("Romance"), pool, anchors = listOf(Source.Collection(box)))
        }
    }

    @Test
    fun anAnchorThatCannotAirAQuarterOfATitleIsOnlyAGuest() {
        for (count in 1..2) {
            val x = SmallAnchor(count)
            val snap = x.lib.snapshot()
            val homes = Curation.homes(snap, listOf(x.small, x.big))
            assertEquals(x.big.id, homes.homeOf(x.series), "$count anchored episodes")
            assertEquals(x.small.id, homes.homeOf(x.own))
            // Still a guest on the anchored channel, with its own episodes airing there.
            val pools = Curation.pools(snap, listOf(x.small, x.big))
            val guestEpisodes = pools.getValue(x.small.id).items.filter { it.groupId == x.series }
            assertEquals(count, guestEpisodes.size)
            assertEquals(20, pools.getValue(x.big.id).items.count { it.groupId == x.series })
        }
    }

    @Test
    fun anAnchorThatAirsAQuarterOfATitleStillWinsTheHome() {
        val x = SmallAnchor(5)
        assertEquals(x.small.id, Curation.homes(x.lib.snapshot(), listOf(x.small, x.big)).homeOf(x.series))
    }

    @Test
    fun withNoChannelAbleToAirAQuarterTheAnchorsStillHome() {
        val lib = Library()
        val s = lib.show(eps = 5, genres = listOf("Comedy"))
        val eps = lib.itemsOf(listOf(s))
        val channels =
            eps.mapIndexed { i, ep ->
                val box = idOf(0x0F01 + i)
                lib.collections += collection(box, episodes = setOf(ep.id))
                channel("ch-$i", 100 + i, listOf("Comedy"), listOf(ep), anchors = listOf(Source.Collection(box)))
            }
        assertEquals(channels[0].id, Curation.homes(lib.snapshot(), channels).homeOf(s))
    }
}
