// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.playback.PlaybackEnd
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Catch-up (spec 20, ADR-0021 4): when a behind play's item ends, the channel plays what is on now, at its schedule
 * position, as a straight cut to a handle resolved ahead on media time. The schedule never moves.
 */
class CatchUpTest {
    private val timings = LiveTimings()
    private val leadMs = timings.preResolveLead.inWholeMilliseconds

    /** One second in: the first picture is up (no key acts while the snow shows). */
    private val upMs = 1_000L

    private class Keys(
        val r: Rig,
        val keys: Channel<LiveCommand>,
        val job: Job,
    ) {
        /** Ends the run, then checks every handle made was tuned or discarded, exactly once. */
        fun finish() {
            job.cancel()
            r.scope.testScheduler.runCurrent()
            r.assertEveryPreResolveEndsOnce()
        }

        fun discards(item: ItemId) = r.tune.calls.count { it is Call.Discard && it.item == item }

        fun resolves(item: ItemId) =
            r.tune.calls
                .filterIsInstance<Call.PreResolve>()
                .filter { it.item == item }

        fun press(command: LiveCommand) {
            keys.trySend(command)
            r.scope.testScheduler.runCurrent()
        }

        fun pressAt(
            ms: Long,
            command: LiveCommand,
        ) {
            r.at(ms)
            press(command)
        }
    }

    /** One tune, however it was made: the item, the media start asked for, and when (test ms). */
    private data class Tuned(
        val item: ItemId,
        val startMs: Long,
        val at: Long,
        val handle: Boolean,
    )

    private fun Rig.tuned(): List<Tuned> =
        tune.calls.mapNotNull {
            when (it) {
                is Call.Tune -> Tuned(it.item, it.start.inWholeMilliseconds, it.at, handle = false)
                is Call.TuneHandle -> Tuned(it.item, it.start.inWholeMilliseconds, it.at, handle = true)
                else -> null
            }
        }

    /** CH7 with [blocks] (item 1 from minute 0 to 30, item 2 to 60 and item 3 to 90), joined at [startMin]. */
    private suspend fun TestScope.keys(
        startMin: Long = 10,
        setup: Rig.() -> Unit = {},
        blocks: List<AiringBlock> = listOf(program(0, 30, 1), program(30, 60, 2), program(60, 90, 3)),
    ): Keys {
        val r = Rig(this, startMin = startMin)
        r.schedule.channel(CH7, 7, blocks)
        r.setup()
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        val job = r.start(commands = keys.receiveAsFlow())
        return Keys(r, keys, job)
    }

    private fun assertNear(
        expectedMs: Long,
        actualMs: Long,
        toleranceMs: Long = 1_000,
    ) = assertTrue("expected $expectedMs, was $actualMs", abs(expectedMs - actualMs) <= toleranceMs)

    @Test
    fun tenMinutesPausedStartsTheNextItemTenMinutesIn() =
        runTest {
            val k = keys()
            // Paused at minute 10 of the clock for ten minutes, at media 10 min.
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(10 * MIN + upMs, LiveCommand.PlayPause)
            // Media 10 to the cut at 30: the item ends 20 minutes later, at minute 40 of the clock.
            k.r.at(30 * MIN - 1_000)
            assertEquals(1, k.r.tuned().size)
            k.r.at(30 * MIN + 1_000)
            val next = k.r.tuned().last()
            assertEquals(2, k.r.tuned().size)
            assertEquals(itemOf(2), next.item)
            assertNear(10 * MIN, next.startMs)
            k.finish()
        }

    @Test
    fun theNetBehindCountsBackAndSkip() =
        runTest {
            val k = keys()
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(10 * MIN + upMs, LiveCommand.PlayPause)
            repeat(4) { k.press(LiveCommand.Seek(SeekDirection.Ahead, false, null)) }
            k.press(LiveCommand.SeekReleased)
            // Ten minutes paused, two skipped: eight behind, so the next item starts eight minutes in.
            k.r.at(28 * MIN + 2_000)
            val next = k.r.tuned().last()
            assertEquals(itemOf(2), next.item)
            assertNear(8 * MIN, next.startMs)
            assertTrue(next.handle)
            k.finish()
        }

