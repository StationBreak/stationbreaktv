// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem

/**
 * Spec 10 task 1.5.3: an episode's `seasonId` is part of the item change test, so a change of season alone makes a
 * version, and `heldAsOf` returns the season as this TV held it before the cut-off.
 */
class EpisodeSeasonVersionTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val start = clock.nowMs

    @After
    fun close() = repository.close()

    private fun episode(season: String?): MediaItem =
        sitem(1).copy(kind = ItemKind.Episode, seriesId = ItemId(hexId(900)), seasonId = season?.let(::ItemId))

    private suspend fun held(cutoffMs: Long) =
        repository.history.heldAsOf(ALICE.server.value, ALICE.user.value, cutoffMs, 100, setOf(MOVIES.value)).single()

    @Test
    fun aSeasonChangeAloneMakesAnItemVersion() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, episode("season-a"))
            clock.stepMs(5_000)

            val write = repository.put(ALICE, MOVIES, episode("season-b"))

            assertEquals(PageWrite(inserted = 0, updated = 1, unchanged = 0), write)
            val version = repository.versions(ALICE).single()
            assertEquals("season-a", version.seasonId)
            assertEquals(start + 5_000, version.supersededAtMs)
            assertEquals("season-b", repository.currentRows(ALICE).single().seasonId)
            repository.assertIntervalsContiguous(ALICE)
        }

    @Test
    fun anEpisodeThatGainsAOrLosesASeasonFolderIsAChangeToo() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, episode(null))
            clock.stepMs(1_000)
            repository.put(ALICE, MOVIES, episode("season-a"))
            clock.stepMs(1_000)
            repository.put(ALICE, MOVIES, episode(null))

            val earlier = repository.versions(ALICE).sortedBy { it.seenFromMs }
            assertEquals(listOf(null, "season-a"), earlier.map { it.seasonId })
            assertNull(repository.currentRows(ALICE).single().seasonId)
        }

    @Test
    fun theSameSeasonWrittenAgainIsUnchanged() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, episode("season-a"))

            val again = repository.put(ALICE, MOVIES, episode("season-a"))

            assertEquals(PageWrite(inserted = 0, updated = 0, unchanged = 1), again)
            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
        }

    @Test
    fun heldAsOfReturnsTheSeasonAsHeldBeforeTheCutoff() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, episode("season-a"))
            clock.stepMs(10_000)
            repository.put(ALICE, MOVIES, episode("season-b"))

            assertEquals("season-a", held(start + 5_000).seasonId)
            assertEquals("season-b", held(start + 20_000).seasonId)
            // Before anything this TV held: the oldest version.
            assertEquals("season-a", held(start - 60_000).seasonId)
        }

    @Test
    fun anItemWithNoSeasonIsHeldWithNone() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.put(ALICE, MOVIES, sitem(1))

            assertNull(held(start + 5_000).seasonId)
        }
}
