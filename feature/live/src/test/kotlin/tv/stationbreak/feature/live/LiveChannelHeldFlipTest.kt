// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A held Up or Down (#430): the remote repeats about 20 times a second, but the number moves at a rate the viewer can
 * read: one step at the press, a pause, then a steady rate (both [LiveTimings]). The hold commits once, after the
 * release, as a single press does.
 */
class LiveChannelHeldFlipTest {
    private val repeatEveryMs = 50L

    private fun Flipping.choosingNumbers() =
        r.seen
            .filterIsInstance<LiveState.Choosing>()
            .map { it.target.number }

    /**
     * Holds Up for [holdMs]: the press, then an auto-repeat every 50 ms (20 a second) until the last one before
     * [holdMs], then the release (which sends nothing).
     */
    private fun TestScope.holdUp(
        f: Flipping,
        holdMs: Long,
    ) {
        f.up()
        var at = repeatEveryMs
        while (at < holdMs) {
            advanceTimeBy(repeatEveryMs.milliseconds)
            runCurrent()
            f.send(LiveCommand.Flip(FlipDirection.Up, repeat = true))
            at += repeatEveryMs
        }
    }

    private suspend fun TestScope.manyChannels(timings: LiveTimings = LiveTimings()): Flipping =
        // Channel 5 and the ones above it: 2 and 9 from the rig, and 20 more so a long hold never wraps.
        flipping(startOn = CH5, timings = timings) {
            (10..29).forEach { n ->
                schedule.channel(ChannelId("ch-$n"), n, listOf(program(0, 600, n)))
            }
        }

    @Test
    fun aHoldAt20RepeatsASecondStepsAtOnceThenAfterThePauseThenAtTheRate() =
        runTest {
            val f = manyChannels()
            val before = f.r.tunes.size
            // 2 s: the press at 0, then repeats at 50..1950 ms. Steps at 0, 400, 650, 900, 1150, 1400, 1650, 1900.
            holdUp(f, holdMs = 2_000)
            assertEquals(listOf(9, 10, 11, 12, 13, 14, 15, 16), f.choosingNumbers())
            assertEquals("nothing is tuned while the key is held", before, f.r.tunes.size)
            // The release: the settle runs out once, and the channel stopped on is tuned once.
            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            val tunes = f.r.tunes.drop(before)
            assertEquals(1, tunes.size)
            assertEquals(itemOf(16), tunes.single().item)
        }

    @Test
    fun thePauseAndTheRateAreTheTimingsOnes() =
        runTest {
            val f = manyChannels(LiveTimings(flipRepeatDelay = 600.milliseconds, flipRepeatEvery = 500.milliseconds))
            // The press at 0, then repeats to 1950 ms. Steps at 0, 600, 1100, 1600.
            holdUp(f, holdMs = 2_000)
            assertEquals(listOf(9, 10, 11, 12), f.choosingNumbers())
        }

    @Test
    fun aRepeatAfterAGapStepsOnceNotOncePerStepMissed() =
        runTest {
            // Repeats that stop for a while (a lost burst) and come back step once, not once per step missed. A long
            // settle keeps the gap from committing.
            val f = manyChannels(LiveTimings(flipSettle = 5.seconds))
            f.up()
            advanceTimeBy(1_500.milliseconds)
            runCurrent()
            f.send(LiveCommand.Flip(FlipDirection.Up, repeat = true))
            assertEquals(listOf(9, 10), f.choosingNumbers())
        }

    @Test
    fun aShortTapStillStepsOnceAndTunesOnce() =
        runTest {
            val f = manyChannels()
            val before = f.r.tunes.size
            f.up()
            assertEquals(listOf(9), f.choosingNumbers())
            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            assertEquals(
                listOf(itemOf(9)),
                f.r.tunes
                    .drop(before)
                    .map { it.item },
            )
        }
}
