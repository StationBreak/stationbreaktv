// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Select on a program on now in the guide (spec 17, Task 10): the channel is tuned as a flip's commit tunes it. */
class TuneCommandTest {
    private fun Rig.flipReports() = reports.filterIsInstance<TuneReport.Flip>()

    @Test
    fun tuneGoesToChannelLikeAFlip() =
        runTest {
            val f = flipping()
            val before = f.r.tune.calls.size
            val at = currentTime

            f.send(LiveCommand.Tune(CH9))

            // At once, no settle: the guide's Select is a stop, not a step. The old channel is stopped, then the new
            // one tuned at its schedule position, as a flip's commit does.
            val calls =
                f.r.tune.calls
                    .drop(before)
            assertTrue(calls.toString(), calls[0] is Call.Stop)
            val tune = calls[1] as Call.Tune
            assertEquals(itemOf(9), tune.item)
            assertEquals(at, tune.at)
            assertTrue(f.r.seen.any { it is LiveState.Tuning && it.banner.number == 9 })
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(f.r.state is LiveState.Watching)
            assertEquals(CH9, f.r.lastChannel.load(f.r.account))
            // The tune line, as for a flip.
            assertEquals(listOf("playing"), f.r.flipReports().map { it.outcome })

            // Menu goes back to the channel the guide was opened over.
            f.send(LiveCommand.Previous)
            assertEquals(
                itemOf(5),
                f.r.tunes
                    .last()
                    .item,
            )
        }

    @Test
    fun tuneToTheChannelPlayingTunesNothing() =
        runTest {
            val f = flipping()
            val calls = f.r.tune.calls.size
            f.send(LiveCommand.Tune(CH5))
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(calls, f.r.tune.calls.size)
            assertTrue(f.r.state is LiveState.Watching)
        }

    @Test
    fun tuneToAChannelThatHasGoneDoesNothing() =
        runTest {
            val f = flipping()
            f.r.schedule.channels
                .getValue(CH9)
                .hidden = HiddenReason.CollectionsNotSynced
            val calls = f.r.tune.calls.size
            val state = f.r.state
            f.send(LiveCommand.Tune(CH9))
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(calls, f.r.tune.calls.size)
            assertEquals(state, f.r.state)
            assertTrue(f.r.reports.any { it is TuneReport.PressDropped && it.reason == "tune-off-air" })
        }

    @Test
    fun tuneReplacesAFlipStillSettling() =
        runTest {
            val f = flipping()
            val before = f.r.tunes.size
            f.up()
            advanceTimeBy(100.milliseconds)
            f.send(LiveCommand.Tune(CH2))
            advanceTimeBy(1.seconds)
            runCurrent()
            // Only the guide's channel is tuned: the number the press had moved to (9) never commits.
            assertEquals(
                listOf(itemOf(2)),
                f.r.tunes
                    .drop(before)
                    .map { it.item },
            )
        }

    /** Flipped from 5 to 9, so Menu has 5 to go back to; every schedule lookup after that takes [lookupMs]. */
    private suspend fun TestScope.onNineWithSlowLookups(lookupMs: Long): Flipping {
        val f = flipping()
        f.up()
        advanceTimeBy(1.seconds)
        runCurrent()
        f.r.schedule.onAirDelayMs = lookupMs
        return f
    }

    private fun Flipping.tunedItems(before: Int) = r.tunes.drop(before).map { it.item }

    @Test
    fun menuReplacesAGuideTuneStillLookingUp() =
        runTest {
            val f = onNineWithSlowLookups(5_000)
            val before = f.r.tunes.size
            f.send(LiveCommand.Tune(CH2))
            advanceTimeBy(1.seconds)
            // Menu is the newer intent: the guide's tune, still looking its channel up, does not land after it.
            f.send(LiveCommand.Previous)
            advanceTimeBy(15.seconds)
            runCurrent()
            assertEquals(listOf(itemOf(5)), f.tunedItems(before))
        }

    @Test
    fun aGuideTuneReplacesAMenuLookup() =
        runTest {
            val f = onNineWithSlowLookups(5_000)
            val before = f.r.tunes.size
            f.send(LiveCommand.Previous)
            advanceTimeBy(1.seconds)
            f.send(LiveCommand.Tune(CH2))
            advanceTimeBy(15.seconds)
            runCurrent()
            assertEquals(listOf(itemOf(2)), f.tunedItems(before))
        }

