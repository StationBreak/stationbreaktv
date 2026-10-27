// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
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
 * read: one step at the press, a pause, then a steady rate (both [LiveTimings]), paced by the key events' own times.
 * Nothing is tuned while the key is down: the settle counts from the release, and a release that never comes is covered
 * by a fallback after the last down or repeat.
 */
class LiveChannelHeldFlipTest {
    private val repeatEveryMs = 50L

    private fun Flipping.choosingNumbers() =
        r.seen
            .filterIsInstance<LiveState.Choosing>()
            .map { it.target.number }

    private fun TestScope.at(ms: Long) {
        advanceTimeBy((ms - currentTime).milliseconds)
        runCurrent()
    }

    /** A key event of Up at [atMs] on the key clock: the press, or one of its auto-repeats. */
    private fun Flipping.upKey(
        atMs: Long,
        repeat: Boolean = false,
    ) = send(LiveCommand.Flip(FlipDirection.Up, repeat = repeat, atMs = atMs))

    /**
     * Holds Up from now for [holdMs]: the press, the first auto-repeat [firstRepeatMs] later, then one every 50 ms
     * (20 a second) until the last one before [holdMs]. With [release] the key comes up at [holdMs]. The key times
     * are the test clock's.
     */
    private fun TestScope.holdUp(
        f: Flipping,
        holdMs: Long,
        firstRepeatMs: Long = repeatEveryMs,
        release: Boolean = true,
    ) {
        val start = currentTime
        f.upKey(start)
        var at = firstRepeatMs
        while (at < holdMs) {
            at(start + at)
            f.upKey(start + at, repeat = true)
            at += repeatEveryMs
        }
        at(start + holdMs)
        if (release) f.send(LiveCommand.FlipReleased)
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
            holdUp(f, holdMs = 2_000, release = false)
            assertEquals(listOf(9, 10, 11, 12, 13, 14, 15, 16), f.choosingNumbers())
            assertEquals("nothing is tuned while the key is held", before, f.r.tunes.size)
            // The release: the settle runs out once, and the channel stopped on is tuned once.
            f.send(LiveCommand.FlipReleased)
            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            assertEquals(
                listOf(itemOf(16)),
                f.r.tunes
                    .drop(before)
                    .map { it.item },
            )
        }

    @Test
    fun thePauseAndTheRateAreTheTimingsOnes() =
        runTest {
            val f = manyChannels(LiveTimings(flipRepeatDelay = 600.milliseconds, flipRepeatEvery = 500.milliseconds))
            val before = f.r.tunes.size
            // The press at 0, then repeats to 1950 ms. Steps at 0, 600, 1100, 1600.
            holdUp(f, holdMs = 2_000, release = false)
            assertEquals(listOf(9, 10, 11, 12), f.choosingNumbers())
            assertEquals("nothing is tuned while the key is held", before, f.r.tunes.size)
            f.send(LiveCommand.FlipReleased)
            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            assertEquals(
                listOf(itemOf(12)),
                f.r.tunes
                    .drop(before)
                    .map { it.item },
            )
        }

    @Test
    fun aFirstRepeatLaterThanTheSettleTunesNothingUntilTheRelease() =
        runTest {
            // The Insignia: the first auto-repeat comes 480 ms after the press, past the 400 ms settle.
            val f = manyChannels()
            val before = f.r.tunes.size
            val start = currentTime
            holdUp(f, holdMs = 2_000, firstRepeatMs = 480, release = false)
            assertEquals("nothing is tuned while the key is held", before, f.r.tunes.size)
            f.send(LiveCommand.FlipReleased)
            // R55: the commit is at the later of the last repeat (1980 ms) plus the settle, and the release.
            at(start + 1_980 + 399)
            assertEquals(before, f.r.tunes.size)
            at(start + 1_980 + 401)
            assertEquals(1, f.r.tunes.size - before)
            at(start + 10_000)
            assertEquals(1, f.r.tunes.size - before)
        }

    @Test
    fun aLostReleaseCommitsOnceAfterTheFallbackFromTheLastRepeat() =
        runTest {
            val f = manyChannels()
            val before = f.r.tunes.size
            val start = currentTime
            // The last repeat at 950 ms; the release never comes.
            holdUp(f, holdMs = 1_000, release = false)
            val fallback = LiveTimings().flipReleaseFallback.inWholeMilliseconds
            at(start + 950 + fallback - 1)
            assertEquals(before, f.r.tunes.size)
            at(start + 950 + fallback + 1)
            assertEquals(1, f.r.tunes.size - before)
            at(start + 10_000)
            assertEquals(1, f.r.tunes.size - before)
        }

