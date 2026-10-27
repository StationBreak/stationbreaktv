// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
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
}
