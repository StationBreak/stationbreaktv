// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 06 task 4.6 (owner answers 1 and 5): what an app open starts. The ID reconciliation runs on every open; the
 * incremental pass only when the last successful pass is over 6 hours old; the periodic job does both; and an open
 * during a sync that is running starts nothing. The policy is a pure class over a fake clock.
 */
class SyncTriggerPolicyTest {
    private val clock = FakeClock()
    private val policy = SyncTriggerPolicy(clock)

    private fun idle(lastSuccess: kotlin.time.Instant?) = LibraryStatus(SyncState.Idle, lastSuccess)

    private val reconcileOnly = SyncPlan(incremental = false, reconcile = true)

    // The pure rules.

    @Test
    fun everyOpenReconcilesWhateverTheAgeOfTheLastPass() {
        listOf(0.minutes, 1.minutes, 5.hours, 6.hours, 7.hours, 30.days).forEach { age ->
            val plan = policy.onOpen(idle(clock.now() - age), syncRunning = false)
            assertTrue("reconcile at $age", plan.reconcile)
        }
    }

    @Test
    fun theIncrementalPassOnlyRunsWhenTheLastSuccessIsOverSixHoursOld() {
        assertEquals(reconcileOnly, policy.onOpen(idle(clock.now() - 1.minutes), syncRunning = false))
        assertEquals(reconcileOnly, policy.onOpen(idle(clock.now() - 5.hours - 59.minutes), syncRunning = false))
        // Exactly six hours is not "over".
        assertEquals(reconcileOnly, policy.onOpen(idle(clock.now() - 6.hours), syncRunning = false))
        assertEquals(SyncPlan.Both, policy.onOpen(idle(clock.now() - 6.hours - 1.milliseconds), syncRunning = false))
        assertEquals(SyncPlan.Both, policy.onOpen(idle(clock.now() - 30.days), syncRunning = false))
    }

    @Test
    fun aMovingClockMovesTheRule() {
        val recorded = clock.now()
        assertEquals(reconcileOnly, policy.onOpen(idle(recorded), syncRunning = false))
        clock.instant += 6.hours + 1.minutes
        assertEquals(SyncPlan.Both, policy.onOpen(idle(recorded), syncRunning = false))
    }

    @Test
    fun aFailedLastSyncStillFollowsTheSameRulesFromTheLastSuccess() {
        val failed = LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Network), clock.now() - 1.hours)
        assertEquals(reconcileOnly, policy.onOpen(failed, syncRunning = false))
        val failedLongAgo = LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Network), clock.now() - 2.days)
        assertEquals(SyncPlan.Both, policy.onOpen(failedLongAgo, syncRunning = false))
    }

    @Test
    fun anOpenDuringARunningSyncStartsNothing() {
        assertEquals(SyncPlan.None, policy.onOpen(idle(clock.now() - 30.days), syncRunning = true))
    }

    @Test
    fun anUnfinishedFirstSyncOrNobodySignedInStartsNothing() {
        val running = LibraryStatus(SyncState.FirstSyncRunning(copied = 10, total = 100), null)
        assertEquals(SyncPlan.None, policy.onOpen(running, syncRunning = false))
        assertEquals(SyncPlan.None, policy.onOpen(LibraryStatus(SyncState.NeverSynced, null), syncRunning = false))
        val failedFirst = LibraryStatus(SyncState.LastSyncFailed(SyncFailure.Server), null)
        assertEquals(SyncPlan.None, policy.onOpen(failedFirst, syncRunning = false))
        assertEquals(SyncPlan.None, policy.onOpen(null, syncRunning = false))
    }

    @Test
    fun aLastSuccessInTheFutureCountsAsStale() {
        // The TV clock was set back: a recorded moment ahead of "now" cannot be trusted, so the incremental pass runs.
        assertEquals(SyncPlan.Both, policy.onOpen(idle(clock.now() + 1.hours), syncRunning = false))
        assertEquals(SyncPlan.Both, policy.onOpen(idle(clock.now() + 30.days), syncRunning = false))
    }

    @Test
    fun thePeriodicJobDoesBoth() {
        assertEquals(SyncPlan.Both, policy.periodic())
    }

    // The opening, over the real runner and a recording scheduler.

    private val rig = StayCurrentRig(clock)
    private val enqueuer = RecordingEnqueuer()
    private val stayCurrent = StayCurrent(enqueuer, enqueuer, rig.repository, policy, rig.runner)

    @After
    fun close() = rig.close()

    private fun plansEnqueued() =
        enqueuer.enqueued.map {
            val data = it.third.workSpec.input
            val incremental = data.getBoolean(StayCurrentWork.KEY_INCREMENTAL, false)
            SyncPlan(incremental, data.getBoolean(StayCurrentWork.KEY_RECONCILE, false))
        }

    @Test
    fun anOpenSoonAfterAPassAsksForTheReconciliationAlone() =
        runBlocking {
            rig.firstSyncDone()
            clock.instant += 1.hours

            stayCurrent.onOpen()

            assertEquals(listOf(reconcileOnly), plansEnqueued())
            assertEquals(listOf(StayCurrentWork.OPEN_NAME), enqueuer.enqueued.map { it.first })
            assertEquals(listOf(ExistingWorkPolicy.APPEND_OR_REPLACE), enqueuer.enqueued.map { it.second })
        }

    @Test
    fun anOpenAfterSevenHoursAsksForBoth() =
        runBlocking {
            rig.firstSyncDone()
            clock.instant += 7.hours

            stayCurrent.onOpen()

            assertEquals(listOf(SyncPlan.Both), plansEnqueued())
        }

    @Test
    fun everyOpenAsksAndTheAppendPolicyKeepsALaterBothPlanBehindAQueuedOne() =
        runBlocking {
            rig.firstSyncDone()

            stayCurrent.onOpen()
            stayCurrent.onOpen()

            assertEquals(listOf(ExistingWorkPolicy.APPEND_OR_REPLACE, ExistingWorkPolicy.APPEND_OR_REPLACE), enqueuer.enqueued.map { it.second })
            assertEquals(setOf(StayCurrentWork.OPEN_NAME), enqueuer.enqueued.map { it.first }.toSet())
        }

    @Test
    fun aSecondOpenWhileASyncIsRunningStartsNothingNew() =
        runBlocking {
            rig.firstSyncDone()
            clock.instant += 7.hours
            rig.server.respond = { call ->
                if (call.number == 1) runBlocking { stayCurrent.onOpen() }
                null
            }

            rig.runner.run(SyncPlan.Both)

            assertTrue("the open during the run started nothing", enqueuer.enqueued.isEmpty())
        }

    @Test
    fun anOpenWithNobodySignedInOrBeforeTheFirstSyncEndsStartsNothing() =
        runBlocking {
            stayCurrent.onOpen()
            rig.sessions.save(SyncWorld.session)
            stayCurrent.onOpen()

            assertTrue(enqueuer.enqueued.isEmpty())
        }

    @Test
    fun everyOpenAlsoMakesSureThePeriodicJobIsScheduledKeepingTheOneThatExists() =
        runBlocking {
            rig.firstSyncDone()

            stayCurrent.onOpen()
            stayCurrent.onOpen()

            assertEquals(
                listOf(ExistingPeriodicWorkPolicy.KEEP, ExistingPeriodicWorkPolicy.KEEP),
                enqueuer.periodic.map { it.second },
            )
            assertEquals(setOf(StayCurrentWork.PERIODIC_NAME), enqueuer.periodic.map { it.first }.toSet())
        }
}
