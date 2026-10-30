// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.feature.live.report.RecordingServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Back and Ahead on a live channel (spec 20, ADR-0021 2, 3 and 9): steps add up, and one seek settles them. */
class BehindStepTest {
    private val timings = LiveTimings()

    private class Keys(
        val r: Rig,
        val keys: Channel<LiveCommand>,
    ) {
        fun press(command: LiveCommand) {
            keys.trySend(command)
            r.scope.testScheduler.runCurrent()
        }

        val control get() = checkNotNull(r.tune.controlOf(itemOf(1)))

        /** The media position live on item 1 now (its block starts at minute 0 with media 0). */
        fun liveMs(): Long = control.positionMs()
    }

    /** CH7 with [blocks] (item 1 from minute 0 to 60 and item 2 to 120 by default), joined at [startMin]. */
    private suspend fun TestScope.keys(
        server: MediaServer? = null,
        startMin: Long = 10,
        blocks: List<AiringBlock> = listOf(program(0, 60, 1), program(60, 120, 2)),
        facts: ReportFacts? = null,
    ): Keys {
        val r = Rig(this, startMin = startMin, server = server)
        r.tune.reportFacts = facts
        r.schedule.channel(CH7, 7, blocks)
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        r.start(commands = keys.receiveAsFlow())
        return Keys(r, keys)
    }

    private fun back(
        repeat: Boolean = false,
        atMs: Long? = null,
    ) = LiveCommand.Seek(SeekDirection.Back, repeat, atMs)

    private fun ahead(
        repeat: Boolean = false,
        atMs: Long? = null,
    ) = LiveCommand.Seek(SeekDirection.Ahead, repeat, atMs)

    private fun Rig.tuneCount() = tune.calls.count { it is Call.Tune || it is Call.TuneHandle }

