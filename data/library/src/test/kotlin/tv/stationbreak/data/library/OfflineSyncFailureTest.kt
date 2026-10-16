// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.domain.hasFinished
import kotlin.time.Duration.Companion.hours

/**
 * Spec 06 task 4.8 (owner answer 4): with the server unreachable the saved library stays intact and usable, the time of
 * the last success is kept, and there is no error screen (the first-sync screen counts the first sync as finished, and
 * nothing else shows a sync error until Phase 1's banner reads the recorded failure).
 */
class OfflineSyncFailureTest {
    private val rig = StayCurrentRig()
    private val firstSync: FirstSync = WorkManagerFirstSync(RecordingEnqueuer(), rig.repository)

    @After
    fun close() = rig.close()

    private val both = SyncPlan(incremental = true, reconcile = true)
    private val account = ALICE

    private suspend fun snapshot() = rig.repository.snapshot(account, setOf(MOVIES), EPOCH)

    private suspend fun assertStillUsable(
        recorded: kotlin.time.Instant?,
        failure: SyncFailure,
    ) {
        assertEquals(300, snapshot().size)
        assertEquals(300, rig.repository.stored(account).count { it.deletedAt == null })
        val status = rig.repository.activeStatus().first()!!
        assertEquals(recorded, status.lastSuccess)
        assertEquals(SyncState.LastSyncFailed(failure), status.state)
        assertTrue("no error screen: the first sync still counts as finished", firstSync.hasFinished())
    }

    @Test
    fun aServerThatCannotBeReachedForTheSessionCheckChangesNothing() =
        runBlocking {
            rig.firstSyncDone()
            val recorded =
                rig.repository
                    .activeStatus()
                    .first()
                    ?.lastSuccess
            rig.clock.instant += 7.hours
            rig.fake.resumeResult = ServerResult.Failure(MediaServerError.NetworkUnreachable())

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Failure, verdict)
            assertStillUsable(recorded, SyncFailure.Network)
        }

    @Test
    fun aServerThatDiesDuringAPassChangesNothing() =
        runBlocking {
            rig.firstSyncDone()
            val recorded =
                rig.repository
                    .activeStatus()
                    .first()
                    ?.lastSuccess
            rig.clock.instant += 7.hours
            rig.library.items.clear()
            rig.server.respond = { MediaServerError.Timeout() }

            val verdict = rig.runner.run(both)

            assertEquals(SyncVerdict.Failure, verdict)
            assertStillUsable(recorded, SyncFailure.Network)
        }

    @Test
    fun aServerThatDiesDuringTheReconciliationKeepsTheLastSuccessOfTheEarlierPass() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            val passStart = rig.clock.now()
            rig.server.respond = { call -> if (call.idsOnly) MediaServerError.Timeout() else null }

            rig.runner.run(both)

            // The incremental pass ended well and recorded its start; the reconciliation failed and changed nothing.
            val status = rig.repository.activeStatus().first()!!
            assertEquals(passStart, status.lastSuccess)
            assertEquals(SyncState.LastSyncFailed(SyncFailure.Network), status.state)
            assertEquals(300, snapshot().size)
        }

    @Test
    fun theNextSuccessfulSyncClearsTheFailureAndMovesTheRecordedMoment() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            rig.server.respond = { MediaServerError.Timeout() }
            rig.runner.run(both)
            rig.server.respond = { null }
            rig.clock.instant += 1.hours

            rig.runner.run(both)

            val status = rig.repository.activeStatus().first()!!
            assertEquals(SyncState.Idle, status.state)
            assertEquals(rig.clock.now(), status.lastSuccess)
        }
}
