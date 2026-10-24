// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.Job
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
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Flipping (spec 15): the number moves at once, the old channel plays on, and the stop commits after the settle. */
class LiveChannelFlipTest {
    private val ch2 = ChannelId("ch-2")

    private class Flipping(
        val r: Rig,
        val job: Job,
        val keys: Channel<LiveCommand>,
        private val scope: TestScope,
    ) {
        fun send(command: LiveCommand) {
            keys.trySend(command)
            scope.runCurrent()
        }

        fun up() = send(LiveCommand.Flip(FlipDirection.Up))

        fun down() = send(LiveCommand.Flip(FlipDirection.Down))
    }

    /** Channels 2, 5 and 9, each with one long program (item = its number); the sitting starts on [startOn]. */
    private suspend fun TestScope.flipping(
        startOn: ChannelId = CH5,
        timings: LiveTimings = LiveTimings(),
        setup: Rig.() -> Unit = {},
    ): Flipping {
        val r = Rig(this, timings = timings)
        r.schedule.channel(ch2, 2, listOf(program(0, 600, 2)))
        r.schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
        r.schedule.channel(CH9, 9, listOf(program(0, 600, 9)))
        r.lastChannel.save(r.account, startOn)
        // These tests are about the flip's own sequence; look-ahead has its own (LiveChannelNeighboursTest).
        r.tune.preResolveResult = { false }
        r.setup()
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        val job = r.start(commands = keys.receiveAsFlow())
        // Past the minimum snow: the picture of the first channel is up.
        advanceTimeBy(1.seconds)
        runCurrent()
        return Flipping(r, job, keys, this)
    }

    private fun Rig.stops() = tune.calls.filterIsInstance<Call.Stop>()

    private fun Rig.flipReports() = reports.filterIsInstance<TuneReport.Flip>()

    @Test
    fun upShowsTheNextNumberAtOnceAndKeepsPlaying() =
        runTest {
            val f = flipping()
            val before = f.r.tune.calls.size
            f.up()
            assertEquals(LiveState.Choosing(ChannelTag(9, "Test Channel 9"), overPicture = true), f.r.state)
            assertEquals(before, f.r.tune.calls.size)
        }

    @Test
    fun theFlipCommitsAfterTheSettleDelay() =
        runTest {
            val f = flipping()
            val before = f.r.tune.calls.size
            f.up()
            advanceTimeBy(399.milliseconds)
            runCurrent()
            assertEquals(before, f.r.tune.calls.size)
            advanceTimeBy(2.milliseconds)
            runCurrent()
            val calls =
                f.r.tune.calls
                    .drop(before)
            assertTrue(calls[0] is Call.Stop)
            val tune = calls[1] as Call.Tune
            assertEquals(itemOf(9), tune.item)
            // The schedule offset: the clock started 10 minutes into the one program.
            assertEquals(10 * MIN + tune.at, tune.start.inWholeMilliseconds)
            assertTrue(f.r.state is LiveState.Tuning)
            assertTrue(f.r.seen.any { it is LiveState.Tuning && it.banner.number == 9 })
            advanceTimeBy(1.seconds)
            assertTrue(f.r.state is LiveState.Watching)
            assertEquals(CH9, f.r.lastChannel.load(f.r.account))
        }

    @Test
    fun rapidPressesTuneOnlyWhereTheyStop() =
        runTest {
            val f = flipping()
            val before = f.r.tunes.size
            f.up()
            advanceTimeBy(100.milliseconds)
            f.up()
            val second = currentTime
            advanceTimeBy(500.milliseconds)
            runCurrent()
            val tunes = f.r.tunes.drop(before)
            assertEquals(1, tunes.size)
            assertEquals(itemOf(2), tunes[0].item)
            assertEquals(second + 400, tunes[0].at)
        }

    @Test
    fun aFullWrapBackToTheSameChannelTunesNothing() =
        runTest {
            val f = flipping()
            val before = f.r.tune.calls.size
            f.up()
            f.up()
            f.up()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertEquals(before, f.r.tune.calls.size)
            assertTrue(f.r.state is LiveState.Watching)
        }

    @Test
    fun downWraps() =
        runTest {
            val f = flipping(startOn = ch2)
            f.down()
            assertEquals(ChannelTag(9, "Test Channel 9"), (f.r.state as LiveState.Choosing).target)
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertEquals(
                itemOf(9),
                f.r.tunes
                    .last()
                    .item,
            )
        }

    @Test
    fun aPressWhileTuningMovesTheNumberOverSnow() =
        runTest {
            val f = flipping { tune.script(itemOf(9), FakeTune.playsAfter(3.seconds)) }
            f.up()
            advanceTimeBy(401.milliseconds)
            runCurrent()
            assertTrue(f.r.state is LiveState.Tuning)
            advanceTimeBy(1.seconds)
            f.up()
            assertEquals(LiveState.Choosing(ChannelTag(2, "Test Channel 2"), overPicture = false), f.r.state)
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertEquals(listOf(itemOf(5), itemOf(9), itemOf(2)), f.r.tunes.map { it.item })
            advanceTimeBy(1.seconds)
            assertTrue(f.r.state is LiveState.Watching)
            assertEquals(ch2, f.r.lastChannel.load(f.r.account))
            // The 9 tune was cut off, not left to finish and show.
            advanceTimeBy(5.seconds)
            assertTrue(f.r.seen.none { it is LiveState.Watching && it.banner?.number == 9 })
        }