    @Test
    fun backStepsTenSeconds() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            val live = k.liveMs()
            k.press(back())
            k.press(LiveCommand.SeekReleased)
            assertEquals(listOf("seek:${live - 10_000}"), k.control.calls)
            assertTrue(checkNotNull(k.r.live.transport.value).visible)
        }

    @Test
    fun heldBackSeeksOnceOnRelease() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            val live = k.liveMs()
            val pressAt = MIN
            k.press(back(atMs = pressAt))
            for (i in 1..8) {
                k.r.at(pressAt + i * 50L)
                k.press(back(repeat = true, atMs = pressAt + i * 50L))
            }
            assertTrue(k.control.calls.isEmpty())
            k.press(LiveCommand.SeekReleased)
            // The held pace is the flip's.
            val pace = HeldFlip(timings.flipRepeatDelay, timings.flipRepeatEvery)
            pace.press(0)
            val due = (1..8).count { pace.repeatSteps(it * 50L) }
            assertEquals(listOf("seek:${live - 10_000L * (1 + due)}"), k.control.calls)
        }

    @Test
    fun stepsSettleWithoutARelease() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            val live = k.liveMs()
            k.press(back())
            k.r.at(MIN + timings.flipSettle.inWholeMilliseconds - 1)
            assertTrue(k.control.calls.isEmpty())
            k.r.at(MIN + timings.flipSettle.inWholeMilliseconds)
            assertEquals(listOf("seek:${live - 10_000}"), k.control.calls)
            // Settled once: no second seek later.
            k.r.at(MIN + 10_000)
            assertEquals(1, k.control.calls.size)
        }

    @Test
    fun backReachesTheItemStartEvenIfJoinedHalfway() =
        runTest {
            // Joined at minute 10 of item 1: Back reaches its start, not just the join point.
            val k = keys()
            k.r.atMin(1)
            repeat(70) { k.press(back()) }
            k.press(LiveCommand.SeekReleased)
            assertEquals(listOf("seek:0"), k.control.calls)
        }

    @Test
    fun aheadCapsAtLiveAndIsLiveAgain() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(back())
            k.press(back())
            k.press(LiveCommand.SeekReleased)
            k.r.at(MIN + 1_000)
            val live = TransportMath.liveMs(program(0, 60, 1), k.r.nowMs())
            k.press(ahead())
            k.press(LiveCommand.SeekReleased)
            assertEquals(listOf("seek:${live - 21_000}", "seek:$live"), k.control.calls)
            assertNull(k.r.live.transport.value)
            // Live again: Play/Pause pauses the same play, nothing is tuned.
            k.press(LiveCommand.PlayPause)
            assertEquals("pause", k.control.calls.last())
            assertEquals(1, k.r.tuneCount())
            assertTrue(checkNotNull(k.r.live.transport.value).paused)
        }

    @Test
    fun aheadWhileLiveDoesNothing() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(ahead(atMs = MIN))
            for (i in 1..8) {
                k.r.at(MIN + i * 50L)
                k.press(ahead(repeat = true, atMs = MIN + i * 50L))
            }
            k.press(LiveCommand.SeekReleased)
            k.r.at(MIN + 2_000)
            assertTrue(k.control.calls.isEmpty())
            assertNull(k.r.live.transport.value)
        }

    @Test
    fun aStepWhilePausedStaysPaused() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            val live = k.liveMs()
            k.press(LiveCommand.PlayPause)
            k.r.at(MIN + 1_000)
            k.press(back())
            k.press(LiveCommand.SeekReleased)
            k.r.at(MIN + 5_000)
            assertEquals(listOf("pause", "seek:${live - 10_000}"), k.control.calls)
            val view = checkNotNull(k.r.live.transport.value)
            assertTrue(view.paused)
            assertEquals(live - 10_000, view.mediaMs)
        }

    @Test
    fun stepsDoNothingOnANonSeekablePlay() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.control.seekableValue = false
            k.press(back())
            k.press(LiveCommand.SeekReleased)
            k.r.at(MIN + 1_000)
            assertTrue(k.control.calls.isEmpty())
            assertNull(k.r.live.transport.value)
            k.press(LiveCommand.PlayPause)
            // Paused (so behind), a step still does nothing.
            k.press(back())
            k.press(LiveCommand.SeekReleased)
            k.r.at(MIN + 2_000)
            assertEquals(listOf("pause"), k.control.calls)
        }

    @Test
    fun goLiveFromBehindSeeksToLiveAndPlays() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(2)
            val live = TransportMath.liveMs(program(0, 60, 1), k.r.nowMs())
            k.press(LiveCommand.GoLive)
            assertEquals(listOf("pause", "seek:$live", "resume"), k.control.calls)
            assertNull(k.r.live.transport.value)
            assertEquals(1, k.r.tuneCount())
        }

    /**
     * Joined at the start of a 10-minute item, paused a minute, then 30 s skipped ahead with a seek that takes 2 s:
     * 90 s played before the seek, then from 120 s to the cut at 600 s, which the player reaches at 630 s. It runs on
     * during the seek, but the ledger counts only from the seek's done position: 568 s watched. Had the 2 s of the seek
     * counted, it would be 570 s.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // The test clock's currentTime, for the server's call times.
    private fun markedPlayedWith(runtime: Duration): Boolean {
        var marked = false
        runTest {
            val server = RecordingServer({ currentTime })
            val facts = ReportFacts(MediaSourceId("src"), PlaySessionId("ps"), PlayMethod.DirectPlay, runtime)
            val k = keys(server, startMin = 0, blocks = listOf(program(0, 10, 1), program(10, 60, 2)), facts = facts)
            k.r.at(60_000)
            k.press(LiveCommand.PlayPause)
            k.r.at(120_000)
            k.press(LiveCommand.PlayPause)
            k.r.at(150_000)
            k.control.seekDelayMs = 2_000
            k.press(ahead())
            k.press(LiveCommand.SeekReleased)
            assertEquals("seek:120000", k.control.calls.last())
            k.r.at(700_000)
            assertEquals(2, k.r.tuneCount())
            marked = server.calls.any { it.kind == "played" && it.item == itemOf(1).value }
        }
        return marked
    }

    @Test
    fun aSeekReportsSeekingThenSeekedAtTheDonePosition() {
        // 90% of 632 s is 568.8 s: over what was watched, under it had the seek's 2 s counted.
        assertTrue(!markedPlayedWith(632.seconds))
        // 90% of 630 s is 567 s: what was watched reaches it.
        assertTrue(markedPlayedWith(630.seconds))
    }
}