    @Test
    fun aFlipReplacesAGuideTuneStillLookingUp() =
        runTest {
            val f = onNineWithSlowLookups(5_000)
            val before = f.r.tunes.size
            f.send(LiveCommand.Tune(CH2))
            advanceTimeBy(1.seconds)
            f.down()
            advanceTimeBy(15.seconds)
            runCurrent()
            // Down from 9 is 5: the press, made after the guide's Select, is what lands.
            assertEquals(listOf(itemOf(5)), f.tunedItems(before))
        }

    @Test
    fun aGuideTuneDropsPressesWaitingForALineup() =
        runTest {
            // The sitting's own lineup read fails, so a press has nothing cached to step through: it waits for a read
            // of its own, which is slow.
            val f = flipping { schedule.airingsFailOn = setOf(2) }
            f.r.schedule.airingsDelayMs = 10_000
            val before = f.r.tunes.size
            f.up()
            assertTrue("the press waits, no number yet: ${f.r.state}", f.r.state !is LiveState.Choosing)
            f.send(LiveCommand.Tune(CH2))
            advanceTimeBy(30.seconds)
            runCurrent()
            // The guide's 2; the waiting Up (to 9) is never applied.
            assertEquals(listOf(itemOf(2)), f.tunedItems(before))
            assertTrue(f.r.reports.any { it is TuneReport.PressDropped && it.reason == "superseded-by-tune" })
        }

    @Test
    fun aGuideTuneWithNoChannelsOnTheAirDoesNothing() =
        runTest {
            val r = Rig(this)
            val keys = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = keys.receiveAsFlow())
            assertEquals(LiveState.NoChannels, r.state)
            keys.trySend(LiveCommand.Tune(CH9))
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(r.tune.calls.isEmpty())
            assertTrue(r.reports.any { it is TuneReport.PressDropped && it.reason == "tune-off-screen" })
        }

    @Test
    fun aGuideTuneBeforeTheFirstSittingDropsTheEarlyPresses() =
        runTest {
            // A cold launch: the first day build takes 20 s, and a press is made before it lands.
            val r = Rig(this)
            r.schedule.channel(CH2, 2, listOf(program(0, 600, 2)))
            r.schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
            r.schedule.channel(CH9, 9, listOf(program(0, 600, 9)))
            r.lastChannel.save(r.account, CH5)
            r.tune.preResolveResult = { false }
            r.schedule.airingsDelayMs = 20_000
            val keys = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = keys.receiveAsFlow())
            advanceTimeBy(1.seconds)
            r.schedule.airingsDelayMs = 0
            keys.trySend(LiveCommand.Flip(FlipDirection.Up))
            runCurrent()

            keys.trySend(LiveCommand.Tune(CH2))
            advanceTimeBy(40.seconds)
            runCurrent()

            // No account to tune with before the first sitting has a channel: the pick is dropped, the first sitting
            // lands on the saved 5 as it would have, and the early Up the pick replaced is not stepped from there.
            assertEquals(listOf(itemOf(5)), r.tunes.map { it.item })
            assertTrue(r.reports.any { it is TuneReport.PressDropped && it.reason == "tune-no-anchor" })
            assertTrue(r.reports.none { it is TuneReport.Failed })
        }

    @Test
    fun aChannelTheCachedLineupDoesNotHaveYetIsReadAgain() =
        runTest {
            val f = flipping()
            val before = f.r.tunes.size
            // A channel that came back on (#299) after the lineup was cached: the guide shows it, so Select tunes it.
            f.r.schedule.channel(CH7, 7, listOf(program(0, 600, 7)))
            f.send(LiveCommand.Tune(CH7))
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(listOf(itemOf(7)), f.tunedItems(before))
        }

    @Test
    fun theTuneLineSaysTheGuideTunedIt() =
        runTest {
            val f = flipping()
            f.send(LiveCommand.Tune(CH9))
            advanceTimeBy(1.seconds)
            runCurrent()
            f.up()
            advanceTimeBy(2.seconds)
            runCurrent()
            val lines = f.r.flipReports().map { it.line() }
            assertEquals(2, lines.size)
            assertTrue(lines[0], lines[0].endsWith(" via=guide"))
            // A flip's line is as it was.
            assertTrue(lines[1], !lines[1].contains("via="))
        }
}