    @Test
    fun menuTogglesBetweenTheLastTwo() =
        runTest {
            val f = flipping()
            f.up()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertEquals(
                itemOf(9),
                f.r.tunes
                    .last()
                    .item,
            )
            val at = currentTime
            f.send(LiveCommand.Previous)
            assertEquals(
                itemOf(5),
                f.r.tunes
                    .last()
                    .item,
            )
            assertEquals(
                at,
                f.r.tunes
                    .last()
                    .at,
            )
            f.send(LiveCommand.Previous)
            assertEquals(
                itemOf(9),
                f.r.tunes
                    .last()
                    .item,
            )
        }

    @Test
    fun menuWithNoPreviousDoesNothing() =
        runTest {
            val f = flipping()
            val state = f.r.state
            val calls = f.r.tune.calls.size
            f.send(LiveCommand.Previous)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(state, f.r.state)
            assertEquals(calls, f.r.tune.calls.size)
        }

    @Test
    fun menuToAChannelThatHasGoneDoesNothing() =
        runTest {
            val f = flipping()
            f.up()
            advanceTimeBy(1.seconds)
            runCurrent()
            f.r.schedule.channels
                .getValue(CH5)
                .hidden = HiddenReason.CollectionsNotSynced
            val calls = f.r.tune.calls.size
            val state = f.r.state
            f.send(LiveCommand.Previous)
            advanceTimeBy(1.seconds)
            assertEquals(calls, f.r.tune.calls.size)
            assertEquals(state, f.r.state)
        }

    @Test
    fun aTargetNotOnTheAirIsSteppedPast() =
        runTest {
            val f = flipping()
            f.up()
            // Nothing is on channel 9 at the commit.
            f.r.schedule.channels
                .getValue(CH9)
                .blocks = emptyList()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertEquals(
                itemOf(2),
                f.r.tunes
                    .last()
                    .item,
            )
            assertEquals(ch2, f.r.lastChannel.load(f.r.account))
        }

    @Test
    fun stoppingDuringTheSettleStartsNoTune() =
        runTest {
            val f = flipping()
            val tunes = f.r.tunes.size
            val stopsBefore = f.r.stops().size
            f.up()
            advanceTimeBy(200.milliseconds)
            f.job.cancel()
            runCurrent()
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(tunes, f.r.tunes.size)
            assertEquals(stopsBefore + 1, f.r.stops().size)
        }

    @Test
    fun theFlipLineIsTimedFromTheLastPress() =
        runTest {
            val f =
                flipping {
                    tune.script(itemOf(2), FakeTune.playsAfter(700.milliseconds, TuneTimings(firstFrameMs = 600)))
                }
            f.up()
            advanceTimeBy(100.milliseconds)
            f.up()
            advanceTimeBy(2.seconds)
            runCurrent()
            val flips = f.r.flipReports()
            assertEquals(1, flips.size)
            val flip = flips[0]
            assertEquals(400L, flip.settleMs)
            assertEquals(1100L, flip.toPlayingMs)
            assertEquals(1100L, flip.elapsedMs)
            assertEquals(1000L, flip.toFirstFrameMs)
            assertEquals("playing", flip.outcome)
            assertEquals(flips, f.r.live.flips)
        }

    @Test
    fun theFlipLogKeepsTheNewest() =
        runTest {
            val f = flipping(timings = LiveTimings(flipLogSize = 2))
            repeat(3) {
                f.up()
                advanceTimeBy(1.seconds)
                runCurrent()
            }
            assertEquals(3, f.r.flipReports().size)
            assertEquals(f.r.flipReports().takeLast(2), f.r.live.flips)
        }

    @Test
    fun aHeldKeyKeepsTheNumberUpWhileTheOldChannelCarriesOn() =
        runTest {
            val f = flipping()
            val tunes = f.r.tunes.size
            // Five seconds of auto-repeat: longer than the old banner lingers, which must not take the number down.
            repeat(17) {
                f.up()
                assertTrue(f.r.state is LiveState.Choosing)
                advanceTimeBy(300.milliseconds)
                assertTrue(f.r.state is LiveState.Choosing)
            }
            assertEquals(tunes, f.r.tunes.size)
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertEquals(tunes + 1, f.r.tunes.size)
        }

