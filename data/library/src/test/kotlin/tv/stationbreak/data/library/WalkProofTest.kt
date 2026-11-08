// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ItemId

/**
 * Spec 26, Part 4 (schema 11): the proof that an add's walk ended lives in Room. `markAddWalked`, the add walk's own
 * last transaction, sets `walkedStamp` (and the library's coverage, the walk's run) only for the stamp the library has;
 * an empty library is proven the same way. `landAdd` needs the stamp and that proof, clears both and bumps once. A new
 * stamp or a hold clears the proof, and a hold clears a rewalk mark too. Invented libraries only.
 */
class WalkProofTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val adding = ItemId(hexId(0xB003))

    @After
    fun close() = repository.close()

    private suspend fun row(library: ItemId = adding) =
        repository.dao.libraries("server-1", "alice").single { it.libraryId == library.value }

    private suspend fun add(stamp: Long) =
        repository.applySelection(ALICE, setOf(MOVIES, adding), mapOf(adding to stamp), nowMs = clock.nowMs)

    private suspend fun generation() = repository.contentGeneration(ALICE)

    @Test
    fun markAddWalked_setsProofAndCoveredFrom_onlyForCurrentStamp() =
        runBlocking {
            add(7L)

            assertFalse("another stamp", repository.markAddWalked(ALICE, adding, 6L, RUN))
            assertNull(row().walkedStamp)
            assertNull(row().coveredFromMs)
            assertFalse("a library with no row", repository.markAddWalked(ALICE, ItemId(hexId(0xB0FF)), 7L, RUN))
            val before = generation()

            assertTrue(repository.markAddWalked(ALICE, adding, 7L, RUN))

            assertEquals(7L, row().walkedStamp)
            assertEquals("covered from the walk's run", RUN, row().coveredFromMs)
            assertEquals("the stamp stays until the landing", 7L, row().addStamp)
            assertFalse("still pending", row().fetched)
            assertEquals("proof moves nothing", before, generation())

            repository.applySelection(
                ALICE,
                setOf(MOVIES),
                holds = mapOf(adding to clock.nowMs + 60_000),
                nowMs = clock.nowMs,
            )
            assertFalse("a held library takes no proof", repository.markAddWalked(ALICE, adding, 7L, RUN))
        }

    @Test
    fun emptyLibrary_walkEndStillProves() =
        runBlocking {
            add(7L)
            assertTrue(repository.stored(ALICE).isEmpty())

            assertTrue(repository.markAddWalked(ALICE, adding, 7L, RUN))
            assertTrue("an empty add lands", repository.landAdd(ALICE, adding, 7L))

            assertTrue(row().fetched)
            assertEquals(RUN, row().coveredFromMs)
        }

    @Test
    fun landAdd_withoutProof_false() =
        runBlocking {
            add(7L)
            repository.writePage(ALICE, adding, stamp = 7L, rewalk = false, listOf(sitem(3, library = adding)), 1, 1, 5)
            val before = generation()
            val stored = row()

            assertFalse(repository.landAdd(ALICE, adding, 7L))

            assertEquals(stored, row())
            assertEquals(before, generation())
        }

    @Test
    fun landAdd_clearsStampAndProof_bumpsOnce() =
        runBlocking {
            add(7L)
            repository.put(ALICE, MOVIES, sitem(1))
            repository.writePage(ALICE, adding, stamp = 7L, rewalk = false, listOf(sitem(3, library = adding)), 1, 1, 5)
            repository.markAddWalked(ALICE, adding, 7L, RUN)
            val before = generation()

            assertTrue(repository.landAdd(ALICE, adding, 7L))

            val landed = row()
            assertNull(landed.addStamp)
            assertNull(landed.walkedStamp)
            assertTrue(landed.fetched)
            assertFalse(landed.rewalkPending)
            assertEquals("landAdd does not write the coverage; the walk's end did", RUN, landed.coveredFromMs)
            assertEquals("once", before + 1, generation())
            assertFalse(repository.landAdd(ALICE, adding, 7L))
            assertEquals(before + 1, generation())
        }

    @Test
    fun newStamp_clearsProof() =
        runBlocking {
            add(7L)
            repository.markAddWalked(ALICE, adding, 7L, RUN)
            add(7L)
            repository.applySelection(ALICE, setOf(MOVIES, adding), nowMs = clock.nowMs)
            assertEquals("the same stamp, or none, keeps the proof", 7L, row().walkedStamp)

            add(8L)

            assertEquals(8L, row().addStamp)
            assertNull("a new stamp is a new walk", row().walkedStamp)
            assertFalse(repository.landAdd(ALICE, adding, 8L))
            assertFalse(repository.landAdd(ALICE, adding, 7L))
        }

    @Test
    fun hold_clearsProofAndMark() =
        runBlocking {
            add(7L)
            repository.markAddWalked(ALICE, adding, 7L, RUN)
            repository.applySelection(
                ALICE,
                setOf(MOVIES),
                holds = mapOf(adding to clock.nowMs + 60_000),
                nowMs = clock.nowMs,
            )
            val held = row()
            assertNull(held.addStamp)
            assertNull("a hold clears the proof", held.walkedStamp)
            assertFalse(repository.landAdd(ALICE, adding, 7L))

            // A reverted add carries a rewalk mark; a hold clears that too.
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), mapOf(SHOWS to 3L), nowMs = clock.nowMs)
            assertTrue(repository.revertUnproven(ALICE, SHOWS, 3L))
            assertTrue(row(SHOWS).rewalkPending)
            repository.applySelection(
                ALICE,
                setOf(MOVIES),
                holds = mapOf(SHOWS to clock.nowMs + 60_000),
                nowMs = clock.nowMs,
            )
            assertFalse("a hold clears the mark", row(SHOWS).rewalkPending)
        }

    private companion object {
        const val RUN = 1_699_000_000_000L
    }
}
