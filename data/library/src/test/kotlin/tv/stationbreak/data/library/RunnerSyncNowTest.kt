// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.domain.selection.ChangeResult
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.domain.selection.LibraryChoices
import tv.stationbreak.core.domain.selection.LibraryListing
import tv.stationbreak.core.model.ItemId
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Spec 26, Task 5.1: Sync now lists the server's libraries once per press (the listing the two-read rule counts), then
 * runs both stay-current passes, waiting behind a run already under way and never running beside one, without the
 * runner's own listing. A failure changes nothing it has not already, and says why. Invented libraries only.
 */
class RunnerSyncNowTest {
    private val lines = Collections.synchronizedList(mutableListOf<String>())
    private val rig = StayCurrentRig { lines += it }

    @Suppress("InjectDispatcher") // The presses' own scope, as the app's root scope is.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val choices = CountingChoices()
    private val syncNow = RunnerSyncNow(choices, rig.runner, scope, rig.clock)

    @After
    fun close() {
        scope.cancel()
        rig.close()
    }

    private val both = SyncPlan(incremental = true, reconcile = true)

    /** The selection's listing as the real one answers it, counted, and waiting on [gate] when one is set. */
    private class CountingChoices : LibraryChoices {
        override val selection = MutableStateFlow<ChoiceView?>(null)
        val listings = AtomicInteger()

        @Volatile var gate: CompletableDeferred<Unit>? = null

        @Volatile var answer: LibraryListing = LibraryListing.Listed(listOf(SyncWorld.movies), null)

        /** When set, the listing's follow ends only once it completes, as when a change holds the selection's lock. */
        @Volatile var followGate: CompletableDeferred<Unit>? = null

        override suspend fun followed() {
            followGate?.await()
        }

        override suspend fun listing(): LibraryListing {
            listings.incrementAndGet()
            gate?.await()
            return answer
        }

        override suspend fun change(ticked: Set<ItemId>): ChangeResult = ChangeResult.Failed
    }

    private suspend fun ended(): SyncNowState {
        val running = SyncNowState.Running
        return withTimeout(TIMEOUT) { syncNow.state.first { it != running } }
    }

    @Test
    fun pressWhileRunning_startsNothing() =
        runBlocking {
            rig.firstSyncDone()
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate

            assertTrue(syncNow.start())
            assertEquals(SyncNowState.Running, syncNow.state.value)
            assertFalse("a second press while one runs", syncNow.start())
            gate.complete(Unit)

            assertTrue(ended() is SyncNowState.Done)
            assertEquals("one press, one listing", 1, choices.listings.get())
            assertEquals("one run", 1, lines.count { it.startsWith("StayCurrent incremental=") })
        }

    // Fix round 1: the selection now follows a listing after answering it. A press runs only once its listing has
    // been followed, so the run reads the choice that listing left (as when the listing was followed in place).
    @Test
    fun aPress_runsOnlyOnceItsListingIsFollowed() =
        runBlocking {
            rig.firstSyncDone()
            val follow = CompletableDeferred<Unit>()
            choices.followGate = follow

            assertTrue(syncNow.start())
            withTimeout(TIMEOUT) { while (choices.listings.get() == 0) delay(10) }
            delay(200)
            assertTrue("no run before the follow", lines.none { it.startsWith("StayCurrent incremental=") })
            follow.complete(Unit)

            assertTrue(ended() is SyncNowState.Done)
            assertEquals(1, lines.count { it.startsWith("StayCurrent incremental=") })
        }

    @Test
    fun waitsForBackgroundRun_neverTwoAtOnce() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            val first = AtomicBoolean(true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            rig.server.respond = {
                if (first.compareAndSet(true, false)) {
                    entered.countDown()
                    release.await(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)
                }
                null
            }
            val background = scope.launch { rig.runner.run(both) }
            assertTrue(entered.await(TIMEOUT.inWholeSeconds, TimeUnit.SECONDS))

            assertTrue(syncNow.start())
            withTimeout(TIMEOUT) { while (choices.listings.get() == 0) delay(10) }
            delay(200)
            assertEquals("waiting behind the background run", SyncNowState.Running, syncNow.state.value)
            release.countDown()
            background.join()

            assertTrue(ended() is SyncNowState.Done)
            val runs = lines.filter { it.startsWith("StayCurrent incremental=") || it.startsWith("StayCurrentDone") }
            assertEquals(4, runs.size)
            runs.chunked(2).forEach { (began, done) ->
                assertTrue(began.startsWith("StayCurrent incremental="))
                assertTrue(done.startsWith("StayCurrentDone"))
            }
            assertTrue("nothing skipped as busy", lines.none { "reason=Busy" in it })
        }

    @Test
    fun listsOnce_perPress() =
        runBlocking {
            rig.firstSyncDone()

            assertTrue(syncNow.start())
            assertTrue(ended() is SyncNowState.Done)
            assertEquals(1, choices.listings.get())
            // The runner did not list the server again: holds expire and the rewalk runs, on the press's listing.
            assertEquals(listOf("expire"), rig.asked)
            assertTrue(rig.followed.isEmpty())
            assertNotNull("the rewalk ran", rig.runner.lastRewalk)

            assertTrue(syncNow.start())
            assertTrue(ended() is SyncNowState.Done)
            assertEquals(2, choices.listings.get())
            assertTrue(rig.followed.isEmpty())
        }

