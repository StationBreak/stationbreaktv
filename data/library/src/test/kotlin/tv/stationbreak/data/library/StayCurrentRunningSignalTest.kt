// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours

/**
 * #434: [StayCurrentRunner.running] says when a run is writing. The lineup refresher holds the triggers a run's writes
 * cause while it is true and checks once when it turns false, so it must cover every write and nothing else.
 */
class StayCurrentRunningSignalTest {
    private val rig = StayCurrentRig()
    private val server = rig.server

    @After
    fun close() = rig.close()

    private val both = SyncPlan(incremental = true, reconcile = true)

    @Test
    fun theRunningSignalIsOnForTheWholeRunAndOffAfterIt() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            assertFalse(rig.runner.running.value)
            var duringRun = false
            server.respond = { call ->
                if (call.number == 1) duringRun = rig.runner.running.value
                null
            }

            rig.runner.run(both)

            assertTrue(duringRun)
            assertFalse(rig.runner.running.value)
        }

    @Test
    fun theRunningSignalIsOffAfterACancelledRun() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            lateinit var job: Job
            var duringRun = false
            server.respond = { call ->
                if (call.number == 1) {
                    duringRun = rig.runner.running.value
                    job.cancel()
                }
                null
            }

            job = launch { rig.runner.run(both) }
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(duringRun)
            assertFalse(rig.runner.running.value)
        }

    // A run that stops before its passes wrote nothing: it must not look like one that did.
    @Test
    fun aRunThatStopsBeforeItsPassesNeverTurnsTheRunningSignalOn() =
        runBlocking {
            val seen = mutableListOf<Boolean>()

            @Suppress("InjectDispatcher") // A test: Unconfined sees each value the run sets, as it sets it.
            val watching = launch(Dispatchers.Unconfined) { rig.runner.running.collect { seen += it } }

            // No first sync yet: the run is skipped once it has taken the gate.
            rig.runner.run(both)
            watching.cancel()

            assertEquals(listOf(false), seen)
        }
}
