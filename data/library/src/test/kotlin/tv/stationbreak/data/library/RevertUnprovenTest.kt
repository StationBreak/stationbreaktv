// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.execSQL
import androidx.room.useWriterConnection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.LibraryRowsContract
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 26, Part 4 (schema 11): `revertUnproven` is a compare-and-set. Only a row with the stamp, no proof for it and no
 * hold goes back to plain pending: stamp and proof cleared, not fetched, marked for a rewalk, its checkpoint deleted,
 * all in one transaction. It bumps once only when the library holds rows (they are a chosen library's from then on),
 * and an account that is not signed in reverts nothing. The fake keeps the same contract. Invented libraries only.
 */
class RevertUnprovenTest {
    private val clock = FakeClock()
    private val opened = TestLibraries.inMemoryWithDatabase(clock, signedIn = listOf(ALICE))
    private val repository = opened.first
    private val database = opened.second
    private val adding = ItemId(hexId(0xB003))

    @After
    fun close() = repository.close()

    private suspend fun row() = repository.dao.libraries("server-1", "alice").single { it.libraryId == adding.value }

    private suspend fun add(stamp: Long = STAMP) =
        repository.applySelection(ALICE, setOf(MOVIES, adding), mapOf(adding to stamp), nowMs = clock.nowMs)

    private suspend fun walkOnePage() =
        repository
            .writePage(ALICE, adding, stamp = STAMP, rewalk = false, listOf(sitem(3, library = adding)), 1, 2, 5)
            .written()

    private suspend fun generation() = repository.contentGeneration(ALICE)

    @Test
    fun unproven_reverted_pendingMarked_checkpointGone() =
        runBlocking {
            add()
            walkOnePage()
            assertNotNull(repository.checkpoint(ALICE, adding))

            assertTrue(repository.revertUnproven(ALICE, adding, STAMP))

            val reverted = row()
            assertNull(reverted.addStamp)
            assertNull(reverted.walkedStamp)
            assertFalse(reverted.fetched)
            assertTrue(reverted.rewalkPending)
            assertNull("the rewalk starts from the beginning", repository.checkpoint(ALICE, adding))
            assertTrue(adding in repository.pendingLibraries(ALICE))
            assertEquals("the rows stay, to be read again", 1, repository.stored(ALICE).size)
            assertFalse("a second revert finds no stamp", repository.revertUnproven(ALICE, adding, STAMP))
        }

    @Test
    fun proven_untouched_false() =
        runBlocking {
            add()
            walkOnePage()
            repository.markAddWalked(ALICE, adding, STAMP, 9L)
            val stored = row()

            assertFalse(repository.revertUnproven(ALICE, adding, STAMP))

            assertEquals(stored, row())
            assertNotNull(repository.checkpoint(ALICE, adding))
        }

    @Test
    fun otherStamp_false() =
        runBlocking {
            add()
            walkOnePage()
            val stored = row()

            assertFalse(repository.revertUnproven(ALICE, adding, STAMP + 1))
            assertFalse("a chosen library has no stamp", repository.revertUnproven(ALICE, MOVIES, STAMP))
            assertFalse("no such library", repository.revertUnproven(ALICE, ItemId(hexId(0xB0FF)), STAMP))

            assertEquals(stored, row())
            assertNotNull(repository.checkpoint(ALICE, adding))
        }

    @Test
    fun held_false() =
        runBlocking {
            add()
            walkOnePage()
            repository.applySelection(
                ALICE,
                setOf(MOVIES),
                holds = mapOf(adding to clock.nowMs + 60_000),
                nowMs = clock.nowMs,
            )
            // Held and stamped, as an older build could leave a row: the hold alone refuses the revert.
            database.useWriterConnection {
                it.execSQL("UPDATE library SET addStamp = $STAMP WHERE libraryId = '${adding.value}'")
            }
            val stored = row()

            assertFalse(repository.revertUnproven(ALICE, adding, STAMP))

            assertEquals(stored, row())
        }

    @Test
    fun bumpsOnlyWhenRowsHeld() =
        runBlocking {
            add()
            repository.put(ALICE, MOVIES, sitem(1))
            val before = generation()
            assertTrue(repository.revertUnproven(ALICE, adding, STAMP))
            assertEquals("an empty add holds nothing a day could read", before, generation())

            repository.applySelection(
                ALICE,
                setOf(MOVIES, adding, SHOWS),
                mapOf(SHOWS to STAMP + 1),
                nowMs = clock.nowMs,
            )
            repository.upsertSeries(ALICE, SHOWS, STAMP + 1, rewalk = false, listOf(series("s-1"))).written()
            val withSeries = generation()
            assertTrue(repository.revertUnproven(ALICE, SHOWS, STAMP + 1))
            assertEquals("a series row counts as a row", withSeries + 1, generation())
        }

    @Test
    fun inactiveAccount_false() =
        runBlocking {
            add()
            val stored = row()
            repository.dao.deleteActive("server-1", "alice")

            assertFalse(repository.revertUnproven(ALICE, adding, STAMP))

            assertEquals(stored, row())
        }

    // The shared contract, against the Room repository here and the fake in the same run.
    @Test
    fun fakeMatchesRoom() =
        runBlocking {
            LibraryRowsContract.check {
                val room = TestLibraries.inMemory(FakeClock(), signedIn = listOf(ALICE, BOB))
                LibraryRowsContract.Fixture(
                    repository = room,
                    account = ALICE,
                    other = BOB,
                    proveWalk = { library, stamp -> room.markAddWalked(ALICE, library, stamp, 1L) },
                    saveRow = { library, stamp ->
                        room.upsertItems(ALICE, library, stamp, rewalk = false, listOf(sitem(1, library = library)))
                    },
                    signOut = { room.dao.deleteActive("server-1", "alice") },
                )
            }
            LibraryRowsContract.check {
                val fake = InMemoryLibraryRepository()
                fake.useAccount(ALICE)
                LibraryRowsContract.Fixture(
                    repository = fake,
                    account = ALICE,
                    other = BOB,
                    proveWalk = { library, stamp -> fake.proveWalk(ALICE, library, stamp) },
                    saveRow = { library, _ ->
                        fake.seed(
                            ALICE,
                            library,
                            MediaItem(ItemId(hexId(1)), "Film", ItemKind.Movie, 90.minutes, library),
                        )
                    },
                    signOut = { fake.forget(ALICE) },
                )
            }
        }

    private companion object {
        const val STAMP = 7L
    }
}
