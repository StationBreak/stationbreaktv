// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Pause and Play on a live channel (spec 20, ADR-0021 3, 5 and 9): the behind play holds on media time. */
class BehindPauseTest {
    private val facts = ReportFacts(MediaSourceId("src"), PlaySessionId("ps"), PlayMethod.DirectPlay, 60.minutes)

    private class Keys(
        val r: Rig,
        val keys: Channel<LiveCommand>,
    ) {
        fun press(command: LiveCommand) {
            keys.trySend(command)
            r.scope.testScheduler.runCurrent()
        }

        val control get() = checkNotNull(r.tune.controlOf(itemOf(1)))
    }

    /** CH7 with item 1 from minute 0 to 60 and item 2 to 120 (plus [more]), joined at minute 10. */
    private suspend fun TestScope.keys(
        server: MediaServer? = null,
        vararg more: AiringBlock,
    ): Keys {
        val r = Rig(this, startMin = 10, server = server)
        if (server != null) r.tune.reportFacts = facts
        r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)) + more)
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        r.start(commands = keys.receiveAsFlow())
        return Keys(r, keys)
    }

    private fun Rig.tuneCount() = tune.calls.count { it is Call.Tune || it is Call.TuneHandle }

    @Test
    fun pauseHoldsThePictureAndNothingIsTuned() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            val before = k.r.tune.calls.size
            k.press(LiveCommand.PlayPause)
            k.r.atMin(5)
            assertEquals(listOf("pause"), k.control.calls)
            val after =
                k.r.tune.calls
                    .drop(before)
            assertTrue(after.none { it is Call.Tune || it is Call.TuneHandle || it is Call.Stop })
            assertTrue(checkNotNull(k.r.live.transport.value).paused)
        }

    @Test
    fun playCarriesOnFromWhereItPaused() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(11)
            k.press(LiveCommand.PlayPause)
            assertEquals(listOf("pause", "resume"), k.control.calls)
            val view = checkNotNull(k.r.live.transport.value)
            assertFalse(view.paused)
            assertEquals(10 * MIN, view.behindMs(k.r.nowMs()))
            // Still ten minutes behind later on: the picture plays on from where it paused.
            k.r.atMin(20)
            assertEquals(10 * MIN, checkNotNull(k.r.live.transport.value).behindMs(k.r.nowMs()))
            assertEquals(1, k.r.tuneCount())
        }

    @Test
    fun twoQuickTogglesPauseThenPlay() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.keys.trySend(LiveCommand.PlayPause)
            k.r.at(MIN + 50)
            k.press(LiveCommand.PlayPause)
            k.r.at(MIN + 100)
            assertEquals(listOf("pause", "resume"), k.control.calls)
            assertFalse(checkNotNull(k.r.live.transport.value).paused)
        }

    @Test
    fun theHoldRunsOnMediaTime() =
        runTest {
            val k = keys(null, program(120, 180, 3))
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            // Paused for an hour: item 1's block (to minute 60) and item 2's (to 120) end meanwhile.
            k.r.atMin(61)
            assertEquals(1, k.r.tuneCount())
            // The screen starts on the ident before the first tune; none since the picture came up.
            assertTrue(
                k.r.seen
                    .dropWhile { it !is LiveState.Watching }
                    .none { it is LiveState.Ident },
            )
            assertTrue(checkNotNull(k.r.live.transport.value).paused)
            k.press(LiveCommand.PlayPause)
            // Paused at media 11 min of a 60 min cut: 49 minutes of media are left to play.
            k.r.at(61 * MIN + 49 * MIN - 1_000)
            assertEquals(1, k.r.tuneCount())
            k.r.at(61 * MIN + 49 * MIN + 1_000)
            assertEquals(2, k.r.tuneCount())
            // Wall minute 120 (10 + 110): what is on now is item 3, from its start.
            val next = k.r.tunes.last()
            assertEquals(itemOf(3), next.item)
            assertEquals(0L, next.start.inWholeMilliseconds)
            assertNull(k.r.live.transport.value)
        }

    @Test
    fun aShortFiringHoldReArms() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(11)
            // The media now runs slower than the clock: 49 minutes of it take about 54.4 minutes.
            k.control.rate = 0.9
            k.press(LiveCommand.PlayPause)
            k.r.atMin(11 + 54)
            assertEquals(1, k.r.tuneCount())
            assertTrue(k.control.positionMs() < 60 * MIN)
            k.r.atMin(11 + 55)
            assertEquals(2, k.r.tuneCount())
            assertEquals(
                itemOf(2),
                k.r.tunes
                    .last()
                    .item,
            )
        }

    @Test
    fun anUnknownPositionKeepsTheLastKnownPlace() =
        runTest {
            val k = keys()
            // The player cannot say where it is from before the pause: the place is the live position it paused at.
            k.control.positionUnknown = true
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(2)
            val behind = checkNotNull(checkNotNull(k.r.live.currentSitting()).behind)
            val place = checkNotNull(behind.pausedPlace())
            assertEquals(11 * MIN, place.mediaMs)
            assertEquals(CH7, place.channel)
        }

    @Test
    fun anUnknownPositionAfterPlayStillReachesTheCutOnTime() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(11)
            k.press(LiveCommand.PlayPause)
            k.control.positionUnknown = true
            val reads = k.control.positionReads
            // 49 minutes of media left from minute 11: the cut is at minute 60, worked out from the last known place.
            k.r.at(60 * MIN - 1_000)
            assertEquals(1, k.r.tuneCount())
            k.r.at(60 * MIN + 1_000)
            assertEquals(2, k.r.tuneCount())
            // No spin: a handful of reads, not one per millisecond near the cut.
            assertTrue(k.control.positionReads - reads < 10)
        }

    @Test
    fun aSlowHoldNearTheCutDoesNotSpin() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(11)
            k.control.rate = 0.9
            val reads = k.control.positionReads
            k.press(LiveCommand.PlayPause)
            k.r.atMin(11 + 55)
            assertEquals(2, k.r.tuneCount())
            assertTrue("reads=${k.control.positionReads - reads}", k.control.positionReads - reads < 20)
        }

    @Test
    fun aPressJustBeforeTheCutPausesTheItemOnScreen() =
        runTest {
            val k = keys()
            k.r.tune.script(itemOf(2), FakeTune.playsAfter(500.milliseconds))
            k.r.at(50 * MIN - 10)
            k.press(LiveCommand.PlayPause)
            k.r.at(50 * MIN + 1_000)
            // It acted on the item whose picture was up, never on the next one: that is not tuned while paused.
            assertEquals(listOf("pause"), k.control.calls)
            assertNull(k.r.tune.controlOf(itemOf(2)))
            assertTrue(checkNotNull(k.r.live.transport.value).paused)
        }

    @Test
    fun aPressJustBeforeABoundaryDoesNotActOnTheNextItem() =
        runTest {
            val k = keys()
            k.r.tune.script(itemOf(2), FakeTune.playsAfter(500.milliseconds))
            // The cut to item 2 is under way (a straight cut: item 1's picture is still up) when the press arrives.
            k.r.at(50 * MIN + 10)
            assertTrue(k.r.state is LiveState.Watching)
            k.press(LiveCommand.PlayPause)
            k.r.at(50 * MIN + 2_000)
            val next = checkNotNull(k.r.tune.controlOf(itemOf(2)))
            assertTrue(next.calls.none { it == "pause" })
            assertTrue(k.control.calls.isEmpty())
            assertNull(k.r.live.transport.value)
        }

    @Test
    fun pausedReportingGoesOutWhileBehind() =
        runTest {
            val server = RecordingServer({ currentTime })
            val k = keys(server)
            k.r.at(31_000)
            assertEquals(1, server.calls.count { it.kind == "start" })
            k.press(LiveCommand.PlayPause)
            k.r.at(200_000)
            val start = server.calls.single { it.kind == "start" }
            val progress = server.calls.filter { it.kind == "progress" }
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.all { it.paused })
            // Paused check-ins stay at the safe position, never the real one.
            assertTrue(progress.all { it.posMs == start.posMs })
            k.press(LiveCommand.PlayPause)
            k.r.at(260_000)
            // Play within pauseReportMax: no second Playing, and the check-ins say playing again.
            val after = server.calls.drop(server.calls.indexOf(progress.last()) + 1)
            assertEquals(1, server.calls.count { it.kind == "start" })
            assertEquals(listOf(210_000L, 240_000L), after.filter { it.kind == "progress" }.map { it.atMs })
            assertTrue(after.all { it.kind == "progress" && !it.paused && it.posMs == start.posMs })
        }
}
