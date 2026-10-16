// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Spec 06 task 2.11: the four sync states and the running count are reported by the status flow. */
class SyncStateFlowTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)

    @After
    fun close() = repository.close()

    // Room reports a change on its own thread, so wait for the state instead of assuming when it arrives.
    private suspend fun Flow<LibraryStatus>.until(wanted: LibraryStatus): LibraryStatus {
        val matches = { status: LibraryStatus -> status == wanted }
        return withTimeout(10.seconds) { first(matches) }
    }

    private suspend fun status() = repository.status(ALICE).first()

    @Test
    fun anAccountWithNothingSavedIsNeverSynced() =
        runBlocking {
            assertEquals(LibraryStatus(SyncState.NeverSynced, null), status())
        }

    @Test
    fun theFirstSyncReportsARunningCountAndTheTotalOncePageOneSaidIt() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.beginFirstSync(ALICE)
            assertEquals(LibraryStatus(SyncState.FirstSyncRunning(copied = 0, total = null), null), status())

            repository.setTotal(ALICE, 4)
            repository.writePage(ALICE, MOVIES, listOf(item("a"), item("b")), 2, 4, run = 1)

            assertEquals(LibraryStatus(SyncState.FirstSyncRunning(copied = 2, total = 4), null), status())
        }

    @Test
    fun theRunningCountKeepsUpWithEveryPage() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.beginFirstSync(ALICE)
            repository.setTotal(ALICE, 6)

            val seen = repository.status(ALICE)
            repository.writePage(ALICE, MOVIES, listOf(item("a"), item("b")), 2, 6, run = 1)
            seen.until(LibraryStatus(SyncState.FirstSyncRunning(2, 6), null))
            repository.writePage(ALICE, MOVIES, listOf(item("c"), item("d")), 4, 6, run = 1)
            seen.until(LibraryStatus(SyncState.FirstSyncRunning(4, 6), null))
            repository.writePage(ALICE, MOVIES, listOf(item("e"), item("f")), 6, 6, run = 1)
            seen.until(LibraryStatus(SyncState.FirstSyncRunning(6, 6), null))
            Unit
        }

    @Test
    fun aReplayedPageDoesNotCountTwice() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.beginFirstSync(ALICE)
            val page = listOf(item("a"), item("b"))

            repository.writePage(ALICE, MOVIES, page, 2, 4, run = 1)
            repository.writePage(ALICE, MOVIES, page, 2, 4, run = 1)

            assertEquals(SyncState.FirstSyncRunning(copied = 2, total = null), status().state)
        }

    @Test
    fun aFinishedSyncIsIdleWithTheTimeOfTheSuccess() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.beginFirstSync(ALICE)

            repository.finishSync(ALICE)

            assertEquals(LibraryStatus(SyncState.Idle, clock.instant), status())
        }

    @Test
    fun aFailedSyncSaysWhyAndKeepsTheLastSuccess() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.finishSync(ALICE)
            val success = clock.instant
            clock.instant += 7.hours

            repository.failSync(ALICE, SyncFailure.Network)

            assertEquals(LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Network), success), status())
        }

    @Test
    fun aFirstSyncThatFailedThenRestartedIsRunningAgain() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.beginFirstSync(ALICE)
            repository.failSync(ALICE, SyncFailure.SessionExpired)
            assertEquals(LibraryStatus(SyncState.LastSyncFailed(SyncFailure.SessionExpired), null), status())

            repository.beginFirstSync(ALICE)

            assertEquals(SyncState.FirstSyncRunning(0, null), status().state)
        }

    @Test
    fun everyFailureKindRoundTrips() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            SyncFailure.entries.forEach { failure ->
                repository.failSync(ALICE, failure)
                assertEquals(SyncState.LastSyncFailed(failure), status().state)
            }
        }

    @Test
    fun theStatusFlowEmitsOnEveryChange() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            val flow = repository.status(ALICE)
            flow.until(LibraryStatus(SyncState.NeverSynced, null))

            repository.beginFirstSync(ALICE)
            flow.until(LibraryStatus(SyncState.FirstSyncRunning(0, null), null))
            repository.finishSync(ALICE)
            flow.until(LibraryStatus(SyncState.Idle, clock.instant))
            repository.failSync(ALICE, SyncFailure.Server)
            flow.until(LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Server), clock.instant))
            Unit
        }

    @Test
    fun accountsHaveTheirOwnState() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.finishSync(ALICE)

            assertEquals(LibraryStatus(SyncState.NeverSynced, null), repository.status(BOB).first())
        }

    @Test
    fun forgettingAnAccountResetsItsState() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            repository.finishSync(ALICE)

            repository.forget(ALICE)

            assertEquals(LibraryStatus(SyncState.NeverSynced, null), status())
        }
}