    @Test
    fun aShortTapStepsOnceAndTunesOnceTheSettleAfterItsPress() =
        runTest {
            // R55: a tap (up 100 ms after the press) commits at press + settle, as before #430, so a flip's time
            // measured from the press is not taxed by the tap's length.
            val f = manyChannels()
            val before = f.r.tunes.size
            val start = currentTime
            f.upKey(start)
            assertEquals(listOf(9), f.choosingNumbers())
            at(start + 100)
            f.send(LiveCommand.FlipReleased)
            at(start + 399)
            assertEquals(before, f.r.tunes.size)
            at(start + 401)
            assertEquals(
                listOf(itemOf(9)),
                f.r.tunes
                    .drop(before)
                    .map { it.item },
            )
            val flip =
                f.r.reports
                    .filterIsInstance<TuneReport.Flip>()
                    .single()
            assertEquals("settleMs counts from the last down", 400L, flip.settleMs)
        }

    @Test
    fun aPressHeldPastTheSettleCommitsAtItsRelease() =
        runTest {
            // Held 600 ms with no auto-repeat yet (a 700 ms first repeat): press + settle has passed while the key was
            // down, so the commit comes the moment it is let go, not before.
            val f = manyChannels()
            val before = f.r.tunes.size
            val start = currentTime
            f.upKey(start)
            at(start + 600)
            assertEquals("nothing is tuned while the key is down", before, f.r.tunes.size)
            f.send(LiveCommand.FlipReleased)
            assertEquals(
                listOf(itemOf(9)),
                f.r.tunes
                    .drop(before)
                    .map { it.item },
            )
            assertEquals(
                start + 600,
                f.r.tunes
                    .last()
                    .at,
            )
        }

    @Test
    fun aRepeatAfterAGapStepsOnceNotOncePerStepMissed() =
        runTest {
            // Repeats that stop for a while (a lost burst) and come back step once, not once per step missed. A long
            // fallback keeps the gap from committing.
            val f = manyChannels(LiveTimings(flipReleaseFallback = 5.seconds))
            val start = currentTime
            f.upKey(start)
            at(start + 1_500)
            f.upKey(start + 1_500, repeat = true)
            assertEquals(listOf(9, 10), f.choosingNumbers())
        }

    @Test
    fun theHeldPaceFollowsTheKeyTimesNotTheClock() =
        runTest {
            // The wall clock steps back a minute in the middle of the hold; the key events' uptime goes on.
            val f = manyChannels()
            f.r.jumpMs = 0
            val start = currentTime
            f.upKey(start)
            f.r.jumpMs = -60_000
            var at = 50L
            while (at < 1_000) {
                at(start + at)
                f.upKey(start + at, repeat = true)
                at += repeatEveryMs
            }
            // Steps at 0, 400, 650, 900.
            assertEquals(listOf(9, 10, 11, 12), f.choosingNumbers())
        }

    @Test
    fun aRepeatThatIsNotDueLeavesACommitInItsLookupAlone() =
        runTest {
            // The fallback ran out (the release was lost) and the commit is looking up its channel, slowly; a late
            // repeat of the same hold, not yet due a step, must not cancel that commit and start the wait again.
            val f = manyChannels(LiveTimings(flipRepeatDelay = 5.seconds))
            f.r.schedule.onAirDelayMs = 2_000
            val before = f.r.tunes.size
            val start = currentTime
            f.upKey(start)
            val fallback = LiveTimings().flipReleaseFallback.inWholeMilliseconds
            at(start + fallback + 100)
            f.upKey(start + fallback + 100, repeat = true)
            at(start + fallback + 6_000)
            assertEquals(1, f.r.tunes.size - before)
            assertEquals(
                itemOf(9),
                f.r.tunes
                    .last()
                    .item,
            )
            val superseded =
                f.r.reports
                    .filterIsInstance<TuneReport.Flip>()
                    .filter { it.outcome == "superseded" }
            assertEquals("the commit was never cancelled", emptyList<TuneReport.Flip>(), superseded)
        }
}
