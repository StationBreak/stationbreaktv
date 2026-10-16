// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Spec 06 task 3.0: what the first-sync screen and launch routing ask the saved library. "The first sync has ended" is
 * the account having a last-success time, so a later sync that fails does not bring the progress screen back.
 */
class FirstSyncPortTest {
    private val alice = LibraryAccount(ServerId("srv"), UserId("alice"))
    private val bob = LibraryAccount(ServerId("srv"), UserId("bob"))
    private val success = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun gate(status: LibraryStatus?) = InMemoryFirstSync(status)

    @Test
    fun nobodySignedInHasNothingToWaitFor() =
        runBlocking {
            assertTrue(gate(null).hasFinished())
            assertTrue(FirstSync.Finished.hasFinished())
        }

    @Test
    fun anAccountThatNeverSyncedHasNotFinished() =
        runBlocking {
            assertFalse(gate(LibraryStatus(SyncState.NeverSynced, null)).hasFinished())
        }

    @Test
    fun aRunningFirstSyncHasNotFinished() =
        runBlocking {
            assertFalse(gate(LibraryStatus(SyncState.FirstSyncRunning(10, 100), null)).hasFinished())
        }

    @Test
    fun aFirstSyncThatFailedHasNotFinished() =
        runBlocking {
            val failed = SyncState.LastSyncFailed(SyncFailure.Network)
            assertFalse(gate(LibraryStatus(failed, null)).hasFinished())
        }

    @Test
    fun anIdleLibraryHasFinished() =
        runBlocking {
            assertTrue(gate(LibraryStatus(SyncState.Idle, success)).hasFinished())
        }

    @Test
    fun aLaterSyncThatFailedLeavesTheFirstOneFinished() =
        runBlocking {
            val failed = SyncState.LastSyncFailed(SyncFailure.Network)
            assertTrue(gate(LibraryStatus(failed, success)).hasFinished())
        }

    @Test
    fun theFakeCountsWhatTheScreenAsksFor() {
        val fake = gate(LibraryStatus(SyncState.NeverSynced, null))
        fake.ensureRunning()
        fake.ensureRunning()
        fake.retry()
        assertEquals(2, fake.ensureCalls)
        assertEquals(1, fake.retryCalls)
    }

    @Test
    fun theActiveAccountIsTheOneSignedInNow() =
        runBlocking {
            val repository = InMemoryLibraryRepository()
            assertNull(repository.activeStatus().first())

            repository.useAccount(alice)
            repository.setStatus(alice, LibraryStatus(SyncState.Idle, success))
            assertEquals(LibraryStatus(SyncState.Idle, success), repository.activeStatus().first())

            repository.useAccount(bob)
            assertEquals(LibraryStatus(SyncState.NeverSynced, null), repository.activeStatus().first())

            repository.forget(bob)
            assertNull(repository.activeStatus().first())
        }
}