    @Test
    fun behindLongerThanTheNextItemLandsOnWhatIsOnNow() =
        runTest {
            val k = keys()
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(40 * MIN + upMs, LiveCommand.PlayPause)
            // The item ends at minute 70 of the clock: item 2's slot is long gone, item 3 is on.
            k.r.at(60 * MIN + 1_000)
            val next = k.r.tuned().last()
            assertEquals(2, k.r.tuned().size)
            assertEquals(itemOf(3), next.item)
            assertNear(10 * MIN, next.startMs)
            k.finish()
        }

    @Test
    fun theItemsSlotEndingWhilePausedChangesNothing() =
        runTest {
            val k = keys()
            // Paused from minute 25 to 35 of the clock: item 1's slot ends at 30 meanwhile.
            k.pressAt(15 * MIN, LiveCommand.PlayPause)
            k.r.at(20 * MIN + 1_000)
            assertEquals(1, k.r.tuned().size)
            k.pressAt(25 * MIN, LiveCommand.PlayPause)
            k.r.at(26 * MIN)
            assertEquals(1, k.r.tuned().size)
            val control = checkNotNull(k.r.tune.controlOf(itemOf(1)))
            assertEquals(listOf("pause", "resume"), control.calls)
            assertFalse(checkNotNull(k.r.live.transport.value).paused)
            assertTrue(k.r.state is LiveState.Watching)
            k.finish()
        }

    @Test
    fun aFileThatEndsEarlyInsideItsBlockHoldsTheIdent() =
        runTest {
            // Item 1's file ends at media 25 min, five minutes before its cut. Two minutes behind, that is minute 27
            // of the clock (test minute 17): still inside block 1.
            val k = keys(setup = { tune.script(itemOf(1), tune.playsThenEnds(17.minutes, PlaybackEnd.Ended)) })
            k.pressAt(MIN, LiveCommand.PlayPause)
            k.pressAt(3 * MIN, LiveCommand.PlayPause)
            k.r.at(17 * MIN + 1_000)
            assertTrue("state=${k.r.state}", k.r.state is LiveState.Ident)
            k.r.at(20 * MIN - 1_000)
            assertTrue(k.r.state is LiveState.Ident)
            assertEquals(1, k.r.tuned().size)
            // Minute 30 of the clock: item 2 cuts in at its start, as after an early end on the live path.
            k.r.at(20 * MIN + 1_000)
            val next = k.r.tuned().last()
            assertEquals(itemOf(2), next.item)
            assertEquals(0L, next.startMs)
            assertNear(20 * MIN, next.at)
            assertTrue(next.handle)
            k.finish()
        }

    @Test
    fun aFileThatEndsEarlyAfterTheBlockCatchesUpWithNoIdent() =
        runTest {
            // The same file, eight minutes behind: it ends at minute 33 of the clock (test minute 23), past block 1.
            val k = keys(setup = { tune.script(itemOf(1), tune.playsThenEnds(23.minutes, PlaybackEnd.Ended)) })
            k.pressAt(MIN, LiveCommand.PlayPause)
            k.pressAt(9 * MIN, LiveCommand.PlayPause)
            k.r.at(23 * MIN + 1_000)
            assertTrue(
                k.r.seen
                    .dropWhile { it !is LiveState.Watching }
                    .none { it is LiveState.Ident },
            )
            val next = k.r.tuned().last()
            assertEquals(itemOf(2), next.item)
            assertNear(23 * MIN, next.at, 10)
            assertNear(3 * MIN, next.startMs)
            k.finish()
        }

