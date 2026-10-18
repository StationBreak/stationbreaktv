// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Spec 10 task 1.2.3: the same fault as `CrossDaoTransactionTest`, over the platform SQLiteOpenHelper of the API 25
 * emulator (the JVM test runs the bundled driver). A page write goes through `LibraryDao` and `ItemHistoryDao` inside
 * one `CacheTransactionDao` method; a fault between the two must leave no item, `item_version` or checkpoint row.
 * Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class CrossDaoTransactionOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val account = LibraryAccount(ServerId("device-test-server"), UserId("device-test-user"))
    private val library = ItemId("device-test-library")
    private val created = Instant.fromEpochMilliseconds(1_600_000_000_000)
    private lateinit var repository: RoomLibraryRepository

    @Before
    fun open() {
        context.deleteDatabase(RoomLibraryRepository.DATABASE_NAME)
        repository = RoomLibraryRepository.create(context)
        runBlocking { repository.useAccount(account) }
    }

    @After
    fun close() {
        repository.close()
        context.deleteDatabase(RoomLibraryRepository.DATABASE_NAME)
    }

    private fun item(
        id: String,
        runtimeMinutes: Int,
    ) = MediaItem(
        id = ItemId(id),
        name = "Item $id",
        kind = ItemKind.Movie,
        runtime = runtimeMinutes.minutes,
        parentId = library,
        dateCreated = created,
    )

    @Test
    fun aFaultAfterTheItemRowsLeavesNoItemVersionOrCheckpointRow() =
        runBlocking {
            repository.select(account, setOf(library))
            repository.writePage(account, library, listOf(item("a", 90)), 1, 2, run = 1)
            val before = repository.rowCounts()
            val rowsBefore = repository.currentRows(account)
            repository.faultBetweenItemsAndHistory = { error("the process died here") }

            // The second page edits "a" (which makes a version) and adds "b".
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.writePage(account, library, listOf(item("a", 100), item("b", 90)), 2, 2, run = 1) }
            }

            assertEquals(before, repository.rowCounts())
            assertEquals(rowsBefore, repository.currentRows(account))
            assertEquals(emptyList<ItemVersionEntity>(), repository.versions(account))
            assertEquals(1, repository.checkpoint(account, library)?.nextStartIndex)
        }

    @Test
    fun theSamePageWritesWholeOnceTheFaultIsGone() =
        runBlocking {
            repository.select(account, setOf(library))
            repository.writePage(account, library, listOf(item("a", 90)), 1, 2, run = 1)
            repository.faultBetweenItemsAndHistory = { error("the process died here") }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.writePage(account, library, listOf(item("a", 100), item("b", 90)), 2, 2, run = 1) }
            }
            repository.faultBetweenItemsAndHistory = null

            repository.writePage(account, library, listOf(item("a", 100), item("b", 90)), 2, 2, run = 1)

            assertEquals(2, repository.currentRows(account).size)
            assertEquals(1, repository.versions(account).size)
            assertEquals(2, repository.checkpoint(account, library)?.nextStartIndex)
        }

    private fun series(
        id: String,
        rating: String? = null,
    ) = SeriesInfo(ItemId(id), "Series $id", rating, listOf("x"), emptyList())

    @Test
    fun aFaultAfterTheSeriesRowsLeavesNoItemSeriesVersionOrCheckpointRow() =
        runBlocking {
            repository.select(account, setOf(library))
            val firstSeries = listOf(series("s1", "A"))
            repository.writePage(account, library, listOf(item("a", 90)), 1, 2, run = 1, series = firstSeries)
            val before = repository.rowCounts()
            val seriesBefore = repository.currentSeries(account)
            repository.faultBetweenItemsAndCheckpoint = { error("the process died here") }

            // The second page edits "a" and "s1" (two versions) and adds "b" and "s2".
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    val items = listOf(item("a", 100), item("b", 90))
                    val edited = listOf(series("s1", "B"), series("s2"))
                    repository.writePage(account, library, items, 2, 2, run = 1, series = edited)
                }
            }

            assertEquals(before, repository.rowCounts())
            assertEquals(seriesBefore, repository.currentSeries(account))
            assertEquals(emptyList<SeriesVersionEntity>(), repository.seriesVersions(account))
            assertEquals(1, repository.checkpoint(account, library)?.nextStartIndex)
        }
}
