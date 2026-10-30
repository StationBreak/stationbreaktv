// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ServerResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val WAIT_SECONDS = 10L

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

    @Test
    fun theRunningSignalIsOffAfterARunCancelledMidWrite() =
        runBlocking {
            rig.sessions.save(SyncWorld.session)
            rig.fake.resumeResult = ServerResult.Success(SyncWorld.session)
            lateinit var job: Job
            var duringRun = false
            rig.server.respond = { call ->
                if (call.number == 1) {
                    duringRun = runner.running.value
                    job.cancel()
                }
                null
            }

            job = launch { runner.run(attempt = 0) }
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(duringRun)
            assertFalse(runner.running.value)
        }

    // A retry replaces the work: the new run starts while the cancelled one has not unwound. The first to end must not
    // switch the signal off for the other.
    @Test
    fun theRunningSignalStaysOnUntilTheLastOfTwoOverlappingRunsEnds() =
        runBlocking {
            rig.sessions.save(SyncWorld.session)
            rig.fake.resumeResult = ServerResult.Success(SyncWorld.session)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = AtomicBoolean(true)
            rig.server.respond = {
                if (first.compareAndSet(true, false)) {
                    entered.countDown()
                    release.await()
                }
                null
            }

            @Suppress("InjectDispatcher") // A test: the first run blocks a thread of its own while the second goes on.
            val a = async(Dispatchers.Default) { runner.outcome() }

            @Suppress("InjectDispatcher") // A test, as above.
            val ready = withContext(Dispatchers.Default) { entered.await(WAIT_SECONDS, TimeUnit.SECONDS) }
            assertTrue(ready)
            assertTrue(runner.running.value)

            @Suppress("InjectDispatcher") // A test, as above.
            val b = async(Dispatchers.Default) { runner.outcome() }
            b.await()
            assertTrue("the first run is still unwinding", runner.running.value)

            release.countDown()
            a.await()
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