    @Test
    fun aFileThatRunsLongWhileBehindIsCutAtTheBlocksEnd() =
        runTest {
            // Item 1's file runs to media 35 min, five minutes past its cut at 30.
            val k = keys(setup = { tune.script(itemOf(1), tune.playsThenEnds(28.minutes, PlaybackEnd.Ended)) })
            k.pressAt(MIN, LiveCommand.PlayPause)
            k.pressAt(4 * MIN, LiveCommand.PlayPause)
            // Three minutes behind: media 30 is reached at minute 33 of the clock (test minute 23).
            k.r.at(23 * MIN - 1_000)
            assertEquals(1, k.r.tuned().size)
            k.r.at(23 * MIN + 1_000)
            val next = k.r.tuned().last()
            assertEquals(itemOf(2), next.item)
            assertNear(23 * MIN, next.at)
            assertNear(3 * MIN, next.startMs)
            assertTrue(next.handle)
            assertTrue(
                k.r.seen
                    .dropWhile { it !is LiveState.Watching }
                    .none { it is LiveState.Ident },
            )
            k.finish()
        }

    @Test
    fun theCatchUpIsAStraightCutWithNoSnow() =
        runTest {
            val k = keys()
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(10 * MIN + upMs, LiveCommand.PlayPause)
            k.r.at(30 * MIN + 1_000)
            val firstWatching = k.r.seen.indexOfFirst { it is LiveState.Watching }
            assertTrue(
                k.r.seen
                    .drop(firstWatching)
                    .none { it is LiveState.Tuning },
            )
            val cut =
                k.r.tune.calls
                    .last { it is Call.TuneHandle || it is Call.Tune }
            val handle = assertIs<Call.TuneHandle>(cut)
            assertEquals(itemOf(2), handle.item)
            // The start asked for is the schedule's position at the cut: minute 40 of the clock is 10 min into item 2.
            assertEquals(30 * MIN, handle.at)
            assertEquals(10 * MIN, handle.start.inWholeMilliseconds)
            val line =
                k.r.reports
                    .filterIsInstance<TuneReport.Tune>()
                    .last()
                    .line()
            // Resolved ahead: no resolve inside the tune.
            assertTrue(line, line.startsWith("tune kind=catchup resolveMs=0 "))
            k.finish()
        }

    @Test
    fun theCatchUpHandleIsDiscardedOnPauseAndResolvedAgain() =
        runTest {
            val k = keys()
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(10 * MIN + upMs, LiveCommand.PlayPause)
            // The cut is at test minute 30: the catch-up is resolved [preResolveLead] of media before it.
            k.r.at(30 * MIN - leadMs - 1_000)
            assertTrue(
                k.r.tune.calls
                    .none { it is Call.PreResolve },
            )
            k.r.at(30 * MIN - leadMs + 1_000)
            val first =
                k.r.tune.calls
                    .filterIsInstance<Call.PreResolve>()
                    .single()
            assertEquals(itemOf(2), first.item)
            assertNear(30 * MIN - leadMs, first.at, 10)
            // Paused inside the lead: the handle is let go.
            k.pressAt(30 * MIN - 30_000, LiveCommand.PlayPause)
            assertEquals(
                1,
                k.r.tune.calls
                    .count { it is Call.Discard },
            )
            k.r.at(31 * MIN)
            assertEquals(
                1,
                k.r.tune.calls
                    .count { it is Call.PreResolve },
            )
            // Play: 30 s of media are left, inside the lead, so it is resolved again at once.
            k.press(LiveCommand.PlayPause)
            val again =
                k.r.tune.calls
                    .filterIsInstance<Call.PreResolve>()
            assertEquals(2, again.size)
            assertEquals(31 * MIN, again.last().at)
            k.r.at(31 * MIN + 30_000 + 1_000)
            val cut =
                assertIs<Call.TuneHandle>(
                    k.r.tune.calls
                        .last { it is Call.TuneHandle || it is Call.Tune },
                )
            assertEquals(itemOf(2), cut.item)
            assertEquals(
                1,
                k.r.tune.calls
                    .count { it is Call.Discard },
            )
            k.finish()
        }

    @Test
    fun theBoundaryLookAheadIsDroppedWhenBehind() =
        runTest {
            val k = keys()
            k.pressAt(MIN, LiveCommand.PlayPause)
            // Block 1 ends at minute 30 of the clock (test minute 20): its look-ahead would resolve item 2 at
            // test minute 20 less the lead. Behind, it is not.
            k.r.at(20 * MIN + 1_000)
            assertTrue(
                k.r.tune.calls
                    .none { it is Call.PreResolve },
            )
            assertEquals(1, k.r.tuned().size)
            k.finish()
        }

