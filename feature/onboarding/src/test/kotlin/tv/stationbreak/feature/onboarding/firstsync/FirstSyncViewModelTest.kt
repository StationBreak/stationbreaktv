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
import tv.stationbreak.core.domain.lineup.FIRST_LINEUP_WAIT
import tv.stationbreak.core.domain.lineup.FakeFirstLineup
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.FirstLineupState
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

    private class Building(
        val vm: FirstSyncViewModel,
        val firstLineup: FakeFirstLineup,
        val schedule: FakeSchedule,
        val seen: List<FirstSyncEvent>,
    )

    /** A screen showing a running first sync that then ends, with the first lineup still building. */
    private fun TestScope.building(schedule: FakeSchedule = onTheAir()): Building {
        val firstSync = InMemoryFirstSync(running)
        val firstLineup = FakeFirstLineup(FirstLineupState.Building)
        val vm = firstSyncViewModel(firstSync, firstLineup, schedule)
        val seen = events(vm)
        runCurrent()
        firstSync.current = finished
        runCurrent()
        return Building(vm, firstLineup, schedule, seen)
    }

    @Test
    fun theEndOfTheSyncBuildsTheChannelsBeforeAnyCheer() =
        runTest(dispatcher) {
            val b = building()

            assertEquals(FirstSyncPhase.Building, b.vm.state.value)
            advanceTimeBy(FIRST_LINEUP_WAIT - 1.milliseconds)
            runCurrent()
            assertEquals(FirstSyncPhase.Building, b.vm.state.value)
            assertEquals(emptyList<FirstSyncEvent>(), b.seen)
        }

    @Test
    fun aSettledLineupWithAChannelOnTheAirCheersThenFinishes() =
        runTest(dispatcher) {
            val b = building()
            advanceTimeBy(30_000)

            b.firstLineup.state.value = FirstLineupState.Settled
            runCurrent()
            assertEquals(FirstSyncPhase.Done, b.vm.state.value)
            assertEquals(emptyList<FirstSyncEvent>(), b.seen)

            advanceTimeBy(CHEER_HOLD_MS)
            runCurrent()
            assertEquals(listOf(FirstSyncEvent.Finished), b.seen)
        }

    @Test
    fun aSettledLineupWithNothingOnTheAirFinishesWithNoCheer() =
        runTest(dispatcher) {
            val b = building(schedule = FakeSchedule())

            b.firstLineup.state.value = FirstLineupState.Settled
            runCurrent()

            // Live TV says there are no channels: nothing to cheer.
            assertEquals(listOf(FirstSyncEvent.Finished), b.seen)
            assertEquals(FirstSyncPhase.Building, b.vm.state.value)
            advanceTimeBy(FIRST_LINEUP_WAIT * 2)
            runCurrent()
            assertEquals("once", listOf(FirstSyncEvent.Finished), b.seen)
        }

    @Test
    fun aLineupThatNeverSettlesWithNothingOnTheAirFinishesAfterTheWait() =
        runTest(dispatcher) {
            val quiet = building(schedule = FakeSchedule())
            advanceTimeBy(FIRST_LINEUP_WAIT)
            runCurrent()
            // Setup never hangs: live TV goes on waiting for the channels itself.
            assertEquals(listOf(FirstSyncEvent.Finished), quiet.seen)
            assertEquals(FirstSyncPhase.Building, quiet.vm.state.value)
        }

    @Test
    fun aLineupThatNeverSettlesStillCheersForAChannelOnTheAir() =
        runTest(dispatcher) {
            val b = building()
            advanceTimeBy(FIRST_LINEUP_WAIT)
            runCurrent()

            assertEquals(FirstSyncPhase.Done, b.vm.state.value)
            advanceTimeBy(CHEER_HOLD_MS)
            runCurrent()
            assertEquals(listOf(FirstSyncEvent.Finished), b.seen)
        }

    @Test
    fun aSessionLostWhileBuildingLeavesOnceForSignIn() =
        runTest(dispatcher) {
            val firstSync = InMemoryFirstSync(running)
            val firstLineup = FakeFirstLineup(FirstLineupState.Building)
            val vm = firstSyncViewModel(firstSync, firstLineup)
            val seen = events(vm)
            runCurrent()
            firstSync.current = finished
            runCurrent()

            firstSync.current = null
            runCurrent()
            firstLineup.state.value = FirstLineupState.Settled
            runCurrent()
            advanceTimeBy(FIRST_LINEUP_WAIT * 2)
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.SessionLost), seen)
            assertEquals(FirstSyncPhase.Building, vm.state.value)
        }

    @Test
    fun aKeyWhileBuildingSkipsNothing() =
        runTest(dispatcher) {
            val b = building()

            b.vm.skipCheer()
            b.firstLineup.state.value = FirstLineupState.Settled
            runCurrent()
            advanceTimeBy(CHEER_HOLD_MS - 1)
            runCurrent()

            assertEquals("the cheer holds in full", emptyList<FirstSyncEvent>(), b.seen)
        }
}
