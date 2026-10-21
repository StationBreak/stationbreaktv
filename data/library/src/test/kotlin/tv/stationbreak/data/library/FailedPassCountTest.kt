// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.CollectionCallKind
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedCollectionCall

/**
 * Issue #315: how many collection passes ended without completing since the last completed one. The first lineup
 * stops waiting for collections only when that is two or more, so a pass that is merely slow, or one interrupted
 * once, never finalises a lineup without them. A pass is recorded as started, and a start that finds the previous one
 * never finished counts it as killed.
 */
class FailedPassCountTest {
    private val rig = CollectionRig()
    private val repository get() = rig.repository

    @After
    fun close() = repository.close()

    private suspend fun failed() = repository.failedCollectionPasses(ALICE)

    // The listing of IDs timing out stops the pass (a members read that times out is skipped, not fatal).
    private suspend fun passThatFails() {
        rig.server.respondToCollections = { call: PagedCollectionCall ->
            if (call.kind == CollectionCallKind.Ids) MediaServerError.Timeout() else null
        }
        rig.run()
    }

    @Test
    fun nothingHasFailedBeforeAnyPass() =
        runBlocking {
            assertEquals(0, failed())
        }

    @Test
    fun aCompletedPassLeavesItAtZero() =
        runBlocking {
            rig.serve(1, *rig.movies(10))
            assertEquals(SyncOutcome.Completed, rig.run().outcome)
            assertEquals(0, failed())
        }

    @Test
    fun eachPassThatEndsWithoutCompletingAddsOne() =
        runBlocking {
            rig.serve(1, *rig.movies(10))
            passThatFails()
            assertEquals(1, failed())
            passThatFails()
            assertEquals(2, failed())
        }

    @Test
    fun aCompletedPassResetsTheFailureCount() =
        runBlocking {
            rig.serve(1, *rig.movies(10))
            passThatFails()
            passThatFails()
            assertEquals(2, failed())
            rig.server.respondToCollections = { null }

            assertEquals(SyncOutcome.Completed, rig.run().outcome)

            assertEquals(0, failed())
        }

    @Test
    fun aPassThatIsCancelledCounts() =
        runBlocking {
            rig.serve(1, *rig.movies(10))
            rig.server.respondToCollections = { throw CancellationException("the sync was cancelled") }

            runCatching { rig.run() }

            assertEquals(1, failed())
        }

    @Test
    fun aPassKilledWithTheProcessCountsWhenTheNextOneStarts() =
        runBlocking {
            rig.serve(1, *rig.movies(10))
            // Recorded as started and never finished: the process died in it.
            repository.beginCollectionPass(ALICE)
            assertEquals("a pass that is running now is not counted", 0, failed())

            repository.beginCollectionPass(ALICE)

            assertEquals(1, failed())
        }

    @Test
    fun oneKilledPassThenAFailedOneIsTwo() =
        runBlocking {
            rig.serve(1, *rig.movies(10))
            repository.beginCollectionPass(ALICE)
            passThatFails()
            assertEquals(2, failed())
        }
}
