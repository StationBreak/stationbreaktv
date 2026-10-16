// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.domain.hasFinished
import kotlin.time.Duration.Companion.hours

/**
 * Spec 06 task 4.8 (owner answer 3): every sync after the first is quiet. The only thing that shows the user anything
 * is the first-sync progress screen, and it follows two facts: the state "first sync running" and the absence of a
 * last-success time. A later sync, whatever it does, must never bring either back, so no screen or message can follow
 * from it. (The sync code lives in `data:library`, which cannot reach any UI.)
 */
class QuietSyncTest {
    private val rig = StayCurrentRig()
    private val firstSync: FirstSync = WorkManagerFirstSync(RecordingEnqueuer(), rig.repository)

    @After
    fun close() = rig.close()

    private val both = SyncPlan(incremental = true, reconcile = true)

    /** What the progress screen would decide from [status] at that moment. */
    private fun screenWouldShow(status: LibraryStatus?) =
        status != null && (status.lastSuccess == null || status.state is SyncState.FirstSyncRunning)

    @Test
    fun noPassEverPutsTheAccountBackIntoTheFirstSyncState() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            rig.library.items.removeAt(3)
            val seen = mutableListOf<LibraryStatus?>()
            rig.server.respond = {
                seen += runBlocking { rig.repository.activeStatus().first() }
                null
            }

            rig.runner.run(both)

            assertTrue("the passes asked the server", seen.size > 2)
            assertTrue(seen.none { screenWouldShow(it) })
            assertTrue(firstSync.hasFinished())
        }

    @Test
    fun aFailedLaterSyncDoesNotBringTheProgressScreenBack() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            rig.server.respond = { MediaServerError.Timeout() }

            rig.runner.run(both)

            val status = rig.repository.activeStatus().first()
            assertTrue(status?.state is SyncState.LastSyncFailed)
            assertTrue(!screenWouldShow(status))
            assertTrue(firstSync.hasFinished())
        }

    @Test
    fun aLaterSyncThatFinishesLeavesTheAccountIdle() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours

            rig.runner.run(both)

            assertEquals(
                SyncState.Idle,
                rig.repository
                    .activeStatus()
                    .first()
                    ?.state,
            )
        }
}
