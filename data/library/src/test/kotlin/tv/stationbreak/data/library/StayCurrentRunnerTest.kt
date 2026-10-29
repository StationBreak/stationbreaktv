// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedCall
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.ItemId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * Spec 06 task 4.7 (the worker's side): what the quiet worker runs when WorkManager starts it. The plan says which of
 * the two passes to run; a sync already running, an unfinished first sync and a signed-out account each mean there is
 * nothing to do.
 */
class StayCurrentRunnerTest {
    private val lines = mutableListOf<String>()
    private val time = TestTimeSource()
    private val rig = StayCurrentRig(timeSource = time) { lines += it }
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

    // An item the server does not have, deleted at t0. A sync succeeded at t0 + 10 d and the run is at t0 + 31 d: the
    // tombstone is more than 30 days old and the last success is under 27 days old, so the guard lets the purge run.
    private suspend fun anOldTombstoneAndTheRunDay() {
        rig.firstSyncDone()
        rig.repository.upsertItems(ALICE, MOVIES, listOf(item("extra")))
        rig.repository.tombstone(ALICE, listOf(ItemId("extra")))
        rig.clock.instant += 10.days
        rig.repository.finishSync(ALICE)
        rig.clock.instant += 21.days
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
            rig.runner.purge = { _, _ -> throw boom }
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
            rig.runner.purge = { _, _ -> throw AccountNotActiveException() }

            val outcome = rig.runner.outcome(both)

            assertEquals(SyncOutcome.Cancelled, outcome)
            val status = rig.repository.activeStatus().first()!!
            assertEquals(SyncState.Idle, status.state)
        }

    // Vimes M-1 on #268: the incremental pass overwrites the last success, so the guard is given the one from
    // before the run.
    @Test
    fun aRunAMonthAfterTheLastSuccessSkipsThePurgeAndTheNextRunPurges() =
        runBlocking {
            rig.firstSyncDone()
            rig.repository.upsertItems(ALICE, MOVIES, listOf(item("extra")))
            rig.repository.tombstone(ALICE, listOf(ItemId("extra")))
            rig.clock.instant += 31.days

            rig.runner.run(both)

            assertTrue(extraIsStored())
            assertEquals(PurgeOutcome.Skipped(PurgeSkip.ClockFarAhead), rig.runner.lastPurge)

            // The run recorded a fresh success, so the next one has a fresh reference.
            rig.clock.instant += 1.hours
            rig.runner.run(both)

            assertFalse(extraIsStored())
            assertEquals(PurgeOutcome.Purged(tombstones = 1, versions = 0), rig.runner.lastPurge)
        }

    @Test
    fun thePurgeIsGivenTheSuccessRecordedBeforeTheRun() =
        runBlocking {
            anOldTombstoneAndTheRunDay()
            val before = rig.repository.lastSuccess(ALICE)
            var given: kotlin.time.Instant? = null
            rig.runner.purge = { _, reference ->
                given = reference
                PurgeOutcome.Purged(0, 0)
            }

            rig.runner.run(both)

            assertEquals(before, given)
            assertTrue(rig.repository.lastSuccess(ALICE)!! > before!!)
        }

    // Vimes L1: a sign-out during the collection pass ends the run as cancelled; it is not a pass result to keep.
    @Test
    fun aSignOutDuringTheCollectionPassIsNotKeptAsAFailedPass() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            rig.server.respondToCollections = {
                runBlocking { rig.repository.forget(ALICE) }
                null
            }

            val outcome = rig.runner.outcome(SyncPlan(incremental = true, reconcile = true))

            assertEquals(SyncOutcome.Cancelled, outcome)
            assertEquals(null, rig.runner.lastCollectionPass)
        }

    // Spec 18, ruling R9: the studios re-read runs last and on its own; it never skips or fails the other passes.

    private suspend fun backfillIsPending() {
        val (server, user) = ALICE.server.value to ALICE.user.value
        rig.repository.dao.ensureMetaRow(server, user)
        rig.repository.dao.setBackfillPending(server, user, true)
    }

    // A full walk's item page: no window and not an ID listing. Only the backfill asks for one in these runs.
    private fun isBackfill(call: PagedCall) = !call.idsOnly && call.changedSince == null

    @Test
    fun theBackfillRunsAfterEveryOtherPassAndClearsTheFlag() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours
            var callsBeforeCollections = -1
            var callsBeforePurge = -1
            server.respondToCollections = {
                if (callsBeforeCollections < 0) callsBeforeCollections = server.calls.size
                null
            }
            rig.runner.purge = { _, _ ->
                callsBeforePurge = server.calls.size
                PurgeOutcome.Purged(0, 0)
            }

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Success, verdict)
            val firstBackfillCall = server.calls.indexOfFirst { isBackfill(it) }
            assertTrue(firstBackfillCall > server.calls.indexOfLast { it.idsOnly })
            assertTrue(callsBeforeCollections in 0..firstBackfillCall)
            assertTrue(callsBeforePurge in 0..firstBackfillCall)
            assertEquals(SyncOutcome.Completed, rig.runner.lastBackfill?.outcome)
            assertFalse(rig.repository.backfillPending(ALICE))
        }

    @Test
    fun aFailedBackfillNeverFailsTheRunNorSkipsTheOtherPasses() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours
            server.respond = { call -> if (isBackfill(call)) MediaServerError.Timeout() else null }

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Success, verdict)
            assertEquals(SyncOutcome.Failed(SyncFailure.Network), rig.runner.lastBackfill?.outcome)
            assertTrue(rig.runner.lastPurge != null && rig.runner.lastCollectionPass != null)
            val status = rig.repository.activeStatus().first()!!
            assertEquals(SyncState.Idle, status.state)
            assertEquals(rig.clock.now(), status.lastSuccess)
            assertTrue(rig.repository.backfillPending(ALICE))
        }

    @Test
    fun aFailedIncrementalPassRunsNoBackfill() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours
            server.respond = { MediaServerError.Timeout() }

            rig.runner.run(both)

            assertNull(rig.runner.lastBackfill)
            assertTrue(server.calls.none { isBackfill(it) })
            assertTrue(rig.repository.backfillPending(ALICE))
        }

    // #460: a reconcile that failed does not stop the re-read; one that was cancelled (or a sign-out) stops everything.

    @Test
    fun aFailedReconcileDoesNotStopTheRereadAndLeavesTheRunsVerdictAlone() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours
            server.respond = { call -> if (call.idsOnly) MediaServerError.Timeout() else null }

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Failure, verdict)
            assertEquals(SyncOutcome.Completed, rig.runner.lastBackfill?.outcome)
            assertFalse(rig.repository.backfillPending(ALICE))
            assertEquals(
                SyncState.LastSyncFailed(SyncFailure.Network),
                rig.repository
                    .activeStatus()
                    .first()!!
                    .state,
            )
        }

    @Test
    fun aSuspectEmptyLibraryFailsTheReconcileButTheRereadStillRuns() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours
            // The ID listing comes back empty for a library that has rows: rejected as suspect. The library is back by
            // the time the re-read lists its items by page.
            val rows = rig.library.items.toList()
            server.respond = { call ->
                if (call.idsOnly) rig.library.items.clear()
                if (isBackfill(call) && rig.library.items.isEmpty()) rig.library.items.addAll(rows)
                null
            }

            val outcome = rig.runner.outcome(both)

            assertEquals(SyncOutcome.Failed(SyncFailure.Suspect), outcome)
            assertTrue(server.calls.any { isBackfill(it) })
            assertEquals(SyncOutcome.Completed, rig.runner.lastBackfill?.outcome)
            assertFalse(rig.repository.backfillPending(ALICE))
        }

    @Test
    fun aCancelledReconcileRunsNoReread() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours
            server.respond = { call ->
                if (call.idsOnly) runBlocking { rig.repository.forget(ALICE) }
                null
            }

            val outcome = rig.runner.outcome(both)

            assertEquals(SyncOutcome.Cancelled, outcome)
            assertNull(rig.runner.lastBackfill)
            assertTrue(server.calls.none { isBackfill(it) })
        }

    @Test
    fun aReconcileFailingWhileAFirstSyncRunsEndsCancelledWithNoReread() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours
            server.respond = { call ->
                if (call.idsOnly) {
                    runBlocking { rig.repository.beginFirstSync(ALICE) }
                    MediaServerError.Timeout()
                } else {
                    null
                }
            }

            val outcome = rig.runner.outcome(both)

            assertEquals(SyncOutcome.Cancelled, outcome)
            assertNull(rig.runner.lastBackfill)
            assertTrue(server.calls.none { isBackfill(it) })
        }

    @Test
    fun aCancellationInTheReconcileStopsTheRunAndRunsNoReread() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours
            server.respond = { call -> if (call.idsOnly) throw CancellationException("stopped") else null }

            try {
                rig.runner.run(both)
                fail("the cancellation should pass through")
            } catch (_: CancellationException) {
                // The stop.
            }

            assertNull(rig.runner.lastBackfill)
            assertTrue(server.calls.none { isBackfill(it) })
        }

    @Test
    fun aRereadOfNothingButUndecodableItemsEndsFailedUnrecordedAndSaysSo() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours
            server.respond = { call -> if (isBackfill(call)) MediaServerError.NotJellyfin() else null }

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Success, verdict)
            assertEquals(SyncOutcome.Failed(SyncFailure.Server), rig.runner.lastBackfill?.outcome)
            assertTrue(rig.repository.backfillPending(ALICE))
            assertEquals(
                SyncState.Idle,
                rig.repository
                    .activeStatus()
                    .first()!!
                    .state,
            )
            val done = lines.single { it.startsWith("BackfillDone ") }
            assertTrue(done, done.contains("outcome=Failed failure=Server "))
            assertTrue(done, done.endsWith("stillPending=true"))
        }

    // The numbers-only timing lines (logged under "Sync"): what ran, how long, how it ended.

    @Test
    fun anIncrementalRunWithTheRereadPendingReportsTheRunAndTheRereadTimings() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours
            server.respond = { call ->
                time += (if (isBackfill(call)) 250 else 0).milliseconds
                if (call.idsOnly) time += 100.milliseconds
                null
            }

            rig.runner.run(both)

            assertEquals(
                listOf(
                    "StayCurrent incremental=true reconcile=true",
                    "StayCurrentDone ms=${100 * server.calls.count { it.idsOnly }} outcome=Completed",
                    "Backfill pending=true",
                ),
                lines.take(3),
            )
            val done = lines.single { it.startsWith("BackfillDone ") }
            val ms = 250 * server.calls.count { isBackfill(it) }
            assertTrue(done, done.startsWith("BackfillDone ms=$ms outcome=Completed "))
            assertTrue(done, done.contains(" inserted=0 updated=0 unchanged=300 skipped=0 "))
            assertTrue(done, done.endsWith("stillPending=false"))
        }

    @Test
    fun aRunWithoutTheIncrementalPassReportsNoRereadLines() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()

            rig.runner.run(SyncPlan(incremental = false, reconcile = true))

            assertEquals("StayCurrent incremental=false reconcile=true", lines.first())
            assertTrue(lines.none { it.startsWith("Backfill") })
        }

    @Test
    fun anIncrementalRunWithNothingPendingReportsOnlyThePendingLine() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours

            rig.runner.run(both)

            assertTrue("Backfill pending=false" in lines)
            assertTrue(lines.none { it.startsWith("BackfillDone") })
        }

    @Test
    fun aFailedRereadNamesTheFailureAndNothingElse() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours
            server.respond = { call -> if (isBackfill(call)) MediaServerError.Timeout() else null }

            rig.runner.run(both)

            val done = lines.single { it.startsWith("BackfillDone ") }
            assertTrue(done, done.contains("outcome=Failed failure=Network "))
            assertTrue(done, done.endsWith("stillPending=true"))
        }

    @Test
    fun noReportedLineCarriesAnIdAnAddressOrAUserFromTheFixtures() =
        runBlocking {
            rig.firstSyncDone()
            backfillIsPending()
            rig.clock.instant += 7.hours

            rig.runner.run(both)

            val secrets =
                listOf(
                    ALICE.server.value,
                    ALICE.user.value,
                    MOVIES.value,
                    SyncWorld.session.address.toString(),
                    SyncWorld.session.userId.value,
                )
            assertTrue(lines.isNotEmpty())
            for (line in lines) {
                for (secret in secrets) assertFalse("$line leaks $secret", line.contains(secret))
                assertTrue(line, line.all { it.isLetterOrDigit() || it == ' ' || it == '=' || it == '/' })
            }
        }

    @Test
    fun aReporterThatThrowsChangesNothingAboutTheRun() =
        runBlocking {
            val loud = StayCurrentRig(report = { error("the log is broken") })
            try {
                loud.firstSyncDone()
                loud.repository.dao.ensureMetaRow(ALICE.server.value, ALICE.user.value)
                loud.repository.dao.setBackfillPending(ALICE.server.value, ALICE.user.value, true)
                loud.clock.instant += 7.hours

                assertEquals(SyncOutcome.Completed, loud.runner.outcome(both))
                assertEquals(SyncOutcome.Completed, loud.runner.lastBackfill?.outcome)
                assertFalse(loud.repository.backfillPending(ALICE))
            } finally {
                loud.close()
            }
        }

    @Test
    fun aRunStoppedMidPassReportsItAsCancelledAndStaysStopped() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            rig.runner.purge = { _, _ -> throw kotlinx.coroutines.CancellationException("stopped") }

            val stopped = runCatching { rig.runner.run(both) }.exceptionOrNull()

            assertTrue(stopped is kotlinx.coroutines.CancellationException)
            assertEquals("StayCurrentDone ms=0 outcome=Cancelled", lines.last())
        }

    @Test
    fun aFailedMainPassReportsTheFailureName() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            server.respond = { MediaServerError.Timeout() }

            rig.runner.run(both)

            assertTrue(lines.toString(), "StayCurrentDone ms=0 outcome=Failed failure=Network" in lines)
        }
}
