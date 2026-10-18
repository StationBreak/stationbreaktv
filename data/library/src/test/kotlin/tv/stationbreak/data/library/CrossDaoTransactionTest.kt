// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 10 task 1.2: a transaction that spans DAOs is one `@Transaction` method on `CacheTransactionDao`, and it rolls
 * back whole. A page write goes through `LibraryDao` (the item rows, the checkpoint) and `ItemHistoryDao` (the
 * version of an edited item); a fault between the two leaves neither. The series table arrives in 1.4, and task 1.5.2
 * extends this to the series rows. `CrossDaoTransactionOnDeviceTest` runs the same fault over the platform helper.
 */
class CrossDaoTransactionTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)

    @After
    fun close() = repository.close()

    // The first page holds one item and moves the checkpoint to 1.
    private val first = listOf(sitem(1, runtime = 90.minutes))

    // The second edits that item (which makes a version) and adds another, and would move the checkpoint to 2.
    private val second = listOf(sitem(1, runtime = 100.minutes), sitem(2))

    private suspend fun writeFirst() {
        repository.select(ALICE, setOf(MOVIES))
        repository.writePage(ALICE, MOVIES, first, nextStartIndex = 1, totalCount = 2, run = 1)
        clock.stepMs(10_000)
    }

    private suspend fun writeSecond(): PageWrite {
        val page = repository.writePage(ALICE, MOVIES, second, nextStartIndex = 2, totalCount = 2, run = 1)
        return page
    }

    @Test
    fun aFaultAfterTheItemRowsLeavesNoItemVersionOrCheckpointRow() =
        runBlocking {
            writeFirst()
            val before = repository.rowCounts()
            val rowsBefore = repository.currentRows(ALICE)
            repository.faultBetweenItemsAndHistory = { error("the process died here") }

            assertThrows(IllegalStateException::class.java) { runBlocking { writeSecond() } }

            // The edit is not stored, the new item is not stored, no version was kept, and the checkpoint did not move.
            assertEquals(before, repository.rowCounts())
            assertEquals(rowsBefore, repository.currentRows(ALICE))
            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
            assertEquals(1, repository.checkpoint(ALICE, MOVIES)?.nextStartIndex)
        }

    @Test
    fun theSamePageWritesWholeOnceTheFaultIsGone() =
        runBlocking {
            writeFirst()
            repository.faultBetweenItemsAndHistory = { error("the process died here") }
            assertThrows(IllegalStateException::class.java) { runBlocking { writeSecond() } }
            repository.faultBetweenItemsAndHistory = null

            val write = writeSecond()

            assertEquals(PageWrite(inserted = 1, updated = 1, unchanged = 0), write)
            assertEquals(2, repository.currentRows(ALICE).size)
            assertEquals(1, repository.versions(ALICE).size)
            assertEquals(2, repository.checkpoint(ALICE, MOVIES)?.nextStartIndex)
            repository.assertIntervalsContiguous(ALICE)
        }

    // Task 1.5.2: the same rollback reaches the series rows, which are written after the item versions.

    private val seriesFirst = listOf(series("s1", rating = "A", genres = listOf("x")))
    private val seriesSecond = listOf(series("s1", rating = "B", genres = listOf("x")), series("s2"))

    private suspend fun writeFirstWithSeries() {
        repository.select(ALICE, setOf(MOVIES))
        repository.writePage(ALICE, MOVIES, first, nextStartIndex = 1, totalCount = 2, run = 1, series = seriesFirst)
        clock.stepMs(10_000)
    }

    private suspend fun writeSecondWithSeries() =
        repository.writePage(ALICE, MOVIES, second, nextStartIndex = 2, totalCount = 2, run = 1, series = seriesSecond)

    @Test
    fun aFaultAfterTheSeriesRowsLeavesNoItemSeriesVersionOrCheckpointRow() =
        runBlocking {
            writeFirstWithSeries()
            val before = repository.rowCounts()
            val seriesBefore = repository.currentSeries(ALICE)
            repository.faultBetweenItemsAndCheckpoint = { error("the process died here") }

            assertThrows(IllegalStateException::class.java) { runBlocking { writeSecondWithSeries() } }

            assertEquals(before, repository.rowCounts())
            assertEquals(seriesBefore, repository.currentSeries(ALICE))
            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(ALICE))
            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(ALICE))
            assertEquals(1, repository.checkpoint(ALICE, MOVIES)?.nextStartIndex)
        }

    @Test
    fun aFaultBetweenTheItemRowsAndTheHistoryRowsLeavesNoSeriesRowEither() =
        runBlocking {
            writeFirstWithSeries()
            val before = repository.rowCounts()
            repository.faultBetweenItemsAndHistory = { error("the process died here") }

            assertThrows(IllegalStateException::class.java) { runBlocking { writeSecondWithSeries() } }

            assertEquals(before, repository.rowCounts())
            assertEquals(listOf("s1"), repository.currentSeries(ALICE).map { it.seriesId })
        }

    @Test
    fun theSamePageWithSeriesWritesWholeOnceTheFaultIsGone() =
        runBlocking {
            writeFirstWithSeries()
            repository.faultBetweenItemsAndCheckpoint = { error("the process died here") }
            assertThrows(IllegalStateException::class.java) { runBlocking { writeSecondWithSeries() } }
            repository.faultBetweenItemsAndCheckpoint = null

            writeSecondWithSeries()

            assertEquals(listOf("s1", "s2"), repository.currentSeries(ALICE).map { it.seriesId })
            assertEquals(1, repository.seriesVersions(ALICE).size)
            assertEquals(1, repository.versions(ALICE).size)
            assertEquals(2, repository.checkpoint(ALICE, MOVIES)?.nextStartIndex)
            repository.assertSeriesIntervalsContiguous(ALICE)
        }
}
