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
    private val settleMs = timings.flipSettle.inWholeMilliseconds

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
            // A tap settles flipSettle after its press, as a flip does.
            k.r.at(MIN + settleMs - 1)
            assertTrue(k.control.calls.isEmpty())
            k.r.at(MIN + settleMs)
            assertEquals(listOf("seek:${live - 10_000}"), k.control.calls)
            assertTrue(checkNotNull(k.r.live.transport.value).visible)
        }

    @Test
    fun quickTapsAddUpToOneSeek() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            val live = k.liveMs()
            for (i in 0..2) {
                k.r.at(MIN + i * 100L)
                k.press(back(atMs = MIN + i * 100L))
                k.press(LiveCommand.SeekReleased)
            }
            k.r.at(MIN + 5_000)
            assertEquals(listOf("seek:${live - 30_000}"), k.control.calls)
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
            k.r.at(MIN + 5_000)
            // The held pace is the flip's.
            val pace = HeldFlip(timings.flipRepeatDelay, timings.flipRepeatEvery)
            pace.press(0)
            val due = (1..8).count { pace.repeatSteps(it * 50L) }
            assertEquals(listOf("seek:${live - 10_000L * (1 + due)}"), k.control.calls)
        }

    @Test
    fun aHeldStepWithTheInsigniasFirstRepeatSeeksOnce() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            val live = k.liveMs()
            k.press(back(atMs = MIN))
            // The first auto-repeat 480 ms after the press (past flipSettle), then one every 250 ms.
            val repeats = (0..6).map { 480L + it * 250L }
            for (offset in repeats) {
                k.r.at(MIN + offset)
                k.press(back(repeat = true, atMs = MIN + offset))
            }
            k.press(LiveCommand.SeekReleased)
            k.r.at(MIN + 5_000)
            val pace = HeldFlip(timings.flipRepeatDelay, timings.flipRepeatEvery)
            pace.press(0)
            val due = repeats.count { pace.repeatSteps(it) }
            assertEquals(listOf("seek:${live - 10_000L * (1 + due)}"), k.control.calls)
        }

    @Test
    fun stepsSettleWithoutARelease() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            val live = k.liveMs()
            k.press(back())
            // The key may still be down: nothing settles until the release fallback.
            val fallbackMs = timings.flipReleaseFallback.inWholeMilliseconds
            k.r.at(MIN + fallbackMs - 1)
            assertTrue(k.control.calls.isEmpty())
            k.r.at(MIN + fallbackMs)
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
            k.r.at(MIN + settleMs)
            assertEquals(listOf("seek:0"), k.control.calls)
        }

    @Test
    fun aheadCapsAtLiveAndIsLiveAgain() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            val behindFrom = k.liveMs()
            k.press(back())
            k.press(back())
            k.press(LiveCommand.SeekReleased)
            k.r.at(MIN + 1_000)
            k.press(ahead())
            k.press(LiveCommand.SeekReleased)
            k.r.at(MIN + 1_000 + settleMs)
            val live = TransportMath.liveMs(program(0, 60, 1), k.r.nowMs())
            assertEquals(listOf("seek:${behindFrom - 20_000}", "seek:$live"), k.control.calls)
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

    @Test
    fun aStaleSeekDoneDoesNotEndTheWayBackToLive() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(back())
            k.press(LiveCommand.SeekReleased)
            k.r.at(MIN + settleMs)
            val earlier = k.control.positionMs()
            // The earlier seek's SeekDone is still buffered when the live seek goes out, and that one takes 2 s.
            k.control.staleDoneMs = earlier
            k.control.seekDelayMs = 2_000
            k.r.at(MIN + 1_000)
            k.press(LiveCommand.GoLive)
            k.r.at(MIN + 2_000)
            val sitting = checkNotNull(k.r.live.currentSitting())
            assertTrue("still waiting for the live seek", sitting.behind != null)
            k.r.at(MIN + 3_100)
            assertNull(sitting.behind)
            assertNull(k.r.live.transport.value)
        }

    @Test
    fun aCoalescedLiveSeekLandsOnItsOwnSeekDone() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(2)
            // The player folds the live seek into one already pending: no Seeking, only the one SeekDone.
            k.control.coalesces = true
            k.control.seekDelayMs = 500
            k.press(LiveCommand.GoLive)
            val sitting = checkNotNull(k.r.live.currentSitting())
            assertTrue(sitting.behind != null)
            k.r.at(2 * MIN + 600)
            assertNull(sitting.behind)
        }

    @Test
    fun aTargetNotYetSoughtIsLoggedWhenTheCutComes() =
        runTest {
            val k = keys()
            // Paused 100 ms of media before the cut, then played: the cut comes 100 ms later.
            k.r.at(50 * MIN - 100)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(51)
            k.press(LiveCommand.PlayPause)
            k.r.at(51 * MIN + 50)
            // Held down: no settle before the cut.
            k.press(back())
            k.r.at(51 * MIN + 200)
            assertEquals(2, k.r.tuneCount())
            assertTrue(k.control.calls.none { it.startsWith("seek") })
            val dropped =
                k.r.reports
                    .filterIsInstance<TuneReport.PressDropped>()
                    .map { it.line() }
            assertTrue(dropped.toString(), dropped.any { "reason=transport-lost-at-cut" in it })
        }

    /**
     * Joined at the start of a 10-minute item, paused a minute, then 30 s skipped ahead (settled 400 ms after the tap)
     * with a seek that takes 2 s: 90.4 s played before the seek, then from 120 s to the cut at 600 s, which the player
     * reaches at 630.4 s. It runs on during the seek, but the ledger counts only from the seek's done position: 568.4 s
     * watched. Had the 2 s of the seek counted, it would be 570.4 s.
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
            k.r.at(150_000 + settleMs)
            assertEquals("seek:120000", k.control.calls.last())
            k.r.at(700_000)
            assertEquals(2, k.r.tuneCount())
            marked = server.calls.any { it.kind == "played" && it.item == itemOf(1).value }
        }
        return marked
    }

    @Test
    fun aSeekReportsSeekingThenSeekedAtTheDonePosition() {
        // 90% of 633 s is 569.7 s: over what was watched, under it had the seek's 2 s counted.
        assertTrue(!markedPlayedWith(633.seconds))
        // 90% of 630 s is 567 s: what was watched reaches it.
        assertTrue(markedPlayedWith(630.seconds))
    }
}
