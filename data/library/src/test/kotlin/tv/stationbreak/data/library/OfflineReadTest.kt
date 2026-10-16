// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import kotlin.time.Duration.Companion.hours

/**
 * Spec 06 task 2.11 (owner answer 4): the saved library and the time of the last success answer with no network. The
 * repository has no server in its constructor at all; this proves the answers also come from the file alone, after the
 * process that wrote them is gone.
 */
class OfflineReadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()

    @Test
    fun theLibraryAndTheLastSuccessSurviveAReopenWithNothingElseRunning() =
        runBlocking {
            val written = TestLibraries.onDisk(folder.root, clock = clock)
            written.select(ALICE, setOf(MOVIES))
            written.writePage(ALICE, MOVIES, listOf(item("a"), item("b")), nextStartIndex = 2, totalCount = 2, run = 1)
            written.finishSync(ALICE)
            val success = clock.instant
            written.close()

            // A later start with the server off: a new repository on the same file, and a clock that has moved on.
            clock.instant += 30.hours
            val reopened = TestLibraries.onDisk(folder.root, clock = clock)

            assertEquals(listOf("a", "b"), reopened.snapshot(ALICE, setOf(MOVIES), EPOCH).map { it.id.value })
            assertEquals(LibraryStatus(SyncState.Idle, success), reopened.status(ALICE).first())
            reopened.close()
        }

    @Test
    fun aFailedSyncLeavesTheLibraryAndTheLastSuccessUsable() =
        runBlocking {
            val repository = TestLibraries.onDisk(folder.root, clock = clock)
            repository.select(ALICE, setOf(MOVIES))
            repository.writePage(ALICE, MOVIES, listOf(item("a")), nextStartIndex = 1, totalCount = 1, run = 1)
            repository.finishSync(ALICE)
            val success = clock.instant
            clock.instant += 7.hours

            repository.failSync(ALICE, SyncFailure.Network)

            assertEquals(listOf("a"), repository.snapshot(ALICE, setOf(MOVIES), EPOCH).map { it.id.value })
            val expected = LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Network), success)
            assertEquals(expected, repository.status(ALICE).first())
            repository.close()
        }

    @Test
    fun theDatabaseFileIsOneFileInTheDatabasesFolder() {
        // It is created by name, with no path, so Android puts it in the app's `databases` directory: the `database`
        // backup domain, which BackupExclusionTest (app) keeps excluded.
        assertEquals("library.db", RoomLibraryRepository.DATABASE_NAME)
        assertEquals(false, RoomLibraryRepository.DATABASE_NAME.contains('/'))
    }
}
