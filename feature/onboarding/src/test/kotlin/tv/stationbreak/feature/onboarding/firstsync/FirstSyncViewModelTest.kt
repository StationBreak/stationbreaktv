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
import tv.stationbreak.core.domain.lineup.FakeLineupReadiness
import tv.stationbreak.core.domain.lineup.LineupState
import kotlin.time.Duration.Companion.milliseconds
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
        val vm = firstSyncViewModel(firstSync)
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
            val vm = firstSyncViewModel(firstSync)
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
            val vm = firstSyncViewModel(firstSync)
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
    fun anAccountLostDuringTheCheerStillLeavesOnlyOnce() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(running)
            val vm = firstSyncViewModel(firstSync)
            val seen = events(vm)
            runCurrent()
            firstSync.current = finished
            runCurrent()

            // Signed out mid-cheer: the screen is already on its way out, so it leaves once, to live TV.
            firstSync.current = null
            runCurrent()
            advanceTimeBy(CHEER_HOLD_MS * 2)
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Finished), seen)
        }

    @Test
    fun withNothingToWaitForThereIsNoCheer() =
        runTest(dispatcher) {
            // The account's first sync had ended before the screen opened: it never shows, so nothing to cheer.
            val vm = firstSyncViewModel(InMemoryFirstSync(finished))
            val seen = events(vm)

            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Finished), seen)
            assertEquals(FirstSyncPhase.Starting, vm.state.value)
        }

    // The first lineup (Stan keeps working until it is built).

    /** A screen showing a running first sync that then ends, with the lineup still [LineupState.Building]. */
    private fun TestScope.building(): Triple<FirstSyncViewModel, FakeLineupReadiness, List<FirstSyncEvent>> {
        val firstSync = InMemoryFirstSync(running)
        val readiness = FakeLineupReadiness(LineupState.Building)
        val vm = firstSyncViewModel(firstSync, readiness)
        val seen = events(vm)
        runCurrent()
        firstSync.current = finished
        runCurrent()
        return Triple(vm, readiness, seen)
    }

    @Test
    fun theEndOfTheSyncBuildsTheChannelsBeforeAnyCheer() =
        runTest(dispatcher) {
            val (vm, readiness, seen) = building()

            assertEquals(FirstSyncPhase.Building, vm.state.value)
            assertEquals("the signed-in account's lineup", listOf(SIGNED_IN), readiness.asked.distinct())
            advanceTimeBy(LINEUP_WAIT - 1.milliseconds)
            runCurrent()
            assertEquals(FirstSyncPhase.Building, vm.state.value)
            assertEquals(emptyList<FirstSyncEvent>(), seen)
        }

    @Test
    fun aReadyLineupCheersThenFinishes() =
        runTest(dispatcher) {
            val (vm, readiness, seen) = building()
            advanceTimeBy(30_000)

            readiness.state.value = LineupState.Ready
            runCurrent()
            assertEquals(FirstSyncPhase.Done, vm.state.value)
            assertEquals(emptyList<FirstSyncEvent>(), seen)

            advanceTimeBy(CHEER_HOLD_MS)
            runCurrent()
            assertEquals(listOf(FirstSyncEvent.Finished), seen)
        }

    @Test
    fun anEmptyLineupFinishesWithNoCheer() =
        runTest(dispatcher) {
            val (vm, readiness, seen) = building()

            readiness.state.value = LineupState.Empty
            runCurrent()

            // Live TV says there are no channels: nothing to cheer.
            assertEquals(listOf(FirstSyncEvent.Finished), seen)
            assertEquals(FirstSyncPhase.Building, vm.state.value)
            advanceTimeBy(LINEUP_WAIT * 2)
            runCurrent()
            assertEquals("once", listOf(FirstSyncEvent.Finished), seen)
        }

    @Test
    fun aLineupThatTakesTooLongFinishesWithNoCheer() =
        runTest(dispatcher) {
            val (vm, readiness, seen) = building()

            advanceTimeBy(LINEUP_WAIT)
            runCurrent()

            // Setup never hangs: live TV goes on waiting for the channels itself.
            assertEquals(listOf(FirstSyncEvent.Finished), seen)
            assertEquals(FirstSyncPhase.Building, vm.state.value)
            readiness.state.value = LineupState.Ready
            runCurrent()
            advanceTimeBy(CHEER_HOLD_MS * 2)
            runCurrent()
            assertEquals("a lineup ready too late does not cheer", FirstSyncPhase.Building, vm.state.value)
            assertEquals("once", listOf(FirstSyncEvent.Finished), seen)
        }

    @Test
    fun aSessionLostWhileBuildingLeavesOnceForSignIn() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(running)
            val readiness = FakeLineupReadiness(LineupState.Building)
            val vm = firstSyncViewModel(firstSync, readiness)
            val seen = events(vm)
            runCurrent()
            firstSync.current = finished
            runCurrent()

            firstSync.current = null
            runCurrent()
            readiness.state.value = LineupState.Ready
            runCurrent()
            advanceTimeBy(LINEUP_WAIT * 2)
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.SessionLost), seen)
            assertEquals(FirstSyncPhase.Building, vm.state.value)
        }

    @Test
    fun aKeyWhileBuildingSkipsNothing() =
        runTest(dispatcher) {
            val (vm, readiness, seen) = building()

            vm.skipCheer()
            readiness.state.value = LineupState.Ready
            runCurrent()
            advanceTimeBy(CHEER_HOLD_MS - 1)
            runCurrent()

            assertEquals("the cheer holds in full", emptyList<FirstSyncEvent>(), seen)
        }
}
