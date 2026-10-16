// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import java.util.concurrent.TimeUnit

/**
 * Spec 06 task 3.6: the first sync is one unique piece of work with a network constraint and growing waits. A network
 * failure retries, a rejected session stops with no loop, and a second start while one is running does nothing.
 */
class SyncRetryPolicyTest {
    // What the worker does with each way the sync can end.

    @Test
    fun aNetworkFailureRetriesWhateverTheAttempt() {
        val network = SyncOutcome.Failed(SyncFailure.Network)
        listOf(0, 1, 5, 50).forEach { assertEquals(SyncVerdict.Retry, SyncRetryPolicy.verdict(network, it)) }
    }

    @Test
    fun aRejectedSessionStopsAndNeverLoops() {
        val rejected = SyncOutcome.Failed(SyncFailure.SessionExpired)
        listOf(0, 1, 5, 50).forEach { assertEquals(SyncVerdict.Failure, SyncRetryPolicy.verdict(rejected, it)) }
    }

    @Test
    fun aServerFailureRetriesAFewTimesThenStops() {
        val server = SyncOutcome.Failed(SyncFailure.Server)
        val verdicts = (0..SyncRetryPolicy.MAX_SERVER_ATTEMPTS + 1).map { SyncRetryPolicy.verdict(server, it) }
        assertEquals(
            List(SyncRetryPolicy.MAX_SERVER_ATTEMPTS) { SyncVerdict.Retry } + List(2) { SyncVerdict.Failure },
            verdicts,
        )
    }

    @Test
    fun aFinishedSyncIsSuccessAndACancelledOneIsNotRetried() {
        assertEquals(SyncVerdict.Success, SyncRetryPolicy.verdict(SyncOutcome.Completed, 0))
        assertEquals(SyncVerdict.Failure, SyncRetryPolicy.verdict(SyncOutcome.Cancelled, 0))
    }

    // The work request.

    @Test
    fun theWorkNeedsTheNetworkAndWaitsLongerEachTime() {
        val spec = FirstSyncWork.request().workSpec

        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(FirstSyncWork.BACKOFF_SECONDS), spec.backoffDelayDuration)
        val waits =
            (1..4).map { attempt ->
                spec.runAttemptCount = attempt
                spec.lastEnqueueTime = 0L
                spec.calculateNextRunTime()
            }
        assertEquals(waits.sorted(), waits)
        assertEquals(4, waits.toSet().size)
    }

    @Test
    fun theWorkCarriesNoInputData() {
        val spec = FirstSyncWork.request().workSpec

        assertTrue(spec.input.keyValueMap.isEmpty())
    }

    // One unique piece of work.

    private class RecordingEnqueuer : WorkEnqueuer {
        val enqueued = mutableListOf<Triple<String, ExistingWorkPolicy, OneTimeWorkRequest>>()
        val cancelled = mutableListOf<String>()

        override fun enqueueUnique(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            enqueued += Triple(name, policy, request)
        }

        override fun cancelUnique(name: String) {
            cancelled += name
        }
    }

    private val enqueuer = RecordingEnqueuer()
    private val repository = TestLibraries.inMemory()

    @After
    fun close() = repository.close()

    @Test
    fun startingTwiceAsksForOneUniqueNameThatKeepsTheRunningWork() {
        val firstSync = WorkManagerFirstSync(enqueuer, repository)

        firstSync.ensureRunning()
        firstSync.ensureRunning()

        assertEquals(listOf(FirstSyncWork.UNIQUE_NAME, FirstSyncWork.UNIQUE_NAME), enqueuer.enqueued.map { it.first })
        assertEquals(listOf(ExistingWorkPolicy.KEEP, ExistingWorkPolicy.KEEP), enqueuer.enqueued.map { it.second })
    }

    @Test
    fun retryReplacesTheWorkSoItStartsNow() {
        WorkManagerFirstSync(enqueuer, repository).retry()

        assertEquals(listOf(ExistingWorkPolicy.REPLACE), enqueuer.enqueued.map { it.second })
        assertEquals(FirstSyncWork.UNIQUE_NAME, enqueuer.enqueued.single().first)
    }

    @Test
    fun cancellingStopsTheUniqueWork() {
        WorkManagerSyncControl(enqueuer).cancelFirstSync()

        assertEquals(listOf(FirstSyncWork.UNIQUE_NAME), enqueuer.cancelled)
    }

    @Test
    fun theStatusIsTheActiveAccountsStatus() =
        runBlocking {
            repository.useAccount(ALICE)

            val status = WorkManagerFirstSync(enqueuer, repository).status().first()

            assertEquals(SyncState.NeverSynced, status?.state)
        }

    // The runner: what the worker does when WorkManager starts it.

    private val fake = FakeMediaServer()
    private val server = PagedMediaServer(listOf(movies), mapOf(MOVIES to SyncWorld.source("m", 30)), fake)
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, server, repository)

    private val clock = FakeClock()

    private fun runner(stored: InMemoryServerSettings) = FirstSyncRunner(stored, sessions, server, repository, clock)

    private fun settings(chosen: Set<tv.stationbreak.core.model.ItemId>? = setOf(MOVIES)) =
        InMemoryServerSettings(ServerRecord(session.address), chosen)

    @Test
    fun aSyncThatFinishesIsSuccessAndLeavesTheLibraryIdle() =
        runBlocking {
            sessions.save(session)
            fake.resumeResult = ServerResult.Success(session)

            val verdict = runner(settings()).run(attempt = 0)

            assertEquals(SyncVerdict.Success, verdict)
            assertEquals(SyncState.Idle, repository.activeStatus().first()?.state)
            assertEquals(30, repository.stored(ALICE).size)
        }

    @Test
    fun aSessionTheServerRejectsStopsTheWorkWithNoRetry() =
        runBlocking {
            sessions.save(session)
            fake.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())

            val verdict = runner(settings()).run(attempt = 0)

            assertEquals(SyncVerdict.Failure, verdict)
            assertTrue(store.snapshot().isEmpty())
        }

    @Test
    fun anOfflineServerRetriesAndTheLibraryReportsTheNetworkFailure() =
        runBlocking {
            sessions.save(session)
            fake.resumeResult = ServerResult.Failure(MediaServerError.Timeout())

            val verdict = runner(settings()).run(attempt = 0)

            assertEquals(SyncVerdict.Retry, verdict)
            assertEquals(SyncState.LastSyncFailed(SyncFailure.Network), repository.activeStatus().first()?.state)
            assertFalse(store.snapshot().isEmpty())
        }

    @Test
    fun noStoredServerOrNoChoiceIsNothingToDoAndNoRetry() =
        runBlocking {
            sessions.save(session)
            fake.resumeResult = ServerResult.Success(session)

            assertEquals(SyncVerdict.Failure, runner(InMemoryServerSettings()).run(attempt = 0))
            assertEquals(SyncVerdict.Failure, runner(settings(chosen = null)).run(attempt = 0))
            assertEquals(0, repository.stored(ALICE).size)
        }
}
