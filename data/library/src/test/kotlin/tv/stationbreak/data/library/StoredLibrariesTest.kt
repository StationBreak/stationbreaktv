// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.InMemoryLibraryRepository
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.StoredLibrary
import tv.stationbreak.core.model.ItemId

/**
 * Spec 26, Part 4 (schema 11): `storedLibraries` reads every library row of one account with all six fields of its
 * lifecycle, in ID order, and never another account's. The fake gives the same rows for the same calls. Invented
 * libraries only.
 */
class StoredLibrariesTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE, BOB))
    private val adding = ItemId(hexId(0xB003))
    private val proven = ItemId(hexId(0xB004))
    private val held = ItemId(hexId(0xB005))
    private val marked = ItemId(hexId(0xB006))

    @After
    fun close() = repository.close()

    // The same port calls on any implementation; [prove] is its way to record an add walk's end.
    private suspend fun drive(
        repo: LibraryRepository,
        prove: suspend (ItemId, Long) -> Unit,
    ) {
        val stamps = mapOf(adding to 2L, proven to 3L, held to 4L, marked to 5L, SHOWS to 6L)
        repo.applySelection(ALICE, setOf(MOVIES, SHOWS, adding, proven, held, marked), stamps, nowMs = clock.nowMs)
        prove(SHOWS, 6L)
        repo.landAdd(ALICE, SHOWS, 6L)
        prove(proven, 3L)
        repo.revertUnproven(ALICE, marked, 5L)
        repo.applySelection(
            ALICE,
            setOf(MOVIES, SHOWS, adding, proven, marked),
            holds = mapOf(held to HOLD),
            nowMs = clock.nowMs,
        )
        repo.applySelection(BOB, setOf(ItemId(hexId(0xC001))), nowMs = clock.nowMs)
    }

    @Test
    fun allSixFields_scopedToTheAccount() =
        runBlocking {
            drive(repository) { library, stamp -> repository.markAddWalked(ALICE, library, stamp, 1L) }

            val expected =
                listOf(
                    StoredLibrary(MOVIES, false, null, null, null, false),
                    StoredLibrary(SHOWS, true, null, null, null, false),
                    StoredLibrary(adding, false, 2L, null, null, false),
                    StoredLibrary(proven, false, 3L, 3L, null, false),
                    StoredLibrary(held, false, null, null, HOLD, false),
                    StoredLibrary(marked, false, null, null, null, true),
                ).sortedBy { it.id.value }
            assertEquals(expected, repository.storedLibraries(ALICE))
            assertEquals(listOf(ItemId(hexId(0xC001))), repository.storedLibraries(BOB).map { it.id })
        }

    @Test
    fun parityWithTheFake() =
        runBlocking {
            drive(repository) { library, stamp -> repository.markAddWalked(ALICE, library, stamp, 1L) }
            val fake = InMemoryLibraryRepository()
            fake.useAccount(ALICE)
            drive(fake) { library, stamp -> fake.proveWalk(ALICE, library, stamp) }

            assertEquals(fake.storedLibraries(ALICE), repository.storedLibraries(ALICE))
        }

    private companion object {
        const val HOLD = 1_700_000_060_000L
    }
}
