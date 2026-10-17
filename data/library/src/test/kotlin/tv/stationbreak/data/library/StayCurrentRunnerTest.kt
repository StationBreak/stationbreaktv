// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Spec 06 task 4.7 (the worker's side): what the quiet worker runs when WorkManager starts it. The plan says which of
 * the two passes to run; a sync already running, an unfinished first sync and a signed-out account each mean there is
 * nothing to do.
 */
class StayCurrentRunnerTest {
    private val rig = StayCurrentRig()
    private val server = rig.server

    @After
    fun close() = rig.close()

    private val both = SyncPlan(incremental = true, reconcile = true)

    @Test
    fun aPlanForBothRunsTheIncrementalPassThenTheReconciliation() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Success, verdict)
            val calls = server.calls
            val firstIdsCall = calls.indexOfFirst { it.idsOnly }
            assertTrue(calls.take(firstIdsCall).all { it.changedSince != null })
            assertTrue(firstIdsCall > 0 && calls.drop(firstIdsCall).all { it.idsOnly })
            assertEquals(
                rig.clock.now(),
                rig.repository
                    .activeStatus()
                    .first()
                    ?.lastSuccess,
            )
        }

    @Test
    fun aReconcileOnlyPlanNeverAsksForChangesAndNeverMovesTheRecordedMoment() =
        runBlocking {
            rig.firstSyncDone()
            val recorded =
                rig.repository
                    .activeStatus()
                    .first()
                    ?.lastSuccess
            rig.clock.instant += 1.hours

            rig.runner.run(SyncPlan(incremental = false, reconcile = true))

            assertTrue(server.calls.isNotEmpty() && server.calls.all { it.idsOnly })
            assertEquals(
                recorded,
                rig.repository
                    .activeStatus()
                    .first()
                    ?.lastSuccess,
            )
        }

    @Test
    fun anIncrementalOnlyPlanNeverListsIds() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours

            rig.runner.run(SyncPlan(incremental = true, reconcile = false))

            assertTrue(server.calls.isNotEmpty() && server.calls.none { it.idsOnly })
        }

    @Test
    fun aFailedIncrementalPassStopsBeforeTheReconciliation() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            server.respond = { MediaServerError.Timeout() }

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Failure, verdict)
            assertTrue(server.calls.none { it.idsOnly })
        }

    @Test
    fun anUnfinishedFirstSyncIsLeftToTheFirstSync() =
        runBlocking {
            rig.sessions.save(SyncWorld.session)
            rig.fake.resumeResult = ServerResult.Success(SyncWorld.session)

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Success, verdict)
            assertTrue(server.calls.isEmpty())
            assertEquals(
                SyncState.NeverSynced,
                rig.repository
                    .activeStatus()
                    .first()
                    ?.state,
            )
        }

    @Test
    fun aSignedOutAccountHasNothingToSync() =
        runBlocking {
            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Success, verdict)
            assertTrue(server.calls.isEmpty())
        }

    @Test
    fun aSessionTheServerRejectsIsRecordedAndNotRetried() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            rig.fake.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Failure, verdict)
            val status = rig.repository.activeStatus().first()!!
            assertEquals(SyncState.LastSyncFailed(SyncFailure.SessionExpired), status.state)
        }

    @Test
    fun aRunWhileAnotherIsRunningStartsNothingAndTheFlagFollowsTheRun() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            var nested: SyncVerdict? = null
            var duringRun = false
            var callsByNested = -1
            server.respond = { call ->
                if (call.number == 1) {
                    duringRun = rig.runner.isRunning
                    val before = server.calls.size
                    nested = runBlocking { rig.runner.run(both) }
                    callsByNested = server.calls.size - before
                }
                null
            }

            rig.runner.run(both)

            assertTrue(duringRun)
            assertEquals(SyncVerdict.Success, nested)
            assertEquals(0, callsByNested)
            assertFalse(rig.runner.isRunning)
        }

    @Test
    fun theFlagIsClearedEvenWhenTheRunThrows() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            server.respond = { throw IllegalStateException("a killed process") }

            runCatching { rig.runner.run(both) }

            assertFalse(rig.runner.isRunning)
        }

    @Test
    fun anExceptionFromThePassIsRecordedAsAServerFailureAndTheFlagIsCleared() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            val recorded =
                rig.repository
                    .activeStatus()
                    .first()
                    ?.lastSuccess
            server.respond = { throw IllegalStateException("a surprise") }

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Failure, verdict)
            val status = rig.repository.activeStatus().first()!!
            assertEquals(SyncState.LastSyncFailed(SyncFailure.Server), status.state)
            assertEquals(recorded, status.lastSuccess)
            assertFalse(rig.runner.isRunning)
        }

    @Test
    fun aCancelledRunIsNotRecordedAsAFailureAndLeavesTheMomentAndTheFlagAlone() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            val before = rig.repository.activeStatus().first()
            lateinit var job: kotlinx.coroutines.Job
            server.respond = { call ->
                if (call.number == 1) job.cancel()
                null
            }

            job = launch { rig.runner.run(both) }
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(before, rig.repository.activeStatus().first())
            assertFalse(rig.runner.isRunning)
        }

    // Spec 09 task 3.7: the history purge runs after a reconcile that completed, and never fails the run.

    // An item the server does not have, deleted 31 days before the run: the reconcile leaves it a tombstone, and a
    // purge takes it. The clock is moved to the day of the run, so the incremental pass records a fresh success.
    private suspend fun anOldTombstoneAndTheRunDay() {
        rig.firstSyncDone()
        rig.repository.upsertItems(ALICE, MOVIES, listOf(item("extra")))
        rig.repository.tombstone(ALICE, listOf(ItemId("extra")))
        rig.clock.instant += 31.days
    }

    private suspend fun extraIsStored() = rig.repository.stored(ALICE).any { it.item.id.value == "extra" }

    @Test
    fun aCompletedReconcilePurgesTheHistory() =
        runBlocking {
            anOldTombstoneAndTheRunDay()

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Success, verdict)
            assertFalse(extraIsStored())
            assertEquals(PurgeOutcome.Purged(tombstones = 1, versions = 0), rig.runner.lastPurge)
        }

    @Test
    fun anIncrementalOnlyRunNeverPurges() =
        runBlocking {
            anOldTombstoneAndTheRunDay()

            rig.runner.run(SyncPlan(incremental = true, reconcile = false))

            assertTrue(extraIsStored())
            assertEquals(null, rig.runner.lastPurge)
        }

    @Test
    fun aReconcileThatFailedDoesNotPurge() =
        runBlocking {
            anOldTombstoneAndTheRunDay()
            server.respond = { call -> if (call.idsOnly) MediaServerError.Timeout() else null }

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Failure, verdict)
            assertTrue(extraIsStored())
            assertEquals(null, rig.runner.lastPurge)
        }

    @Test
    fun aFailedIncrementalPassDoesNotPurge() =
        runBlocking {
            anOldTombstoneAndTheRunDay()
            server.respond = { MediaServerError.Timeout() }

            rig.runner.run(both)

            assertTrue(extraIsStored())
            assertEquals(null, rig.runner.lastPurge)
        }

    @Test
    fun aPurgeThatThrowsIsRecordedAndTheRunStillSucceeds() =
        runBlocking {
            anOldTombstoneAndTheRunDay()
            val boom = IllegalStateException("the disk is full")
            rig.runner.purge = { throw boom }
            val recorded =
                rig.repository
                    .activeStatus()
                    .first()
                    ?.lastSuccess

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Success, verdict)
            assertEquals(PurgeOutcome.Failed(boom), rig.runner.lastPurge)
            val status = rig.repository.activeStatus().first()!!
            assertEquals(SyncState.Idle, status.state)
            assertTrue(status.lastSuccess!! > recorded!!)
            assertFalse(rig.runner.isRunning)
        }

    @Test
    fun aClockBehindTheLastSuccessSkipsThePurgeAndTheSkipIsRecorded() =
        runBlocking {
            anOldTombstoneAndTheRunDay()
            // A reconcile-only run does not move the recorded moment, so the clock set back is behind it.
            rig.repository.finishSync(ALICE, rig.clock.now())
            rig.clock.instant -= 1.hours

            val verdict = rig.runner.run(SyncPlan(incremental = false, reconcile = true))

            assertEquals(SyncVerdict.Success, verdict)
            assertTrue(extraIsStored())
            assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockBehindLastSync), rig.runner.lastPurge)
        }

    @Test
    fun aSignOutDuringThePurgeEndsTheRunQuietlyWithNothingRecordedAsAFailure() =
        runBlocking {
            anOldTombstoneAndTheRunDay()
            rig.runner.purge = { throw AccountNotActiveException() }

            val outcome = rig.runner.outcome(both)

            assertEquals(SyncOutcome.Cancelled, outcome)
            val status = rig.repository.activeStatus().first()!!
            assertEquals(SyncState.Idle, status.state)
        }
}
