// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import kotlin.time.Instant

/**
 * Spec 10 task 2.6 (Vimes M7): signing in as another account deletes the old account's `collection`,
 * `collection_version`, `collection_member` and `collection_sync` rows in the same transaction as the rest of its data;
 * signing one account out leaves every other account's collections alone.
 */
class AccountSwitchClearsCollectionsTest {
    private val repository = TestLibraries.inMemory(signedIn = emptyList())
    private val box = ItemId("box01")

    @After
    fun close() = repository.close()

    // Two members, then one change: a collection, a version, three member rows and a sync row.
    private suspend fun fill(account: LibraryAccount) {
        repository.writeCollection(account, box, "n", movies("a", "b"))
        repository.writeCollection(account, box, "n", movies("b", "c"))
        repository.recordCollectionPass(account, Instant.fromEpochMilliseconds(5), relisted = true)
    }

    private suspend fun rowsOf(account: LibraryAccount) =
        listOf(
            repository.collections(account).size,
            repository.collectionVersions(account).size,
            repository.collectionMembers(account).size,
            if (repository.collectionSync(account) == null) 0 else 1,
        )

    @Test
    fun signingInAsAnotherAccountLeavesNoCollectionRowOfTheOldOne() =
        runBlocking {
            repository.useAccount(ALICE)
            fill(ALICE)
            assertEquals(listOf(1, 1, 3, 1), rowsOf(ALICE))

            repository.useAccount(BOB)

            assertEquals(listOf(0, 0, 0, 0), rowsOf(ALICE))
            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun signingInAgainAsTheSameAccountKeepsItsCollections() =
        runBlocking {
            repository.useAccount(ALICE)
            fill(ALICE)

            repository.useAccount(ALICE)

            assertEquals(listOf(1, 1, 3, 1), rowsOf(ALICE))
        }

    @Test
    fun signingOneAccountOutLeavesTheOtherAccountsCollectionsAlone() =
        runBlocking {
            TestLibraries.signedIn(repository, listOf(ALICE, BOB))
            fill(ALICE)
            fill(BOB)

            repository.forget(ALICE)

            assertEquals(listOf(0, 0, 0, 0), rowsOf(ALICE))
            assertEquals(listOf(1, 1, 3, 1), rowsOf(BOB))
        }
}
