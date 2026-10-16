// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem

/** Spec 06 task 2.7 (ADR-0002): rows are keyed by server and user, and a change of either clears the cache. */
class AccountIsolationTest {
    private val repository = TestLibraries.inMemory()

    @After
    fun close() = repository.close()

    private suspend fun save(
        account: LibraryAccount,
        vararg ids: String,
    ) {
        repository.select(account, setOf(MOVIES))
        val items = ids.map { item(it).copy(genres = listOf("g-$it"), tags = listOf("t-$it")) }
        repository.writePage(account, MOVIES, items, nextStartIndex = ids.size, totalCount = ids.size, run = 1)
    }

    @Test
    fun accountBReadsNothingOfAccountAOnTheSameServer() =
        runBlocking {
            save(ALICE, "a1", "a2")

            assertEquals(emptyList<MediaItem>(), repository.snapshot(BOB, setOf(MOVIES), EPOCH))
            assertEquals(0, repository.excludedCount(BOB, setOf(MOVIES)))
            assertEquals(emptySet<ItemId>(), repository.pendingLibraries(BOB))
        }

    @Test
    fun theSameUserOnAnotherServerReadsNothing() =
        runBlocking {
            save(ALICE, "a1")

            assertEquals(emptyList<MediaItem>(), repository.snapshot(ALICE_ON_ANOTHER_SERVER, setOf(MOVIES), EPOCH))
        }

    @Test
    fun twoAccountsCanHoldTheSameItemIdWithoutMixingThem() =
        runBlocking {
            save(ALICE, "shared")
            save(BOB, "shared")
            repository.writePage(
                BOB,
                MOVIES,
                listOf(item("shared").copy(name = "Bob's copy")),
                nextStartIndex = 1,
                totalCount = 1,
                run = 2,
            )

            assertEquals("Name of shared", repository.snapshot(ALICE, setOf(MOVIES), EPOCH).single().name)
            assertEquals("Bob's copy", repository.snapshot(BOB, setOf(MOVIES), EPOCH).single().name)
        }

    @Test
    fun aChangeOfUserClearsTheOtherUsersLibraryEverywhere() =
        runBlocking {
            save(ALICE, "a1", "a2")
            repository.beginFirstSync(ALICE)

            repository.useAccount(BOB)

            assertEquals(RowCounts.NONE, repository.rowCounts())
            assertEquals(emptyList<MediaItem>(), repository.snapshot(ALICE, setOf(MOVIES), EPOCH))
        }

    @Test
    fun aChangeOfServerClearsTheLibrary() =
        runBlocking {
            save(ALICE, "a1")

            repository.useAccount(ALICE_ON_ANOTHER_SERVER)

            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun usingTheSameAccountAgainKeepsItsLibrary() =
        runBlocking {
            save(ALICE, "a1", "a2")

            repository.useAccount(ALICE)

            assertEquals(2, repository.snapshot(ALICE, setOf(MOVIES), EPOCH).size)
        }

    @Test
    fun forgettingAnAccountDeletesItsRowsAndOnlyItsRows() =
        runBlocking {
            save(ALICE, "a1")
            save(BOB, "b1")

            repository.forget(ALICE)

            assertEquals(emptyList<MediaItem>(), repository.snapshot(ALICE, setOf(MOVIES), EPOCH))
            assertEquals(listOf("b1"), repository.snapshot(BOB, setOf(MOVIES), EPOCH).map { it.id.value })
        }

    @Test
    fun noTableHoldsAPlaceForATokenOrAPassword() {
        // The cache is plain library data: the exported schema's tables and columns name no credential.
        val columns = SchemaFile.columnNames()
        assertEquals(true, columns.size > 20)
        val forbidden = listOf("token", "password", "secret", "credential", "authorization", "apikey")
        assertEquals(emptyList<String>(), columns.filter { name -> forbidden.any { name.contains(it, true) } })
    }
}
