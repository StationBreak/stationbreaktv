// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.stationbreak.core.model.ItemId

/**
 * Spec 06 task 2.7: a page and its checkpoint are stored together or not at all, and a page written twice changes
 * nothing (the resume of 3.x and the idempotent upserts the 1.0 review asked for).
 */
class AtomicPageWriteTest {
    private val repository = TestLibraries.inMemory()

    @After
    fun close() = repository.close()

    private val page1 = listOf(item("p1-a").copy(genres = listOf("g")), item("p1-b"))
    private val page2 = listOf(item("p2-a"), item("p2-b"))

    private suspend fun idsStored() = repository.stored(ALICE).map { it.item.id.value }.sorted()

    @Test
    fun aPageAndItsCheckpointAreStoredTogether() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))

            repository.writePage(ALICE, MOVIES, page1, nextStartIndex = 2, totalCount = 4, run = 7)

            assertEquals(listOf("p1-a", "p1-b"), idsStored())
            val expected = Checkpoint(MOVIES, nextStartIndex = 2, totalCount = 4, run = 7)
            assertEquals(expected, repository.checkpoint(ALICE, MOVIES))
        }

    @Test
    fun aFailureBetweenTheItemsAndTheCheckpointStoresNeither() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, page1, nextStartIndex = 2, totalCount = 4, run = 7)
            repository.faultBetweenItemsAndCheckpoint = { error("the process died here") }

            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.writePage(ALICE, MOVIES, page2, nextStartIndex = 4, totalCount = 4, run = 7) }
            }

            // Page 2's items are not there, and the checkpoint still says the next page is page 2.
            assertEquals(listOf("p1-a", "p1-b"), idsStored())
            assertEquals(2, repository.checkpoint(ALICE, MOVIES)?.nextStartIndex)
        }

    @Test
    fun aFailureLeavesTheSideTablesAloneToo() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.faultBetweenItemsAndCheckpoint = { error("the process died here") }

            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.writePage(ALICE, MOVIES, page1, nextStartIndex = 2, totalCount = 4, run = 7) }
            }

            assertEquals(0, repository.rowCounts().items)
            assertEquals(0, repository.rowCounts().genres)
            assertNull(repository.checkpoint(ALICE, MOVIES))
        }

    @Test
    fun theWriteWorksAgainOnceTheFaultIsGone() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.faultBetweenItemsAndCheckpoint = { error("the process died here") }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.writePage(ALICE, MOVIES, page1, nextStartIndex = 2, totalCount = 4, run = 7) }
            }
            repository.faultBetweenItemsAndCheckpoint = null

            repository.writePage(ALICE, MOVIES, page1, nextStartIndex = 2, totalCount = 4, run = 7)

            assertEquals(listOf("p1-a", "p1-b"), idsStored())
        }

    @Test
    fun aPageForALibraryThatWasNotSelectedStoresNothing() {
        assertThrows(Exception::class.java) {
            runBlocking {
                repository.writePage(ALICE, ItemId("never-selected"), page1, 2, 4, run = 1)
            }
        }
        runBlocking {
            assertEquals(RowCounts.NONE, repository.rowCounts())
        }
    }

    @Test
    fun writingAPageTwiceChangesNothing() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            val first = repository.writePage(ALICE, MOVIES, page1, nextStartIndex = 2, totalCount = 4, run = 7)
            val before = repository.stored(ALICE)

            val replay = repository.writePage(ALICE, MOVIES, page1, nextStartIndex = 2, totalCount = 4, run = 7)

            assertEquals(PageWrite(inserted = 2, updated = 0, unchanged = 0), first)
            assertEquals(PageWrite(inserted = 0, updated = 0, unchanged = 2), replay)
            assertEquals(before, repository.stored(ALICE))
            assertEquals(repository.rowCounts().genres, 1)
        }

    @Test
    fun aChangedSincePageSavesItemsAndMovesNoCheckpoint() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, page1, nextStartIndex = 2, totalCount = 4, run = 7)
            val before = repository.checkpoint(ALICE, MOVIES)

            val write = repository.upsertItems(ALICE, MOVIES, listOf(page1[0].copy(name = "Changed"), page2[0]))

            assertEquals(PageWrite(inserted = 1, updated = 1, unchanged = 0), write)
            assertEquals(before, repository.checkpoint(ALICE, MOVIES))
            assertEquals(listOf("p1-a", "p1-b", "p2-a"), idsStored())
        }

    @Test
    fun aChangedItemIsUpdatedAndItsGenresAreReplaced() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, page1, nextStartIndex = 2, totalCount = 4, run = 7)
            val renamed = page1[0].copy(name = "Renamed", genres = listOf("new-1", "new-2"))

            val write = repository.writePage(ALICE, MOVIES, listOf(renamed, page1[1]), 2, 4, run = 8)

            assertEquals(PageWrite(inserted = 0, updated = 1, unchanged = 1), write)
            assertEquals(renamed, repository.stored(ALICE).first { it.item.id == renamed.id }.item)
            assertEquals(2, repository.rowCounts().genres)
        }

    @Test
    fun aTombstonedItemThatComesBackInAPageIsRevived() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, page1, nextStartIndex = 2, totalCount = 2, run = 1)
            repository.tombstone(ALICE, listOf(page1[0].id))

            val write = repository.writePage(ALICE, MOVIES, page1, nextStartIndex = 2, totalCount = 2, run = 2)

            assertEquals(1, write.updated)
            assertNull(repository.stored(ALICE).first { it.item.id == page1[0].id }.deletedAt)
        }

    @Test
    fun aPageMayBeLargerThanSqliteAllowsVariablesFor() =
        runBlocking {
            // The platform SQLite on API 25 allows 999 variables in a statement; the write chunks its lookups.
            val many = (0 until 1500).map { item("many-${it.toString().padStart(4, '0')}") }
            repository.select(ALICE, setOf(MOVIES))

            repository.writePage(ALICE, MOVIES, many, nextStartIndex = many.size, totalCount = many.size, run = 1)
            val replay = repository.writePage(ALICE, MOVIES, many, many.size, many.size, run = 1)

            assertEquals(1500, repository.stored(ALICE).size)
            assertEquals(1500, replay.unchanged)
        }
}
