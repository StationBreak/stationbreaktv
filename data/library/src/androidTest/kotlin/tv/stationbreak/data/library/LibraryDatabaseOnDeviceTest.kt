// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Spec 06 task 2.14: the saved library on the platform SQLite of the API 25 emulator (SQLite 3.9, like Fire OS 6's),
 * through the same `create` the app uses. The JVM tests run the same SQL over a newer SQLite; this proves nothing newer
 * than API 25's is needed: foreign keys that cascade, a transaction that rolls back, and the file in the `databases`
 * directory.
 * Generated placeholder data only.
 */
@RunWith(AndroidJUnit4::class)
class LibraryDatabaseOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val account = LibraryAccount(ServerId("device-test-server"), UserId("device-test-user"))
    private val library = ItemId("device-test-library")
    private val created = Instant.fromEpochMilliseconds(1_600_000_000_000)
    private lateinit var repository: RoomLibraryRepository

    @Before
    fun open() {
        context.deleteDatabase(RoomLibraryRepository.DATABASE_NAME)
        repository = RoomLibraryRepository.create(context)
    }

    @After
    fun close() {
        repository.close()
        context.deleteDatabase(RoomLibraryRepository.DATABASE_NAME)
    }

    private fun item(id: String) =
        MediaItem(
            id = ItemId(id),
            name = "Item $id",
            kind = ItemKind.Movie,
            runtime = 90.minutes,
            parentId = library,
            dateCreated = created,
            genres = listOf("one", "two"),
            tags = listOf("tag"),
        )

    private val later = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun aPageRoundTripsAndTheFileIsInTheDatabasesDirectory() =
        runBlocking {
            repository.select(account, setOf(library))
            repository.writePage(account, library, listOf(item("a"), item("b")), 2, 2, run = 1)

            assertEquals(listOf(item("a"), item("b")), repository.snapshot(account, setOf(library), later))
            val file = context.getDatabasePath(RoomLibraryRepository.DATABASE_NAME)
            assertTrue(file.isFile)
            assertEquals("databases", file.parentFile?.name)
        }

    @Test
    fun aFailureBetweenTheItemsAndTheCheckpointRollsBack() =
        runBlocking {
            repository.select(account, setOf(library))
            repository.faultBetweenItemsAndCheckpoint = { error("the process died here") }

            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.writePage(account, library, listOf(item("a")), 1, 1, run = 1) }
            }

            assertEquals(RowCounts.NONE.copy(libraries = 1), repository.rowCounts())
            assertNull(repository.checkpoint(account, library))
        }

    @Test
    fun deletingALibraryCascadesToItsItemsGenresTagsAndCheckpoint() =
        runBlocking {
            repository.select(account, setOf(library))
            repository.writePage(account, library, listOf(item("a")), 1, 1, run = 1)

            repository.select(account, emptySet())

            assertEquals(RowCounts.NONE, repository.rowCounts())
        }

    @Test
    fun aPageForALibraryThatWasNotChosenIsRefused() {
        assertThrows(Exception::class.java) {
            runBlocking { repository.writePage(account, library, listOf(item("a")), 1, 1, run = 1) }
        }
        runBlocking { assertEquals(RowCounts.NONE, repository.rowCounts()) }
    }

    @Test
    fun theStatusAndTheLastSuccessSurviveAReopen() =
        runBlocking {
            repository.select(account, setOf(library))
            repository.finishSync(account)
            val success = repository.status(account).first().lastSuccess
            repository.close()

            repository = RoomLibraryRepository.create(context)

            val status = repository.status(account).first()
            assertEquals(LibraryStatus(SyncState.Idle, success), status)
            assertTrue(success != null && success <= Clock.System.now())
        }

    @Test
    fun aBigPageStaysUnderTheVariableLimit() =
        runBlocking {
            val many = (0 until 1200).map { item("many-${it.toString().padStart(4, '0')}") }
            repository.select(account, setOf(library))

            repository.writePage(account, library, many, many.size, many.size, run = 1)

            assertEquals(1200, repository.snapshot(account, setOf(library), later).size)
        }
}