    @Test
    fun networkFailure_failedNetwork_cacheAndScheduleUnchanged() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            val generation = rig.repository.contentGeneration(ALICE)
            val rows = rig.repository.stored(ALICE)
            val status = rig.repository.activeStatus().first()
            val selection = rig.settings.loadSelection()

            // The listing fails: nothing runs, and nothing is written, not even the sync state.
            choices.answer = LibraryListing.Unavailable(MediaServerError.NetworkUnreachable(), null)
            assertTrue(syncNow.start())
            assertEquals(SyncNowState.Failed(SyncFailure.Network, changedNothing = true), ended())
            assertTrue("no pass ran", rig.server.calls.isEmpty())
            assertEquals(status, rig.repository.activeStatus().first())

            // The listing answers and the passes fail: the content is as it was.
            choices.answer = LibraryListing.Listed(listOf(SyncWorld.movies), null)
            rig.server.respond = { MediaServerError.Timeout() }
            assertTrue(syncNow.start())
            // A failed run never says nothing changed, though here (its first call failed) nothing did.
            assertEquals(SyncNowState.Failed(SyncFailure.Network, changedNothing = false), ended())

            assertEquals(generation, rig.repository.contentGeneration(ALICE))
            assertEquals(rows, rig.repository.stored(ALICE))
            assertEquals(selection, rig.settings.loadSelection())
        }

    @Test
    fun sessionExpired_failedSessionExpired() =
        runBlocking {
            rig.firstSyncDone()
            rig.fake.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())

            assertTrue(syncNow.start())
            assertEquals(SyncNowState.Failed(SyncFailure.SessionExpired, changedNothing = false), ended())

            // The listing itself rejected: the same, with no run, and so nothing changed.
            choices.answer = LibraryListing.Unavailable(MediaServerError.SessionExpired(), null)
            rig.server.calls.clear()
            assertTrue(syncNow.start())
            assertEquals(SyncNowState.Failed(SyncFailure.SessionExpired, changedNothing = true), ended())
            assertTrue(rig.server.calls.isEmpty())
        }

    @Test
    fun completed_done() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours

            assertTrue(syncNow.start())

            assertEquals(SyncNowState.Done(rig.clock.now().toEpochMilliseconds()), ended())
        }

    // A run with nothing to do (here the first sync has not ended) is no failure: the row goes back to idle.
    @Test
    fun cancelled_idle() =
        runBlocking {
            assertTrue(syncNow.start())

            assertEquals(SyncNowState.Idle, ended())
        }

    // Follow-up to #654: a listing made for another account than the run's is not walked on, and the press is not
    // failed for it. (The listing the real selection gives carries its account.)
    @Test
    fun aListingForAnotherAccount_isNotRewalked_andThePressIsDone() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            choices.answer = LibraryListing.Listed(listOf(SyncWorld.movies), null, BOB)

            assertTrue(syncNow.start())

            assertTrue(ended() is SyncNowState.Done)
            assertEquals(null, rig.runner.lastRewalk)
            assertTrue(lines.any { "ListingAccountMismatch" in it })
        }

    @Test
    fun aListingForTheRunsAccount_isRewalked() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            choices.answer = LibraryListing.Listed(listOf(SyncWorld.movies), null, ALICE)

            assertTrue(syncNow.start())

            assertTrue(ended() is SyncNowState.Done)
            assertNotNull(rig.runner.lastRewalk)
        }

    // An empty listing is the answer the selection does not trust: nothing runs.
    @Test
    fun emptyListing_failedSuspect_nothingRuns() =
        runBlocking {
            rig.firstSyncDone()
            choices.answer = LibraryListing.Listed(emptyList(), null)

            assertTrue(syncNow.start())

            assertEquals(SyncNowState.Failed(SyncFailure.Suspect, changedNothing = true), ended())
            assertTrue(rig.server.calls.isEmpty())
        }

    // Fix round 1 (review MED): a run whose incremental pass wrote, and whose reconcile then failed, changed something;
    // it must not say nothing changed.
    @Test
    fun incrementalWrote_thenReconcileFailed_failedChangedSomething() =
        runBlocking {
            rig.firstSyncDone()
            rig.clock.instant += 7.hours
            rig.library.items += SyncWorld.item("m", 900).copy(dateCreated = rig.clock.now())
            val generation = rig.repository.contentGeneration(ALICE)
            rig.server.respond = { call -> if (call.idsOnly) MediaServerError.Timeout() else null }

            assertTrue(syncNow.start())

            assertEquals(SyncNowState.Failed(SyncFailure.Network, changedNothing = false), ended())
            assertTrue("the incremental pass wrote", rig.repository.contentGeneration(ALICE) > generation)
        }

    private companion object {
        val TIMEOUT = 10.seconds
    }

    // Follow-up to #654: a press whose run never finishes (here the follow of its listing never ends) is ended by its
    // timeout as a server failure, in virtual time, and a press after it is accepted.
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun aPressThatNeverFinishes_endsAsAServerFailureAfterTheTimeout() =
        runTest {
            val hung = CountingChoices().also { it.followGate = CompletableDeferred() }
            val limit = 5.minutes
            val press = RunnerSyncNow(hung, rig.runner, backgroundScope, rig.clock, limit)

            assertTrue(press.start())
            advanceTimeBy(limit - 1.seconds)
            runCurrent()
            assertEquals("still waiting just before the limit", SyncNowState.Running, press.state.value)

            advanceTimeBy(2.seconds)
            runCurrent()

            assertEquals(SyncNowState.Failed(SyncFailure.Server, changedNothing = false), press.state.value)
            assertTrue("a new press is accepted", press.start())
        }
}