    @Test
    fun theFlipLineHasNumbersAndFixedNamesOnly() {
        val line = TuneReport.Flip(null, 900, 400, 900, "playing", "transcode", "loadFailed:CODE").line()
        assertEquals(
            "flip toFirstFrameMs=-1 toPlayingMs=900 settleMs=400 elapsedMs=900 outcome=playing route=transcode " +
                "fallback=loadFailed:CODE",
            line,
        )
    }

    @Test
    fun aFlipToAChannelThatCannotPlayReportsFailed() =
        runTest {
            val f = flipping { tune.script(itemOf(9), FakeTune.dead(), FakeTune.dead()) }
            f.up()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            advanceTimeBy(10.seconds)
            runCurrent()
            assertEquals(listOf("failed"), f.r.flipReports().map { it.outcome })
        }

    @Test
    fun aPressShowsTheNumberAtOnceWhateverTheLineupReadCosts() =
        runTest {
            val f = flipping()
            f.r.schedule.airingsDelayMs = 2_000
            f.up()
            assertTrue(f.r.state is LiveState.Choosing)
            // A commit refreshes the snapshot in the background, and the next press still needs no wait.
            advanceTimeBy(500.milliseconds)
            runCurrent()
            f.up()
            assertTrue(f.r.state is LiveState.Choosing)
        }

    @Test
    fun aCommitSupersededMidTuneLogsSuperseded() =
        runTest {
            val f = flipping { tune.script(itemOf(9), FakeTune.playsAfter(3.seconds)) }
            f.up()
            advanceTimeBy(1401.milliseconds)
            f.up()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            advanceTimeBy(2.seconds)
            val flips = f.r.flipReports()
            assertEquals(listOf("superseded", "playing"), flips.map { it.outcome })
            assertEquals(1801L, flips[0].elapsedMs)
            assertEquals(flips, f.r.live.flips)
        }

    @Test
    fun cancellingTheRunMidTuneLogsAbandoned() =
        runTest {
            val f = flipping { tune.script(itemOf(9), FakeTune.playsAfter(3.seconds)) }
            f.up()
            advanceTimeBy(1401.milliseconds)
            f.job.cancel()
            runCurrent()
            val flips = f.r.flipReports()
            assertEquals(listOf("abandoned"), flips.map { it.outcome })
            assertEquals(1401L, flips[0].elapsedMs)
        }

    @Test
    fun aSlateOutcomeCarriesTheElapsedTime() =
        runTest {
            val f = flipping { schedule.channels.getValue(CH9).blocks = listOf(slate(0, 600)) }
            f.up()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            val flip = f.r.flipReports().single()
            assertEquals("slate", flip.outcome)
            assertEquals(400L, flip.elapsedMs)
        }

    @Test
    fun aPressBetweenACommitAndTheNewSittingsFirstStateIsOverSnow() =
        runTest {
            val f = flipping()
            f.r.lastChannel.saveDelayMs = 300
            f.up()
            advanceTimeBy(401.milliseconds)
            // The old player has stopped and the new sitting has not written a state yet.
            f.up()
            assertEquals(LiveState.Choosing(ChannelTag(2, "Test Channel 2"), overPicture = false), f.r.state)
        }

    @Test
    fun aMenuPressDuringTheStopLogsEachFlipOnce() =
        runTest {
            val f = flipping()
            f.r.tune.stopDelayMs = 500
            f.up()
            advanceTimeBy(500.milliseconds)
            // The first commit is out of the queue and its sitting waits on the old player's stop.
            f.send(LiveCommand.Previous)
            advanceTimeBy(3.seconds)
            runCurrent()
            assertEquals(listOf("superseded", "playing"), f.r.flipReports().map { it.outcome })
            assertEquals(f.r.flipReports(), f.r.live.flips)
        }

    @Test
    fun aQueuedCommitReplacedBeforeItsSittingStartsLogsSupersededOnce() =
        runTest {
            val f = flipping()
            f.up()
            advanceTimeBy(1.seconds)
            // Two Menu presses in one go: the first commit is replaced before any sitting picks it up.
            f.keys.trySend(LiveCommand.Previous)
            f.keys.trySend(LiveCommand.Previous)
            runCurrent()
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(listOf("playing", "superseded", "playing"), f.r.flipReports().map { it.outcome })
        }

    @Test
    fun aQueuedCommitAtRunEndLogsAbandonedOnce() =
        runTest {
            val f = flipping()
            f.r.tune.stopDelayMs = 500
            f.up()
            advanceTimeBy(500.milliseconds)
            f.job.cancel()
            runCurrent()
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(listOf("abandoned"), f.r.flipReports().map { it.outcome })
        }

    @Test
    fun aPressShowsTheNumberBeforeAnUnwindingCommitHasStopped() =
        runTest {
            val f = flipping()
            f.r.schedule.onAirDelayMs = 1_000
            f.r.schedule.onAirUncancellable = true
            f.up()
            // The settle is now inside a lookup that will not stop at once.
            advanceTimeBy(500.milliseconds)
            f.up()
            assertEquals(ChannelTag(2, "Test Channel 2"), (f.r.state as LiveState.Choosing).target)
        }
}
