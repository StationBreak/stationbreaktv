// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.FIRST_LINEUP_WAIT
import tv.stationbreak.core.domain.lineup.FakeFirstLineup
import tv.stationbreak.core.domain.lineup.FakeSchedule
import tv.stationbreak.core.domain.lineup.FirstLineupState
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The cheer before live TV (spec 2026-11-18, "Two cheers with no screen of their own"): when the first sync the screen
 * was showing ends, Stan cheers for [CHEER_HOLD_MS], then the screen finishes with [FirstSyncEvent.Cheered], so the app
 * keeps the cheer up over live TV until its picture is ready. A key skips the hold; it finishes once.
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
            assertEquals(listOf(FirstSyncEvent.Cheered), seen)
            assertEquals("the cheer stays up until the screen goes", FirstSyncPhase.Done, vm.state.value)
        }

    @Test
    fun skipCheerFinishesAtOnce() =
        runTest(dispatcher) {
            val (vm, seen) = finishing()

            vm.skipCheer()
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Cheered), seen)
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

            assertEquals(listOf(FirstSyncEvent.Cheered), seen)
        }

    @Test
    fun skippingTwiceFinishesOnce() =
        runTest(dispatcher) {
            val (vm, seen) = finishing()

            vm.skipCheer()
            vm.skipCheer()
            advanceTimeBy(CHEER_HOLD_MS * 2)
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Cheered), seen)
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

            assertEquals(listOf(FirstSyncEvent.Cheered), seen)
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

    // The first lineup (Stan keeps working until a channel is on the air).

    private class Building(
        val vm: FirstSyncViewModel,
        val firstSync: InMemoryFirstSync,
        val firstLineup: FakeFirstLineup,
        val schedule: FakeSchedule,
        val changes: LineupChanges,
        val seen: List<FirstSyncEvent>,
    ) {
        /** A channel comes on the air with the lineup's next change. */
        fun channelOnTheAir() {
            schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
            changes.changed()
        }
    }

    /** A screen showing a running first sync that then ends, with the first lineup still building. */
    private fun TestScope.building(schedule: FakeSchedule = FakeSchedule()): Building {
        val firstSync = InMemoryFirstSync(running)
        val firstLineup = FakeFirstLineup(FirstLineupState.Building)
        val changes = LineupChanges()
        val vm = firstSyncViewModel(firstSync, firstLineup, schedule, changes)
        val seen = events(vm)
        runCurrent()
        firstSync.current = finished
        runCurrent()
        return Building(vm, firstSync, firstLineup, schedule, changes, seen)
    }

    private fun TestScope.holdsTheCheerThenFinishes(b: Building) {
        assertEquals(FirstSyncPhase.Done, b.vm.state.value)
        assertEquals(emptyList<FirstSyncEvent>(), b.seen)
        advanceTimeBy(CHEER_HOLD_MS)
        runCurrent()
        assertEquals(listOf(FirstSyncEvent.Cheered), b.seen)
    }

    @Test
    fun withNothingOnTheAirStanKeepsWorking() =
        runTest(dispatcher) {
            val b = building()

            assertEquals(FirstSyncPhase.Building, b.vm.state.value)
            b.changes.changed()
            advanceTimeBy(FIRST_LINEUP_WAIT - 1.milliseconds)
            runCurrent()
            assertEquals(FirstSyncPhase.Building, b.vm.state.value)
            assertEquals(emptyList<FirstSyncEvent>(), b.seen)
        }

    @Test
    fun aChannelOnTheAirCheersThoughTheLineupIsStillBuilding() =
        runTest(dispatcher) {
            // A thin library whose collection pass is slow: its channel airs long before the lineup settles.
            val b = building()
            advanceTimeBy(30_000)

            b.channelOnTheAir()
            runCurrent()

            holdsTheCheerThenFinishes(b)
        }

    @Test
    fun aChannelAlreadyOnTheAirCheersAtOnce() =
        runTest(dispatcher) {
            val b = building(schedule = onTheAir())

            holdsTheCheerThenFinishes(b)
        }

    @Test
    fun aSettledLineupWithAChannelOnTheAirCheers() =
        runTest(dispatcher) {
            val b = building()
            b.schedule.channel(CH7, 7, listOf(program(0, 60, 1)))

            b.firstLineup.state.value = FirstLineupState.Settled
            runCurrent()

            holdsTheCheerThenFinishes(b)
        }

    @Test
    fun aSettledLineupWithNothingOnTheAirFinishesWithNoCheer() =
        runTest(dispatcher) {
            val b = building()

            b.firstLineup.state.value = FirstLineupState.Settled
            runCurrent()

            // Live TV says there are no channels: nothing to cheer.
            assertEquals(listOf(FirstSyncEvent.Finished), b.seen)
            b.channelOnTheAir()
            advanceTimeBy(FIRST_LINEUP_WAIT * 2)
            runCurrent()
            assertEquals("once", listOf(FirstSyncEvent.Finished), b.seen)
            assertEquals(FirstSyncPhase.Building, b.vm.state.value)
        }

    @Test
    fun aSettledLineupDecidesBeforeAnyChannelListArrives() =
        runTest(dispatcher) {
            // The channel list has said nothing yet (no active account in it, say): the lineup's state alone decides.
            val silent =
                object : ChannelRepository {
                    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = emptyFlow()

                    override fun activeChannels(): Flow<List<ChannelSummary>> = emptyFlow()
                }
            val firstSync = InMemoryFirstSync(running)
            val vm = firstSyncViewModel(firstSync, FakeFirstLineup(), FakeSchedule(), silent)
            val seen = events(vm)
            runCurrent()

            firstSync.current = finished
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.Finished), seen)
        }

    @Test
    fun theWaitWithNothingOnTheAirFinishesWithNoCheer() =
        runTest(dispatcher) {
            val b = building()

            advanceTimeBy(FIRST_LINEUP_WAIT)
            runCurrent()

            // Setup never hangs: live TV goes on waiting for the channels itself.
            assertEquals(listOf(FirstSyncEvent.Finished), b.seen)
            assertEquals(FirstSyncPhase.Building, b.vm.state.value)
        }

    @Test
    fun theWaitLooksOnceMoreAndCheersForAChannelOnTheAir() =
        runTest(dispatcher) {
            val b = building()
            // On the air with no change said: only the last look, at the end of the wait, sees it.
            b.schedule.channel(CH7, 7, listOf(program(0, 60, 1)))

            advanceTimeBy(FIRST_LINEUP_WAIT)
            runCurrent()

            holdsTheCheerThenFinishes(b)
        }

    @Test
    fun aSessionLostWhileBuildingLeavesOnceForSignIn() =
        runTest(dispatcher) {
            val b = building()

            b.firstSync.current = null
            runCurrent()
            b.channelOnTheAir()
            runCurrent()
            advanceTimeBy(FIRST_LINEUP_WAIT * 2)
            runCurrent()

            assertEquals(listOf(FirstSyncEvent.SessionLost), b.seen)
            assertEquals(FirstSyncPhase.Building, b.vm.state.value)
        }

    @Test
    fun aKeyWhileBuildingSkipsNothing() =
        runTest(dispatcher) {
            val b = building()

            b.vm.skipCheer()
            b.channelOnTheAir()
            runCurrent()
            advanceTimeBy(CHEER_HOLD_MS - 1)
            runCurrent()

            assertEquals("the cheer holds in full", emptyList<FirstSyncEvent>(), b.seen)
        }
}
