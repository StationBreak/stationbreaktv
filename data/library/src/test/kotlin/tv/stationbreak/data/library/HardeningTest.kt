// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.ItemId

/** Angua's review of spec 06 task 2.0: deleted rows are overwritten, and no list outgrows SQLite's variable limit. */
class HardeningTest {
    private val repository = TestLibraries.inMemory()

    @After
    fun close() = repository.close()

    @Test
    fun deletedRowsAreOverwrittenOnDisk() =
        runBlocking {
            // secure_delete=ON zeroes freed pages, so a deleted library's titles are not left in the file.
            assertEquals(1, repository.pragmaSecureDelete())
        }

    @Test
    fun selectingMoreLibrariesThanSqliteHasVariablesForWorks() =
        runBlocking {
            val many = (0 until 1500).map { ItemId("lib-${it.toString().padStart(4, '0')}") }.toSet()

            repository.select(ALICE, many)

            assertEquals(many, repository.pendingLibraries(ALICE))
            repository.select(ALICE, many.take(10).toSet())
            assertEquals(10, repository.pendingLibraries(ALICE).size)
        }

    @Test
    fun snapshotAndExcludedCountOverManyLibrariesWork() =
        runBlocking {
            val many = (0 until 1500).map { ItemId("lib-${it.toString().padStart(4, '0')}") }.toSet()
            repository.select(ALICE, many)
            val first = many.first()
            repository.writePage(ALICE, first, listOf(item("a"), item("b", runtime = null)), 2, 2, run = 1)

            assertEquals(listOf("a"), repository.snapshot(ALICE, many, EPOCH).map { it.id.value })
            assertEquals(1, repository.excludedCount(ALICE, many))
        }
}
