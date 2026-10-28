// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.episode
import tv.stationbreak.core.engine.idOf
import tv.stationbreak.core.engine.movie
import tv.stationbreak.core.engine.series
import tv.stationbreak.core.engine.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The leftovers pool: the snapshot minus what is claimed, under the same rating ceiling as `PoolEvaluator`. */
class LeftoversTest {
    private val show = idOf(0x10)

    @Test
    fun loneSeriesAirsOnLeftovers() {
        val snap =
            snapshot(
                items = listOf(episode(idOf(1), show), episode(idOf(2), show)),
                series = listOf(series(show)),
            )
        val pool = Leftovers.pool(snap, emptySet(), null, held = false)
        assertEquals(listOf(idOf(1), idOf(2)), pool.items.map { it.id })
        assertEquals(emptyList(), pool.counts.matchedBySource)
    }

    @Test
    fun claimedItemsAreLeftOut() {
        val snap = snapshot(items = listOf(movie(idOf(1)), movie(idOf(2)), movie(idOf(3))))
        val pool = Leftovers.pool(snap, setOf(idOf(2)), null, held = false)
        assertEquals(listOf(idOf(1), idOf(3)), pool.items.map { it.id })
    }

    @Test
    fun nothingLeftGivesEmptyPool() {
        val snap = snapshot(items = listOf(movie(idOf(1)), movie(idOf(2))), collectionsSynced = false)
        val pool = Leftovers.pool(snap, setOf(idOf(1), idOf(2)), "PG", held = false)
        assertEquals(emptyList(), pool.items)
        assertEquals(0, pool.counts.blockedByCeiling)
        assertEquals(false, pool.counts.collectionsSynced)
    }

    @Test
    fun kidsLeftoversAppliesCeiling() {
        val snap =
            snapshot(
                items =
                    listOf(
                        movie(idOf(1), rating = null),
                        movie(idOf(2), rating = "R"),
                        movie(idOf(3), rating = "G"),
                    ),
            )
        val pool = Leftovers.pool(snap, emptySet(), "PG", held = false)
        assertEquals(listOf(idOf(3)), pool.items.map { it.id })
        assertEquals(2, pool.counts.blockedByCeiling)
    }

    @Test
    fun episodeOfUnknownSeriesBlockedUnderCeiling() {
        val snap = snapshot(items = listOf(episode(idOf(1), show, rating = "G")))
        val under = Leftovers.pool(snap, emptySet(), "PG", held = false)
        assertEquals(emptyList(), under.items)
        assertEquals(1, under.counts.blockedByCeiling)
        assertEquals(1, under.counts.episodesWithUnknownSeries)
        assertEquals(1, Leftovers.pool(snap, emptySet(), null, held = false).items.size)
    }

    @Test
    fun aCeilingOffTheLadderIsRefusedLikeThePoolEvaluator() {
        assertFailsWith<IllegalArgumentException> { Leftovers.pool(snapshot(), emptySet(), "XX", held = false) }
    }

    @Test
    fun aHeldLeftoversChannelDropsGrownUpItemsAndKeepsKidsItemsUnderTheCeiling() {
        val snap =
            snapshot(
                items =
                    listOf(
                        movie(idOf(1), rating = "G", genres = listOf("Kids")),
                        movie(idOf(2), rating = "G", genres = listOf("Drama")),
                        movie(idOf(3), rating = "TV-PG", genres = listOf("Kids")),
                        movie(idOf(4), rating = "G", genres = listOf("Kids")),
                    ),
            )
        val pool = Leftovers.pool(snap, setOf(idOf(4)), "PG", held = true)
        assertEquals(listOf(idOf(1)), pool.items.map { it.id })
        assertEquals(1, pool.counts.heldOut)
        assertEquals(1, pool.counts.blockedByCeiling)
        val unheld = Leftovers.pool(snap, setOf(idOf(4)), "PG", held = false)
        assertEquals(0, unheld.counts.heldOut)
        assertEquals(listOf(idOf(1), idOf(2), idOf(3)), unheld.items.map { it.id })
    }

    @Test
    fun aHeldLeftoversEpisodeOfAnUnknownSeriesIsHeldOutAndNotCountedUnknown() {
        val snap = snapshot(items = listOf(episode(idOf(1), show, rating = "G")))
        val pool = Leftovers.pool(snap, emptySet(), "PG", held = true)
        assertEquals(1, pool.counts.heldOut)
        assertEquals(0, pool.counts.episodesWithUnknownSeries)
        assertEquals(0, pool.counts.blockedByCeiling)
    }
}
