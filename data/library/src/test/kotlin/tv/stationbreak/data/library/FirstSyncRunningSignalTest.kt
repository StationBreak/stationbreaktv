// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ServerResult

/**
 * #480: [FirstSyncRunner.running] says when a first sync is writing, the same signal as [StayCurrentRunner.running].
 * The lineup refresher holds the triggers a run's writes cause while it is true and checks once when it turns false.
 */
class FirstSyncRunningSignalTest {
    private val rig = StayCurrentRig()
    private val runner = FirstSyncRunner(rig.settings, rig.sessions, rig.server, rig.repository, rig.clock)

    @After
    fun close() = rig.close()

    @Test
    fun theRunningSignalIsOnForTheWholeRunAndOffAfterIt() =
        runBlocking {
            rig.sessions.save(SyncWorld.session)
            rig.fake.resumeResult = ServerResult.Success(SyncWorld.session)
            assertFalse(runner.running.value)
            var duringRun = false
            rig.server.respond = { call ->
                if (call.number == 1) duringRun = runner.running.value
                null
            }

            val verdict = runner.run(attempt = 0)

            assertEquals(SyncVerdict.Success, verdict)
            assertTrue(duringRun)
            assertFalse(runner.running.value)
        }

    // A run that stops before it writes anything must not look like one that did.
    @Test
    fun aRunThatStopsBeforeItWritesNeverTurnsTheRunningSignalOn() =
        runBlocking {
            val seen = mutableListOf<Boolean>()

            @Suppress("InjectDispatcher") // A test: Unconfined sees each value the run sets, as it sets it.
            val watching = launch(Dispatchers.Unconfined) { runner.running.collect { seen += it } }

            // Nobody is signed in: the session cannot be verified, so there is nothing to write.
            runner.run(attempt = 0)
            watching.cancel()

            assertEquals(listOf(false), seen)
        }
}