    @Test
    fun aheadDriftAndBehindCatchUpOnTheSittingsClock() =
        runTest {
            // Joined at minute 25 with item 1 dead: the channel moves on to item 2 early, about five minutes ahead.
            val k =
                keys(startMin = 25, setup = { tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead()) })
            k.r.at(10_000)
            val sitting = checkNotNull(k.r.live.currentSitting())
            val driftMs = sitting.driftMs
            assertTrue("drift=$driftMs", driftMs in 4 * MIN..5 * MIN)
            val second = k.r.tuned().last()
            assertEquals(itemOf(2), second.item)
            k.pressAt(MIN, LiveCommand.PlayPause)
            k.pressAt(3 * MIN, LiveCommand.PlayPause)
            // Two minutes behind item 2 (30 min of media from its start): its cut is 32 min after it was tuned.
            k.r.at(second.at + 32 * MIN + 1_000)
            val next = k.r.tuned().last()
            assertEquals(itemOf(3), next.item)
            // The position comes from the sitting's clock (the clock plus the drift), not the clock alone.
            val vnowAtCut = T0 + 25 * MIN + next.at + driftMs
            assertNear(vnowAtCut - (T0 + 60 * MIN), next.startMs)
            assertNear(2 * MIN, next.startMs)
            assertTrue(next.handle)
            k.finish()
        }

    @Test
    fun aChannelHiddenWhilePausedIsLostAtTheCatchUp() =
        runTest {
            val k = keys(setup = { schedule.channel(CH9, 9, listOf(program(0, 90, 9))) })
            k.pressAt(MIN, LiveCommand.PlayPause)
            k.r.at(5 * MIN)
            k.r.schedule.channels
                .getValue(CH7)
                .hidden = HiddenReason.EmptyToday
            k.pressAt(10 * MIN, LiveCommand.PlayPause)
            // Nine minutes behind from media 11: the cut is at test minute 29.
            k.r.at(29 * MIN + 1_000)
            assertEquals(LiveState.Ident(null), k.r.state)
            k.r.at(29 * MIN + 10_000)
            val next = k.r.tuned().last()
            assertEquals(itemOf(9), next.item)
            assertEquals(2, k.r.tuned().size)
            k.finish()
        }

    @Test
    fun goLiveAfterTheBlockEndedCatchesUp() =
        runTest {
            val k = keys()
            k.pressAt(upMs, LiveCommand.PlayPause)
            // Block 1 ends at minute 30 of the clock (test minute 20): live is now item 2.
            k.pressAt(25 * MIN, LiveCommand.GoLive)
            val next = k.r.tuned().last()
            assertEquals(itemOf(2), next.item)
            assertEquals(25 * MIN, next.at)
            assertEquals(5 * MIN, next.startMs)
            val control = checkNotNull(k.r.tune.controlOf(itemOf(1)))
            assertEquals(listOf("pause"), control.calls)
            k.finish()
        }

    @Test
    fun aSettledAheadToTheCutAfterTheBlockEndedCatchesUpWithoutASeek() =
        runTest {
            val k = keys()
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(25 * MIN, LiveCommand.PlayPause)
            // From media 10:01, forty skips pass the cut at 30:00: the target clamps there (live is past it).
            repeat(40) { k.press(LiveCommand.Seek(SeekDirection.Ahead, false, null)) }
            k.press(LiveCommand.SeekReleased)
            k.r.at(25 * MIN + 1_000)
            val next = k.r.tuned().last()
            assertEquals(itemOf(2), next.item)
            assertNear(5 * MIN, next.startMs)
            val control = checkNotNull(k.r.tune.controlOf(itemOf(1)))
            assertTrue(control.calls.none { it.startsWith("seek:") })
            k.finish()
        }

    @Test
    fun backAtLiveTheBoundaryIsResolvedAheadAgain() =
        runTest {
            val k = keys()
            k.pressAt(MIN, LiveCommand.PlayPause)
            k.pressAt(2 * MIN, LiveCommand.GoLive)
            k.r.at(3 * MIN)
            assertTrue(checkNotNull(k.r.live.currentSitting()).behind == null)
            // Live again: block 1's end (test minute 20) is a prepared cut, resolved the lead before it.
            k.r.at(20 * MIN + 1_000)
            val resolve =
                k.r.tune.calls
                    .filterIsInstance<Call.PreResolve>()
                    .single()
            assertEquals(itemOf(2), resolve.item)
            assertEquals(20 * MIN - leadMs, resolve.at)
            val cut =
                assertIs<Call.TuneHandle>(
                    k.r.tune.calls
                        .last { it is Call.TuneHandle || it is Call.Tune },
                )
            assertEquals(itemOf(2), cut.item)
            assertEquals(0L, cut.start.inWholeMilliseconds)
            k.finish()
        }

    @Test
    fun aResolveStillRunningAtTheCutIsCancelledAndTunedFresh() =
        runTest {
            // The server takes a minute to resolve: longer than the lead.
            val k = keys(setup = { tune.preResolveDelayMs = 60_000 })
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(10 * MIN + upMs, LiveCommand.PlayPause)
            k.r.at(30 * MIN - leadMs + 1_000)
            assertEquals(1, k.resolves(itemOf(2)).size)
            k.r.at(30 * MIN + 1_000)
            // Cancelled at the cut: no handle was made, and the tune resolves for itself, under the snow.
            assertTrue(
                k.r.tune.handleFates
                    .isEmpty(),
            )
            val next = k.r.tuned().last()
            assertEquals(itemOf(2), next.item)
            assertFalse(next.handle)
            assertEquals(30 * MIN, next.at)
            val firstWatching = k.r.seen.indexOfFirst { it is LiveState.Watching }
            assertTrue(
                k.r.seen
                    .drop(firstWatching)
                    .any { it is LiveState.Tuning },
            )
            k.finish()
        }

    @Test
    fun aSeekInsideTheLeadDiscardsTheHandleAndArmsAgain() =
        runTest {
            val k = keys()
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(10 * MIN + upMs, LiveCommand.PlayPause)
            k.r.at(30 * MIN - 30_000)
            assertEquals(1, k.resolves(itemOf(2)).size)
            // Back 10 s inside the lead: the cut moves, so the handle is let go and resolved again for the new cut.
            k.press(LiveCommand.Seek(SeekDirection.Back, false, null))
            k.press(LiveCommand.SeekReleased)
            k.r.at(30 * MIN - 29_000)
            assertEquals(1, k.discards(itemOf(2)))
            val resolves = k.resolves(itemOf(2))
            assertEquals(2, resolves.size)
            val discardAt =
                k.r.tune.calls
                    .single { it is Call.Discard }
                    .at
            assertTrue(resolves.last().at >= discardAt)
            k.r.at(30 * MIN + 11_000)
            val next = k.r.tuned().last()
            assertEquals(itemOf(2), next.item)
            assertTrue(next.handle)
            assertEquals(1, k.discards(itemOf(2)))
            k.finish()
        }

    @Test
    fun aHandleForAnotherBlockIsDiscardedAndTunedFresh() =
        runTest {
            // Item 2 runs to minute 41 of the clock, item 3 from there.
            val blocks = listOf(program(0, 30, 1), program(30, 41, 2), program(41, 90, 3))
            val k = keys(blocks = blocks)
            k.pressAt(upMs, LiveCommand.PlayPause)
            // Ten minutes 40 behind: the cut is due at minute 40:40 of the clock, inside item 2's slot.
            k.pressAt(10 * MIN + 41_000, LiveCommand.PlayPause)
            k.r.at(30 * MIN - 4_000)
            assertEquals(1, k.resolves(itemOf(2)).size)
            // Then the media runs at half speed: the cut comes at minute 41:24, in item 3's slot.
            checkNotNull(k.r.tune.controlOf(itemOf(1))).rate = 0.5
            k.r.at(31 * MIN + 30_000)
            assertEquals(1, k.discards(itemOf(2)))
            val next = k.r.tuned().last()
            assertEquals(itemOf(3), next.item)
            assertFalse(next.handle)
            assertNear(24_000, next.startMs, 2_000)
            k.finish()
        }

    @Test
    fun aFileThatEndsInsideItsBlockWithAHandleArmedDiscardsIt() =
        runTest {
            // Ten seconds behind, the file ends at media 29:40 (minute 29:50 of the clock, test 19:50), 20 s before
            // its cut: the catch-up was armed at media 29:15.
            val end = 19.minutes + 50.seconds
            val k = keys(setup = { tune.script(itemOf(1), tune.playsThenEnds(end, PlaybackEnd.Ended)) })
            k.pressAt(MIN, LiveCommand.PlayPause)
            k.pressAt(MIN + 10_000, LiveCommand.PlayPause)
            k.r.at(end.inWholeMilliseconds - 1_000)
            assertEquals(1, k.resolves(itemOf(2)).size)
            k.r.at(end.inWholeMilliseconds + 1_000)
            assertTrue(k.r.state is LiveState.Ident)
            assertEquals(1, k.discards(itemOf(2)))
            // The ident holds to the block's end; item 2 is resolved ahead again for its start, and cut in then.
            k.r.at(20 * MIN + 1_000)
            val next = k.r.tuned().last()
            assertEquals(itemOf(2), next.item)
            assertEquals(0L, next.startMs)
            assertTrue(next.handle)
            k.finish()
        }

    @Test
    fun aFlipWhileArmedDiscardsTheHandle() =
        runTest {
            val k = keys(setup = { schedule.channel(CH9, 9, listOf(program(0, 90, 9))) })
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(10 * MIN + upMs, LiveCommand.PlayPause)
            k.r.at(30 * MIN - 30_000)
            assertEquals(1, k.resolves(itemOf(2)).size)
            k.press(LiveCommand.Flip(FlipDirection.Up))
            k.press(LiveCommand.FlipReleased)
            k.r.at(30 * MIN - 25_000)
            assertEquals(
                itemOf(9),
                k.r
                    .tuned()
                    .last()
                    .item,
            )
            assertEquals(1, k.discards(itemOf(2)))
            k.finish()
        }

    @Test
    fun theSittingEndingWhileArmedDiscardsTheHandle() =
        runTest {
            val k = keys()
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(10 * MIN + upMs, LiveCommand.PlayPause)
            k.r.at(30 * MIN - 30_000)
            assertEquals(1, k.resolves(itemOf(2)).size)
            k.finish()
            assertEquals(1, k.discards(itemOf(2)))
        }

    @Test
    fun theSittingEndingWhileTheCatchUpIsHeldDiscardsIt() =
        runTest {
            val k = keys()
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(10 * MIN + upMs, LiveCommand.PlayPause)
            k.r.at(30 * MIN - 10_000)
            assertEquals(1, k.resolves(itemOf(2)).size)
            // A slow look at what is on now: the handle is held for it when the run ends.
            k.r.schedule.onAirDelayMs = 5_000
            k.r.at(30 * MIN + 2_000)
            assertEquals(1, k.r.tuned().size)
            k.finish()
            assertEquals(1, k.discards(itemOf(2)))
        }

    @Test
    fun theCatchUpReportsTheCutsGap() =
        runTest {
            val k = keys(setup = { tune.script(itemOf(2), FakeTune.playsAfter(500.milliseconds)) })
            k.pressAt(upMs, LiveCommand.PlayPause)
            k.pressAt(10 * MIN + upMs, LiveCommand.PlayPause)
            k.r.at(30 * MIN + 1_000)
            val tune = k.r.reports.indexOfLast { it is TuneReport.Tune }
            assertEquals(TuneKind.CatchUp, (k.r.reports[tune] as TuneReport.Tune).kind)
            // The gap from the item's end to the next picture: the tune's 500 ms.
            assertEquals(TuneReport.Cut(500, 0, 500), k.r.reports.getOrNull(tune + 1))
            k.finish()
        }
}
