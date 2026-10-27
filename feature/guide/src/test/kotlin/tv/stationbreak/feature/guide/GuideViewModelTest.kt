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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.ScheduleFixtures
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.HOUR
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.feature.guide.ui.minutesUntil

class GuideViewModelTest : GuideRig() {
    @Test
    fun aCallThatEndsAfterItsSessionDoesNotSetTheNextSessionsWindowTime() =
        guideTest { vm ->
            var seenBySecondCall = Long.MIN_VALUE
            schedule.afterSchedule = { call -> if (call == 1) vm.open(null) }
            schedule.onSchedule = { call -> if (call == 2) seenBySecondCall = vm.windowMs }
            vm.open(null)
            runCurrent()
            assertEquals(-1L, seenBySecondCall)
        }

    @Test
    fun opensWithCachedDaysThenFillsIn() =
        guideTest { vm ->
            fake.cachedUntilMs = min(180)
            val states = mutableListOf<GuideState>()
            val focuses = mutableListOf<GuideFocus?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.states().toList(states) }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.focuses().toList(focuses) }

            vm.open(CH7)
            runCurrent()

            val ready = states.filterIsInstance<GuideState.Ready>()
            assertEquals(listOf(false, true), ready.map { it.model.complete })
            assertTrue(ready[1].model.endMs > ready[0].model.endMs)
            assertEquals(
                listOf(ScheduleBuild.CachedOnly, ScheduleBuild.AsNeeded),
                fake.scheduleCalls.map { it.third }.take(2),
            )
            // Focus held by identity: it was published once and did not move or blink across the fill-in.
            assertEquals(listOf(null, GuideFocus(CH7, min(60), min(60))), focuses)
            assertEquals(min(60), ready[0].model.originMs)
        }

    @Test
    fun opensLoadingWhenNothingIsBuilt() =
        guideTest { vm ->
            fake.nothingCached = true
            val states = mutableListOf<GuideState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.states().toList(states) }

            vm.open(CH7)
            runCurrent()

            assertEquals(listOf("Closed", "Loading", "Ready"), states.map { it::class.simpleName })
            assertEquals(CH7, vm.focused().channel)
        }

    @Test
    fun signedOutIsEmpty() =
        guideTest { vm ->
            fake.signedOut = true
            vm.open(CH7)
            runCurrent()
            assertEquals(GuideState.Empty, vm.snapshot.value.state)
            assertNull(vm.snapshot.value.focus)
        }

    @Test
    fun fillInThatEndsIncompleteIsFinal() =
        guideTest { vm ->
            // Channel 7 stops airing at 3 h: no amount of building completes the window.
            lineup(stopCh7AtMin = 180)
            fake.nothingCached = true
            vm.open(CH7)
            runCurrent()
            after(2 * HOUR)
            assertFalse(vm.ready().model.complete)
            assertEquals(1, fake.scheduleCalls.count { it.third == ScheduleBuild.AsNeeded })
        }

    @Test
    fun extensionNeverBuilds() =
        guideTest { vm ->
            lineup(untilMin = 1000)
            vm.open(CH7)
            runCurrent()
            val endAtOpen = vm.ready().model.endMs

            after(5 * HOUR)

            val extensions = fake.scheduleCalls.drop(1)
            assertTrue("the guide asked for more as the end came within 2 hours", extensions.isNotEmpty())
            assertTrue(extensions.all { it.third == ScheduleBuild.CachedOnly })
            assertTrue(vm.ready().model.endMs > endAtOpen)
            assertTrue(fake.scheduleCalls.none { it.third == ScheduleBuild.AsNeeded })
        }

    @Test
    fun detailsWaitForHalfSecondOfFocus() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right)
            after(200)
            vm.move(GuideDirection.Right)
            after(200)
            vm.move(GuideDirection.Right)
            // Moves at 0, 200 and 400 ms: nothing is asked until 900.
            after(499)
            assertEquals(0, server.itemDetailsCalls.size)
            assertTrue(vm.panel.value?.loading == true)

            after(1)

            assertEquals(listOf(ScheduleFixtures.itemOf(105)), server.itemDetailsCalls.toList())
            val panel = checkNotNull(vm.panel.value)
            assertFalse(panel.loading)
            val expected = ItemDetails(ScheduleFixtures.itemOf(105), "About it.", 2001, listOf("Drama"), null)
            assertEquals(expected, panel.item)
            assertEquals(7, panel.channelNumber)
            assertEquals("Test Channel 7", panel.channelName)
            assertEquals(min(150), panel.cell.startMs)
        }

    @Test
    fun cachedDetailsShowAtOnce() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            after(500)
            vm.move(GuideDirection.Right)
            after(500)
            vm.move(GuideDirection.Left)
            // Back on the first cell, whose details the cache holds: no wait, no second request.
            val panel = checkNotNull(vm.panel.value)
            assertFalse(panel.loading)
            assertNotNull(panel.item)
            after(1000)
            assertEquals(2, server.itemDetailsCalls.size)
        }

    @Test
    fun detailsFailureKeepsPanelQuietAndRetriesLater() =
        guideTest { vm ->
            server.itemDetailsResult = ServerResult.Failure(MediaServerError.Timeout())
            vm.open(CH7)
            runCurrent()
            after(500)

            val failed = checkNotNull(vm.panel.value)
            assertNull(failed.item)
            assertFalse(failed.loading)
            assertEquals(1, server.itemDetailsCalls.size)
            after(5000)
            assertEquals(1, server.itemDetailsCalls.size)

            // Focus leaves and comes back: that asks again, and this time the server answers.
            vm.move(GuideDirection.Right)
            after(10)
            vm.move(GuideDirection.Left)
            server.itemDetailsResult = null
            after(500)
            assertEquals(2, server.itemDetailsCalls.count { it == ScheduleFixtures.itemOf(102) })
            assertNotNull(vm.panel.value?.item)
        }

    @Test
    fun slateCellHasQuietPanel() =
        guideTest { vm ->
            vm.open(CH9)
            runCurrent()
            vm.move(GuideDirection.Right)
            after(1000)
            val panel = checkNotNull(vm.panel.value)
            assertEquals(CellKind.Slate, panel.cell.kind)
            assertNull(panel.item)
            assertFalse(panel.loading)
            assertEquals(0, server.itemDetailsCalls.size)
        }

    @Test
    fun tickMovesNowAndDropsEndedCells() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            assertEquals(min(65), vm.ready().nowMs)
            assertEquals(min(60), vm.ready().viewportStartMs)

            after(30 * MIN)

            val ready = vm.ready()
            assertEquals(min(95), ready.nowMs)
            assertEquals(min(90), ready.model.originMs)
            assertEquals(min(90), ready.viewportStartMs)
            assertTrue(
                ready.model.rows
                    .flatMap { it.cells }
                    .all { it.endMs > min(90) },
            )
            // The focused cell (60 to 90) ended: focus is on the cell now holding the anchor, in the same row.
            assertEquals(GuideFocus(CH7, min(90), min(90)), vm.snapshot.value.focus)
        }

    @Test
    fun tickMovesNowEveryMinute() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            after(MIN)
            assertEquals(min(66), vm.ready().nowMs)
            after(MIN)
            assertEquals(min(67), vm.ready().nowMs)
        }

    @Test
    fun aTickThatWakesJustBeforeTheMinuteStillShowsTheMinute() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            // The tick due at 66 minutes reads a clock 6 ms short of it, as a wake-up a hair early does on a device
            // whose wall clock runs a little behind the timer; the next read is on time again.
            after(MIN - 1)
            lagNextReadMs = 6
            after(1)

            // Half a minute on, the panel's "min left" for the cell on now (60 to 90) is 24 (23.5, rounded up): the
            // shown time is the minute, not the moment before it, which would say 25 for a whole minute.
            after(MIN / 2)
            assertTrue("shown ${vm.ready().nowMs - min(66)} ms from 66 min", vm.ready().nowMs >= min(66))
            assertEquals(24, minutesUntil(min(90), vm.ready().nowMs))
        }

    @Test
    fun aTickThatShowedItsMinuteDoesNotTickThatMinuteAgain() =
        guideTest { vm ->
            val shown = mutableListOf<Long>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.snapshot.collect { (it.state as? GuideState.Ready)?.nowMs?.let(shown::add) }
            }
            vm.open(CH7)
            runCurrent()
            // The tick due at 66 minutes, and the reading that works out the next one, are both 6 ms short of it.
            after(MIN - 1)
            lagNextReadMs = 6
            lagReads = 2
            after(1)
            after(MIN / 2)

            // One moment shown for the 66th minute: the tick that showed it is not followed by another 6 ms later.
            assertEquals(listOf(min(66)), shown.filter { it >= min(66) && it < min(67) })
        }

    @Test
    fun selectActsOnTheMomentTheGridShows() =
        guideTest { vm ->
            val events = mutableListOf<GuideEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            vm.open(CH7)
            runCurrent()
            // The grid shows 65 minutes: the cell from 60 to 90 is on now and the hint says "OK Watch". A clock that
            // reads past its end before the tick has shown that must not turn the same OK into "Info".
            lagNextReadMs = -30 * MIN

            vm.select()
            runCurrent()

            assertEquals(listOf<GuideEvent>(GuideEvent.Tune(CH7)), events)
            assertNull(vm.info.value)
        }

    @Test
    fun aVerticalMoveMinutesAfterOpeningLandsOnWhatIsOnNow() =
        guideTest { vm ->
            // Channel 9's program from 0 to 75 stays on the grid (from 60) after it ends; at 80 the one on now
            // began at 75.
            fake.channel(CH9, 9, listOf(program(0, 75, 300), program(75, 600, 301)))
            vm.open(CH7)
            runCurrent()
            assertEquals(GuideFocus(CH7, min(60), min(60)), vm.focused())

            after(15 * MIN)
            vm.move(GuideDirection.Down)

            assertEquals(CH9, vm.focused().channel)
            assertEquals(min(75), vm.focused().cellStartMs)
            assertTrue(vm.focused().anchorMs >= vm.ready().nowMs)
        }

    @Test
    fun tickKeepsViewportWhenScrolledRight() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            repeat(4) { vm.move(GuideDirection.Right) }
            val scrolled = vm.ready().viewportStartMs
            assertTrue(scrolled > vm.ready().model.originMs)

            after(30 * MIN)

            assertEquals(min(90), vm.ready().model.originMs)
            assertEquals(scrolled, vm.ready().viewportStartMs)
            assertEquals(min(180), vm.focused().cellStartMs)
        }

    @Test
    fun focusedCellDroppedByTheNextWindowMovesWithinTheRow() =
        guideTest { vm ->
            // The window ends at 2 h 30 min, within 2 h of now, so the first tick asks for more.
            lineup(untilMin = 120)
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right)
            assertEquals(min(90), vm.focused().cellStartMs)
            schedule.onSchedule = { fake.channels.getValue(CH7).blocks = splitAround90(120) }

            after(MIN)

            // No cell starts at 90 now; the one holding the anchor (90) is the one that began at 0.
            assertEquals(GuideFocus(CH7, min(0), min(90)), vm.snapshot.value.focus)
            assertTrue(vm.ready().model.indexOf(vm.focused()) != null)
        }

    @Test
    fun focusedRowThatLosesAllCellsFallsBackToInitialFocus() =
        guideTest { vm ->
            lineup(untilMin = 120)
            vm.open(CH7)
            runCurrent()
            schedule.onSchedule = { fake.channels.getValue(CH7).blocks = emptyList() }

            after(MIN)

            assertEquals(CH5, vm.focused().channel)
        }

    @Test
    fun selectNowCellEmitsTune() =
        guideTest { vm ->
            val events = mutableListOf<GuideEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            vm.open(CH7)
            runCurrent()

            vm.select()

            assertEquals(listOf<GuideEvent>(GuideEvent.Tune(CH7)), events)
            assertNull(vm.info.value)
        }

    @Test
    fun selectFutureCellOpensInfo() =
        guideTest { vm ->
            val events = mutableListOf<GuideEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right)
            val gate = CompletableDeferred<Unit>()
            server.itemDetailsGate = gate

            vm.select()
            runCurrent()

            // Fetched at once, not after the dwell: the request is out and the overlay shows it is loading.
            assertEquals(1, server.itemDetailsCalls.size)
            val loading = checkNotNull(vm.info.value)
            assertTrue(loading.loading)
            assertEquals(min(90), loading.cell.startMs)
            gate.complete(Unit)
            runCurrent()
            val loaded = checkNotNull(vm.info.value)
            assertFalse(loaded.loading)
            assertNotNull(loaded.item)
            assertEquals(7, loaded.channelNumber)
            assertTrue(events.isEmpty())
        }

    @Test
    fun closeInfoReturnsTrueOnlyWithOverlay() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            assertFalse(vm.closeInfo())
            vm.move(GuideDirection.Right)
            vm.select()
            runCurrent()
            assertNotNull(vm.info.value)

            assertTrue(vm.closeInfo())
            assertNull(vm.info.value)
            assertFalse(vm.closeInfo())
        }

    @Test
    fun closeStopsTicksAndDetails() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            after(200)
            vm.close()
            val calls = fake.scheduleCalls.size

            after(2 * HOUR)

            assertEquals(GuideState.Closed, vm.snapshot.value.state)
            assertNull(vm.snapshot.value.focus)
            assertNull(vm.panel.value)
            assertEquals(0, server.itemDetailsCalls.size)
            assertEquals(calls, fake.scheduleCalls.size)
        }

    @Test
    fun reopenStartsFreshAtCurrentChannel() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right)
            vm.select()
            runCurrent()
            vm.close()

            vm.open(CH5)
            runCurrent()

            assertEquals(CH5, vm.focused().channel)
            assertEquals(min(60), vm.focused().anchorMs)
            assertNull(vm.info.value)
            assertEquals(min(60), vm.ready().viewportStartMs)
            assertEquals(min(65), vm.ready().nowMs)
        }

    @Test
    fun openingAgainWithoutClosingResets() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Right)
            vm.open(CH9)
            runCurrent()
            assertEquals(CH9, vm.focused().channel)
            assertEquals(min(0), vm.focused().cellStartMs)
        }

    @Test
    fun movingDownKeepsTheViewport() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.move(GuideDirection.Down)
            assertEquals(CH9, vm.focused().channel)
            assertEquals(min(60), vm.ready().viewportStartMs)
            assertEquals(0, vm.ready().firstVisibleRow)
        }

    // The focus resolves in the model it is read with, or there is no grid (or no cell) for it to be in.
    private fun consistencyProblem(snap: GuideSnapshot): String? {
        val ready = snap.state as? GuideState.Ready
        val focus = snap.focus
        val hasCells = ready != null && ready.model.rows.any { it.cells.isNotEmpty() }
        return when {
            focus == null -> if (hasCells) "no focus on a grid with cells" else null
            ready == null -> "focus $focus beside ${snap.state}"
            ready.model.indexOf(focus) == null -> "focus $focus not in the model"
            else -> null
        }
    }

    private fun TestScope.runThroughFillInAndLineupChange(
        vm: GuideViewModel,
        look: () -> Unit,
    ) {
        lineup(untilMin = 300)
        fake.cachedUntilMs = min(180)
        vm.open(CH7)
        runCurrent()
        look()
        vm.move(GuideDirection.Right)
        // A lineup change that drops the focused cell, and cells ending under the focus.
        schedule.onSchedule = { fake.channels.getValue(CH7).blocks = splitAround90(300) }
        repeat(400) {
            after(MIN / 2)
            look()
            if (it % 40 == 0) vm.move(GuideDirection.Right)
        }
        assertTrue("the window was replaced during the run", fake.scheduleCalls.size >= 3)
    }

    @Test
    fun tickNeverPublishesFocusOutsideTheModel() =
        guideTest { vm ->
            val problems = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.snapshot.collect { snap -> consistencyProblem(snap)?.let { problems += it } }
            }
            runThroughFillInAndLineupChange(vm) {}
            assertEquals(emptyList<String>(), problems)
        }

    @Test
    fun conflatingObserverNeverSeesAMismatchedPair() =
        guideTest { vm ->
            // Reads only the latest snapshot, at arbitrary points, like a frame that skipped the ones in between.
            val problems = mutableListOf<String>()
            runThroughFillInAndLineupChange(vm) { consistencyProblem(vm.snapshot.value)?.let { problems += it } }
            assertEquals(emptyList<String>(), problems)
        }

    @Test
    fun cachedRowsOpenStraightToReady() =
        guideTest { vm ->
            val states = mutableListOf<GuideState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.states().toList(states) }
            vm.open(CH7)
            runCurrent()
            assertEquals(listOf("Closed", "Ready"), states.map { it::class.simpleName })
        }

    @Test
    fun extensionDuringFillInNeverShrinksTheWindow() =
        guideTest { vm ->
            // The window ends at 2 h 30 min, so ticks want an extension, but the fill-in is still running.
            lineup(untilMin = 120)
            val gate = CompletableDeferred<Unit>()
            schedule.asNeededGate = gate
            val ends = mutableListOf<Long>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.states().collect { (it as? GuideState.Ready)?.let { ready -> ends += ready.model.endMs } }
            }
            vm.open(CH7)
            runCurrent()
            after(3 * MIN)
            assertEquals(1, fake.scheduleCalls.size)

            // Anything that reads later is shorter than the grid: it must not replace it.
            schedule.onSchedule = { call ->
                if (call == 3) fake.channels.values.forEach { e -> e.blocks = e.blocks.filter { it.endMs <= min(120) } }
            }
            gate.complete(Unit)
            runCurrent()
            after(MIN)

            assertEquals(
                listOf(ScheduleBuild.CachedOnly, ScheduleBuild.AsNeeded, ScheduleBuild.CachedOnly),
                fake.scheduleCalls.map { it.third },
            )
            assertEquals(ends.sorted(), ends)
            assertEquals(min(150), vm.ready().model.endMs)
        }

    @Test
    fun staleTuneDoesNotSurviveReopen() =
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            vm.select()
            vm.open(CH5)
            runCurrent()
            val events = mutableListOf<GuideEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            runCurrent()
            assertEquals(emptyList<GuideEvent>(), events)

            vm.select()
            assertEquals(listOf<GuideEvent>(GuideEvent.Tune(CH5)), events)
        }
}
