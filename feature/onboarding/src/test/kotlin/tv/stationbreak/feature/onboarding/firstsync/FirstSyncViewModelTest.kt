// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import tv.stationbreak.core.domain.InMemoryFirstSync
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncState
import kotlin.time.Instant

/**
 * The cheer before live TV (spec 2026-11-18, "Two cheers with no screen of their own"): when the first sync the screen
 * was showing ends, Stan cheers for [CHEER_HOLD_MS], then the screen finishes. A key skips the hold; it finishes once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstSyncViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val running = LibraryStatus(SyncState.FirstSyncRunning(40, 100), null)
    private val finished = LibraryStatus(SyncState.Idle, Instant.fromEpochMilliseconds(1_700_000_000_000))

    @Before
    fun setMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun TestScope.events(vm: FirstSyncViewModel): List<FirstSyncEvent> {
        val seen = mutableListOf<FirstSyncEvent>()
        backgroundScope.launch { vm.events.collect { seen += it } }
        return seen
    }

    /** A screen showing a running first sync, which then ends. */
    private fun TestScope.finishing(): Pair<FirstSyncViewModel, List<FirstSyncEvent>> {
        val firstSync = InMemoryFirstSync(running)
        val vm = FirstSyncViewModel(firstSync)
        val seen = events(vm)
        runCurrent()
        firstSync.current = finished
        runCurrent()
        return vm to seen
    }

    @Test
    fun finishingShowsDoneThenFinishesAfterTheHold() =
        runTest(dispatcher) {
            val (vm, seen) = finishing()
            assertEquals(FirstSyncPhase.Done, vm.state.value)

            advanceTimeBy(CHEER_HOLD_MS - 1)
            runCurrent()
            assertEquals(emptyList<FirstSyncEvent>(), seen)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(FirstSyncEvent.Finished), seen)
            assertEquals("the cheer stays up until the screen goes", FirstSyncPhase.Done, vm.state.value)
        }

    @Test
    fun skipCheerFinishesAtOnce() =
        runTest(dispatcher) {
            val (vm, seen) = finishing()

            vm.skipCheer()
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Finished), seen)
        }

    @Test
    fun finishIsSentOnce() =
        runTest(dispatcher) {
            val (vm, seen) = finishing()
            advanceTimeBy(CHEER_HOLD_MS)
            runCurrent()

            vm.skipCheer()
            runCurrent()
            advanceTimeBy(CHEER_HOLD_MS * 2)
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Finished), seen)
        }

    @Test
    fun skippingTwiceFinishesOnce() =
        runTest(dispatcher) {
            val (vm, seen) = finishing()

            vm.skipCheer()
            vm.skipCheer()
            advanceTimeBy(CHEER_HOLD_MS * 2)
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Finished), seen)
        }

    @Test
    fun aSkipBeforeTheCheerDoesNothing() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(running)
            val vm = FirstSyncViewModel(firstSync)
            val seen = events(vm)
            runCurrent()

            // A key while it syncs is not a skip: there is nothing to skip yet.
            vm.skipCheer()
            runCurrent()
            assertEquals(emptyList<FirstSyncEvent>(), seen)

            firstSync.current = finished
            runCurrent()
            advanceTimeBy(CHEER_HOLD_MS - 1)
            runCurrent()
            assertEquals(emptyList<FirstSyncEvent>(), seen)
        }

    @Test
    fun aStaleCountNeverReplacesTheCheer() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(running)
            val vm = FirstSyncViewModel(firstSync)
            events(vm)
            runCurrent()

            // A count lands inside the half-second throttle, then the sync ends before it is shown.
            firstSync.current = LibraryStatus(SyncState.FirstSyncRunning(90, 100), null)
            runCurrent()
            firstSync.current = finished
            runCurrent()
            advanceTimeBy(CHEER_HOLD_MS - 1)
            runCurrent()

            assertEquals(FirstSyncPhase.Done, vm.state.value)
        }

    @Test
    fun withNothingToWaitForThereIsNoCheer() =
        runTest(dispatcher) {
            // The account's first sync had ended before the screen opened: it never shows, so nothing to cheer.
            val vm = FirstSyncViewModel(InMemoryFirstSync(finished))
            val seen = events(vm)

            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Finished), seen)
            assertEquals(FirstSyncPhase.Starting, vm.state.value)
        }
}
