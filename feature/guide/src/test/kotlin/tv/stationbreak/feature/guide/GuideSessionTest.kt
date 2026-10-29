// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.HOUR
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.model.ChannelId

/**
 * A guide session against the clock, scrolling and the edges of what is loaded: the moment shown never goes back,
 * the window grows as the viewer scrolls, the focus follows what is on now, and what OK does matches the hint.
 */
class GuideSessionTest : GuideRig() {
    // The grid and its "now" line, every time they are published.
    private fun TestScope.readies(vm: GuideViewModel): List<GuideState.Ready> {
        val shown = mutableListOf<GuideState.Ready>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.snapshot.collect { (it.state as? GuideState.Ready)?.let(shown::add) }
        }
        return shown
    }

    private fun assertNeverBack(shown: List<GuideState.Ready>) {
        assertEquals(shown.map { it.nowMs }.sorted(), shown.map { it.nowMs })
        assertEquals(shown.map { it.model.originMs }.sorted(), shown.map { it.model.originMs })
    }

    @Test
    fun aFillInBuiltBeforeATickNeverMovesTheGridBack() =
        guideTest(startMin = 88) { vm ->
            // Cached rows open the grid at once; the fill-in is held until a tick has crossed into the next half hour.
            fake.cachedUntilMs = min(180)
            val gate = CompletableDeferred<Unit>()
            schedule.asNeededGate = gate
            val shown = readies(vm)
            vm.open(CH7)
            runCurrent()
            after(3 * MIN)
            assertEquals(min(90), vm.ready().model.originMs)

            // The fill-in read the clock before that tick was published: it was built at 89 minutes.
            schedule.afterSchedule = { call -> if (call == 2) lagNextReadMs = 2 * MIN }
            gate.complete(Unit)
            runCurrent()

            assertTrue("the fill-in was shown", vm.ready().model.complete)
            assertNeverBack(shown)
            assertEquals(min(90), vm.ready().model.originMs)
        }

    @Test
    fun scrollingRightPastTheFirstSixHoursKeepsGoing() =
        guideTest { vm ->
            // Twelve hours are built; the guide opens on six of them and must reach the rest by scrolling alone.
            lineup(untilMin = 780)
            vm.open(CH7)
            runCurrent()
            assertTrue("opened on about six hours", vm.ready().model.endMs < min(660))

            repeat(20) {
                vm.move(GuideDirection.Right, repeat = false, atMs = 0)
                runCurrent()
            }

            // Half-hour cells from 60: twenty steps right is the cell at 660, past the six hours opened with.
            assertEquals(min(660), vm.focused().cellStartMs)
            assertTrue(vm.ready().model.endMs > min(660))
            assertTrue(fake.scheduleCalls.none { it.third == ScheduleBuild.AsNeeded })
            // Asked once per new end, not once per key.
            val extensions = fake.scheduleCalls.drop(1)
            assertEquals(extensions.map { it.second }.distinct(), extensions.map { it.second })
        }

    @Test
    fun scrollingRightKeepsGoingWhenAnotherRowRunsFarPastTheWindow() =
        guideTest { vm ->
            // Channel 9 is off air from 210 minutes to the end of its day: one slate, far past the six hours opened
            // with, as the scheduler lays out a channel that has run out of programs. The grid's end is that slate's,
            // but channel 7's row stops at the window, so scrolling it must still ask for more.
            lineup(untilMin = 780)
            fake.channel(CH9, 9, listOf(program(0, 150, 300), program(150, 210, 301), slate(210, 1440)))
            vm.open(CH7)
            runCurrent()
            assertEquals(min(1440), vm.ready().model.endMs)

            repeat(20) {
                vm.move(GuideDirection.Right, repeat = false, atMs = 0)
                runCurrent()
            }

            assertEquals(min(660), vm.focused().cellStartMs)
            assertTrue(fake.scheduleCalls.none { it.third == ScheduleBuild.AsNeeded })
            // What the measurement line reports, so a device run shows the extension happened.
            assertEquals(fake.scheduleCalls.size - 1, vm.extensionsAsked)
            assertTrue(vm.extensionsAsked >= 1)
            assertEquals(vm.extensionsAsked, vm.extensionsApplied)

            vm.close()
            assertEquals(0, vm.extensionsAsked)
            assertEquals(0, vm.extensionsApplied)
        }

    @Test
    fun scrollingAsksAgainOnceMoreHasBeenBuilt() =
        guideTest { vm ->
            // The grid's end is pinned by channel 9's slate. What is built stops at 7 hours, so the first ask from
            // channel 7 finds nothing new, and the next keys do not ask again for the same end. A minute on, more is
            // built: a further Right asks again and goes on.
            lineup(untilMin = 780)
            fake.channel(CH9, 9, listOf(program(0, 150, 300), program(150, 210, 301), slate(210, 1440)))
            fake.cachedUntilMs = min(420)
            vm.open(CH7)
            runCurrent()
            repeat(12) {
                vm.move(GuideDirection.Right, repeat = false, atMs = 0)
                runCurrent()
            }
            assertEquals(min(390), vm.focused().cellStartMs)
            val asked = fake.scheduleCalls.count { it.third == ScheduleBuild.CachedOnly }

            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            runCurrent()
            assertEquals(asked, fake.scheduleCalls.count { it.third == ScheduleBuild.CachedOnly })

            fake.cachedUntilMs = null
            after(MIN)
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            runCurrent()
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            runCurrent()

            assertTrue(fake.scheduleCalls.count { it.third == ScheduleBuild.CachedOnly } > asked)
            assertEquals(min(420), vm.focused().cellStartMs)
        }

    @Test
    fun theClockKeepsShortRowsGoingWhenAnotherRowRunsFarPastTheWindow() =
        guideTest { vm ->
            // As above, with nobody scrolling: the clock must ask for more before channel 7's row runs out.
            lineup(untilMin = 780)
            fake.channel(CH9, 9, listOf(program(0, 150, 300), program(150, 210, 301), slate(210, 1440)))
            vm.open(CH7)
            runCurrent()
            val ch7 = {
                vm
                    .ready()
                    .model.rows
                    .single { it.channel == CH7 }
            }
            assertEquals(min(420), ch7().cells.last().endMs)

            after(5 * HOUR)

            assertTrue(fake.scheduleCalls.drop(1).isNotEmpty())
            assertTrue(fake.scheduleCalls.none { it.third == ScheduleBuild.AsNeeded })
            assertEquals(min(780), ch7().cells.last().endMs)
        }

    @Test
    fun focusOnAProgramThatEndsMovesToWhatIsOnNowInItsRow() =
        guideTest { vm ->
            // Channel 7's program on now ends at 70 minutes, inside the half hour, so it stays on the grid.
            fake.channel(CH7, 7, listOf(program(0, 70, 1), program(70, 100, 2)) + halfHours(600, fromMin = 100))
            vm.open(CH7)
            runCurrent()
            assertEquals(min(0), vm.focused().cellStartMs)
            vm.move(GuideDirection.Down, repeat = false, atMs = 0)
            vm.move(GuideDirection.Up, repeat = false, atMs = 0)
            assertEquals(min(0), vm.focused().cellStartMs)

            after(5 * MIN)

            assertEquals(min(60), vm.ready().model.originMs)
            assertEquals(GuideFocus(CH7, min(70), min(70)), vm.snapshot.value.focus)
        }

    @Test
    fun focusOnALaterProgramStaysWhenTheOneBeforeItEnds() =
        guideTest { vm ->
            fake.channel(CH7, 7, listOf(program(0, 70, 1), program(70, 100, 2)) + halfHours(600, fromMin = 100))
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            assertEquals(min(100), vm.focused().cellStartMs)

            after(5 * MIN)

            assertEquals(min(100), vm.focused().cellStartMs)
        }

    @Test
    fun aSlowFirstReadShowsLoadingAfterFourHundredMilliseconds() =
        guideTest { vm ->
            val gate = CompletableDeferred<Unit>()
            schedule.cachedGate = gate
            vm.open(CH7)
            runCurrent()
            // A quick read never flashes a loading line: nothing yet.
            after(399)
            assertEquals(GuideState.Closed, vm.snapshot.value.state)

            after(1)
            assertEquals(GuideState.Loading, vm.snapshot.value.state)

            gate.complete(Unit)
            runCurrent()
            assertTrue(vm.snapshot.value.state is GuideState.Ready)
        }

    @Test
    fun aQuickFirstReadNeverShowsLoading() =
        guideTest { vm ->
            val states = mutableListOf<GuideState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.states().toList(states) }
            val gate = CompletableDeferred<Unit>()
            schedule.cachedGate = gate
            vm.open(CH7)
            runCurrent()
            after(300)
            gate.complete(Unit)
            runCurrent()
            after(1000)
            assertEquals(listOf("Closed", "Ready"), states.map { it::class.simpleName })
        }

    // Ten rows: 5, 7 and 9, then 11 to 17.
    private fun tenRows(): List<ChannelId> {
        val more = (11..17).map { ChannelId("ch-$it") to it }
        more.forEach { (id, number) -> fake.channel(id, number, halfHours(600)) }
        return more.map { it.first }
    }

    @Test
    fun theGuideOpensWithThePlayingRowAtTheTop() =
        guideTest { vm ->
            val more = tenRows()
            vm.open(more[1]) // channel 12, the fifth row
            runCurrent()
            assertEquals(4, vm.ready().firstVisibleRow)

            // A later move keeps the rows where they are while the focus stays in view.
            vm.move(GuideDirection.Down, repeat = false, atMs = 0)
            assertEquals(4, vm.ready().firstVisibleRow)
        }

    @Test
    fun theGuideOpensOnAFullLastPage() =
        guideTest { vm ->
            val more = tenRows()
            vm.open(more[5]) // channel 16, the ninth row of ten: the last six rows show
            runCurrent()
            assertEquals(4, vm.ready().firstVisibleRow)
        }

    @Test
    fun selectOnASlateOnNowTunesLikeAnyCellOnNow() =
        guideTest(startMin = 160) { vm ->
            val events = mutableListOf<GuideEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            vm.open(CH9)
            runCurrent()
            assertEquals(min(150), vm.focused().cellStartMs)

            vm.select()

            assertEquals(listOf<GuideEvent>(GuideEvent.Tune(CH9)), events)
            assertNull(vm.info.value)
        }

    @Test
    fun selectOnALaterSlateDoesNothing() =
        guideTest { vm ->
            val events = mutableListOf<GuideEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            vm.open(CH9)
            runCurrent()
            vm.move(GuideDirection.Right, repeat = false, atMs = 0)
            assertEquals(min(150), vm.focused().cellStartMs)

            vm.select()
            runCurrent()

            assertNull(vm.info.value)
            assertTrue(events.isEmpty())
        }
}
