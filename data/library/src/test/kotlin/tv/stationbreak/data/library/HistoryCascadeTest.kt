// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 09 (Vimes H5): `item_version` has a foreign key to `item` with `ON DELETE CASCADE`, so an item's history goes
 * with the item: sign-out and a change of account (`forget`, `activate`), unticking a library, and the purge of a
 * tombstone. This proves the cascade really fires on the JVM driver; `LibraryDatabaseOnDeviceTest` proves the app's.
 */
class HistoryCascadeTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)

    @After
    fun close() = repository.close()

    private suspend fun twoLibrariesWithHistory() {
        repository.select(ALICE, setOf(MOVIES, SHOWS))
        repository.put(ALICE, MOVIES, sitem(1, runtime = 90.minutes), sitem(2, runtime = 90.minutes))
        repository.put(ALICE, SHOWS, sitem(3, runtime = 90.minutes, library = SHOWS))
        clock.stepMs(1_000)
        repository.put(ALICE, MOVIES, sitem(1, runtime = 91.minutes), sitem(2, runtime = 91.minutes))
        repository.put(ALICE, SHOWS, sitem(3, runtime = 91.minutes, library = SHOWS))
    }

    @Test
    fun unTickingALibraryDeletesTheHistoryOfItsItemsOnly() =
        runBlocking {
            twoLibrariesWithHistory()
            assertEquals(3, repository.versions(ALICE).size)

            repository.select(ALICE, setOf(MOVIES))

            assertEquals(setOf(hexId(1), hexId(2)), repository.versions(ALICE).map { it.itemId }.toSet())
        }

    @Test
    fun aChangeOfAccountDeletesTheOtherAccountsHistory() =
        runBlocking {
            twoLibrariesWithHistory()
            repository.select(BOB, setOf(MOVIES))
            repository.put(BOB, MOVIES, sitem(1, runtime = 90.minutes))
            repository.put(BOB, MOVIES, sitem(1, runtime = 95.minutes))
            assertEquals(1, repository.versions(BOB).size)

            repository.useAccount(BOB)

            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
            assertEquals(1, repository.versions(BOB).size)
        }

    @Test
    fun forgetDeletesTheHistoryAndLeavesAnotherAccountsAlone() =
        runBlocking {
            twoLibrariesWithHistory()
            repository.select(BOB, setOf(MOVIES))
            repository.put(BOB, MOVIES, sitem(1, runtime = 90.minutes))
            repository.put(BOB, MOVIES, sitem(1, runtime = 95.minutes))

            repository.forget(ALICE)

            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
            assertEquals(1, repository.versions(BOB).size)
        }

    @Test
    fun thePurgeOfATombstoneDeletesItsVersions() =
        runBlocking {
            twoLibrariesWithHistory()
            repository.tombstone(ALICE, listOf(ItemId(hexId(1))))
            clock.stepMs(31 * 86_400_000L)

            assertEquals(1, (repository.purgeHistory(ALICE) as PurgeOutcome.Purged).tombstones)

            assertEquals(setOf(hexId(2), hexId(3)), repository.versions(ALICE).map { it.itemId }.toSet())
            assertEquals(2, repository.currentRows(ALICE).size)
        }
}
