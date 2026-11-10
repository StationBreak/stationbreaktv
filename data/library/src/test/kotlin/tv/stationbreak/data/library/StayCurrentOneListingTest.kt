// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ServerResult
import kotlin.time.Duration.Companion.hours

/**
 * #654: one stay-current run lists the server's libraries once, whatever the plan: the passes, the selection's follow
 * and the studios re-read all use that one listing. Invented libraries only.
 */
class StayCurrentOneListingTest {
    private val rig = StayCurrentRig()

    @After
    fun close() = rig.close()

    private suspend fun listedBy(block: suspend () -> Unit): Int {
        val before = rig.server.librariesCount
        block()
        return rig.server.librariesCount - before
    }

    @Test
    fun aRunWithBothPassesAndTheFollowListsTheServerOnce() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours

            val listed = listedBy { assertEquals(SyncOutcome.Completed, rig.runner.outcome(SyncPlan.Both)) }

            assertEquals(1, listed)
            assertEquals(listOf("expire", "follow"), rig.asked)
            assertEquals(1, rig.followed.size)
        }

    @Test
    fun aRunWithTheStudiosReReadListsTheServerOnce() =
        runBlocking {
            rig.firstSyncDone()
            rig.repository.dao.ensureMetaRow(ALICE.server.value, ALICE.user.value)
            rig.repository.dao.setBackfillPending(ALICE.server.value, ALICE.user.value, true)
            rig.clock.instant += 7.hours

            val listed = listedBy { assertEquals(SyncOutcome.Completed, rig.runner.outcome(SyncPlan.Both)) }

            assertEquals(1, listed)
        }

    @Test
    fun aReconcileOnlyRunListsTheServerOnce() =
        runBlocking {
            rig.firstSyncDone()

            val listed = listedBy { rig.runner.outcome(SyncPlan(incremental = false, reconcile = true)) }

            assertEquals(1, listed)
        }

    @Test
    fun aRunWhileTheSelectionIsGoneListsAndFollowsOnce() =
        runBlocking {
            rig.firstSyncDone()
            rig.goneSelection()

            val listed = listedBy { rig.runner.outcome(SyncPlan.Both) }

            assertEquals(1, listed)
            assertEquals(1, rig.followed.size)
            assertTrue(rig.followed.single() is ServerResult.Success)
        }
}
