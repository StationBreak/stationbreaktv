// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem

/**
 * Spec 26, schema 11: when a library is deleted (an untick, a hold that expired, a dropped add), each item it owns
 * that another staying library lists moves to that library instead of going with the cascade: a chosen one first, then
 * one being added, then a held one, then the smallest id. The move is versioned, takes the lister's row away, carries
 * the coverage, and runs before the shared series are re-homed, so a moved episode keeps its series. Invented
 * libraries and names only.
 */
class DeleteMoveTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val gone = ItemId(hexId(0xB000))

    @After
    fun close() = repository.close()

    private suspend fun generation() = repository.contentGeneration(ALICE)

    private suspend fun select(
        keep: Set<ItemId>,
        stamps: Map<ItemId, Long> = emptyMap(),
        holds: Map<ItemId, Long> = emptyMap(),
    ) = repository.applySelection(ALICE, keep, stamps, holds, clock.nowMs)

    private suspend fun stamped(
        library: ItemId,
        stamp: Long,
        vararg items: MediaItem,
    ) = repository.upsertItems(ALICE, library, stamp, rewalk = false, items.toList()).written()

    @Test
    fun overlap_untickB_itemStaysInA() =
        runBlocking {
            select(setOf(MOVIES, SHOWS))
            repository.put(ALICE, MOVIES, sitem(1))
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))

            select(setOf(MOVIES))

            assertEquals(MOVIES, repository.ownerOf(ALICE, 1))
            assertTrue("B's row went with B", repository.alsoListed(ALICE).isEmpty())
            assertTrue("no move, no version", repository.versions(ALICE).isEmpty())
        }

    @Test
    fun overlap_afterBLands_untickA_itemStaysInB() =
        runBlocking {
            select(setOf(MOVIES, SHOWS), mapOf(SHOWS to STAMP))
            repository.put(ALICE, MOVIES, sitem(1))
            stamped(SHOWS, STAMP, sitem(1, library = SHOWS))
            assertTrue(repository.markAddWalked(ALICE, SHOWS, STAMP, clock.nowMs))
            assertTrue(repository.landAdd(ALICE, SHOWS, STAMP))
            clock.stepMs(1_000)
            val before = generation()

            select(setOf(SHOWS))

            val row = repository.rowOf(ALICE, 1)
            assertNotNull("the item outlives A", row)
            assertEquals(SHOWS.value, row!!.libraryId)
            assertEquals(null, row.deletedAtMs)
            assertTrue("B's row is gone: B owns it", repository.alsoListed(ALICE).isEmpty())
            assertTrue("the move is versioned", repository.movedVersioned(ALICE, 1, MOVIES))
            assertTrue(generation() > before)
        }

    @Test
    fun heldOwner_keepsRows_thenExpiryMovesToChosen() =
        runBlocking {
            select(setOf(MOVIES, SHOWS))
            repository.put(ALICE, MOVIES, sitem(1))
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))

            select(setOf(SHOWS), holds = mapOf(MOVIES to clock.nowMs + HOLD_MS))
            assertEquals("a held owner keeps the item", MOVIES, repository.ownerOf(ALICE, 1))
            assertEquals(setOf(SHOWS), repository.alsoListersOf(ALICE, 1))

            clock.stepMs(HOLD_MS + 1)
            select(setOf(SHOWS))

            assertEquals("the expiry moves it to the chosen lister", SHOWS, repository.ownerOf(ALICE, 1))
            assertTrue(repository.alsoListed(ALICE).isEmpty())
            assertTrue(repository.movedVersioned(ALICE, 1, MOVIES))
        }

    @Test
    fun deleteMove_targetPreference() =
        runBlocking {
            // MOVIES (the smallest id) is held, SHOWS and MORE are chosen, ADDS is being added: a smaller id never
            // beats a better class.
            select(setOf(gone, MOVIES, SHOWS, ADDS, MORE), mapOf(ADDS to STAMP))
            repository.put(ALICE, gone, sitem(1), sitem(2), sitem(3), sitem(4), sitem(5))
            repository.put(ALICE, SHOWS, sitem(1), sitem(4))
            stamped(ADDS, STAMP, sitem(1), sitem(2))
            repository.put(ALICE, MOVIES, sitem(1), sitem(2), sitem(3))
            repository.put(ALICE, MORE, sitem(4))
            select(setOf(gone, SHOWS, ADDS, MORE), holds = mapOf(MOVIES to clock.nowMs + HOLD_MS))

            select(setOf(SHOWS, ADDS, MORE))

            assertEquals("chosen before adding before held", SHOWS, repository.ownerOf(ALICE, 1))
            assertEquals("adding before held", ADDS, repository.ownerOf(ALICE, 2))
            assertEquals("a held lister still takes it", MOVIES, repository.ownerOf(ALICE, 3))
            assertEquals("the smallest id within a class", SHOWS, repository.ownerOf(ALICE, 4))
            assertEquals("no lister: it goes with the library", null, repository.rowOf(ALICE, 5))
            assertEquals(setOf(ADDS, MOVIES), repository.alsoListersOf(ALICE, 1))
            assertEquals(setOf(MOVIES), repository.alsoListersOf(ALICE, 2))
            assertEquals(emptySet<ItemId>(), repository.alsoListersOf(ALICE, 3))
            assertEquals(setOf(MORE), repository.alsoListersOf(ALICE, 4))
        }

    // 4.4a-i LOW-4: a library marked for a rewalk is chosen, so it ranks before one being added.
    @Test
    fun deleteMove_markedListerCountsAsChosen() =
        runBlocking {
            select(setOf(gone, ADDS, MORE), mapOf(ADDS to STAMP, MORE to STAMP + 1))
            repository.put(ALICE, gone, sitem(1))
            stamped(ADDS, STAMP, sitem(1))
            assertTrue(repository.revertUnproven(ALICE, MORE, STAMP + 1))
            repository.upsertItems(ALICE, MORE, stamp = null, rewalk = true, listOf(sitem(1))).written()

            select(setOf(ADDS, MORE))

            assertEquals(MORE, repository.ownerOf(ALICE, 1))
        }

    @Test
    fun deleteMove_tombstoneMovesToo() =
        runBlocking {
            select(setOf(MOVIES, SHOWS))
            repository.put(ALICE, MOVIES, sitem(1))
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))
            repository.tombstone(ALICE, listOf(ItemId(hexId(1))))

            select(setOf(SHOWS))

            val row = repository.rowOf(ALICE, 1)!!
            assertEquals(SHOWS.value, row.libraryId)
            assertNotNull("still a tombstone", row.deletedAtMs)
            assertTrue(repository.movedVersioned(ALICE, 1, MOVIES))
            repository.assertIntervalsContiguous(ALICE)
        }

    @Test
    fun deleteMove_carriesCoveredFrom() =
        runBlocking {
            select(setOf(MOVIES, SHOWS, MORE))
            repository.put(ALICE, MOVIES, sitem(1))
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))
            repository.put(ALICE, MORE, sitem(2))
            repository.put(ALICE, MOVIES, sitem(2))
            val heldAt = clock.nowMs
            select(setOf(SHOWS, MORE), holds = mapOf(MOVIES to clock.nowMs + HOLD_MS))
            assertEquals(heldAt, repository.coveredFrom(ALICE)[MOVIES])
            clock.stepMs(HOLD_MS + 1)

            select(setOf(SHOWS, MORE))

            assertEquals(SHOWS, repository.ownerOf(ALICE, 1))
            assertEquals("the move carries the coverage", heldAt, repository.coveredFrom(ALICE)[SHOWS])
            assertEquals("no item moved into MORE", null, repository.coveredFrom(ALICE)[MORE])
        }

    @Test
    fun deleteMove_beforeSeriesRehome_seriesSurvives() =
        runBlocking {
            select(setOf(MOVIES, SHOWS))
            val show = hexId(0x5E01)
            repository.put(ALICE, MOVIES, sepisode(1, show))
            repository.putSeries(ALICE, MOVIES, series(show))
            repository.put(ALICE, SHOWS, sepisode(1, show))

            select(setOf(SHOWS))

            assertEquals(SHOWS, repository.ownerOf(ALICE, 1))
            val kept = repository.currentSeries(ALICE).singleOrNull { it.seriesId == show }
            assertNotNull("the moved episode keeps its series", kept)
            assertEquals(SHOWS.value, kept!!.libraryId)
        }

    private companion object {
        const val STAMP = 7L
        const val HOLD_MS = 60_000L
    }
}
