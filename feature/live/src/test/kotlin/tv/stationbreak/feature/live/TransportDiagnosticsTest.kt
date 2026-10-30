// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program

/** The `SBLIVE seek` and `SBLIVE pause` lines (spec 20, Task 11): numbers and fixed labels only. */
@OptIn(ExperimentalCoroutinesApi::class) // The test clock's currentTime.
class TransportDiagnosticsTest {
    private val settleMs = LiveTimings().flipSettle.inWholeMilliseconds

    private class Keys(
        val r: Rig,
        val keys: Channel<LiveCommand>,
    ) {
        fun press(command: LiveCommand) {
            keys.trySend(command)
            r.scope.testScheduler.runCurrent()
        }

        val control get() = checkNotNull(r.tune.controlOf(itemOf(1)))

        /** The transport lines so far, as logged. */
        fun lines() = r.reports.map { it.line() }.filter { it.startsWith("seek ") || it.startsWith("pause ") }
    }

    /** CH7 with item 1 (minute 0 to 60) and item 2, joined at minute 10; item 1 plays by "transcode" (C16). */
    private suspend fun TestScope.keys(withNeighbour: Boolean = false): Keys {
        val r = Rig(this, startMin = 10)
        r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
        if (withNeighbour) {
            r.schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
            r.tune.preResolveResult = { false }
        }
        r.tune.script(itemOf(1), { FakeTune.plays(timings = TuneTimings(route = "transcode")) })
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        r.start(commands = keys.receiveAsFlow())
        return Keys(r, keys)
    }

    private fun back() = LiveCommand.Seek(SeekDirection.Back, repeat = false, atMs = null)

    private fun settleBack(k: Keys) {
        k.press(back())
        k.press(LiveCommand.SeekReleased)
        k.r.at(k.r.scope.testScheduler.currentTime + settleMs)
    }

    @Test
    fun aSettledBackLogsOneSeekLine() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            val live = k.control.positionMs()
            settleBack(k)
            assertEquals(listOf("seek dir=back targetMs=${live - 10_000} toPlayingMs=0 route=transcode"), k.lines())
        }

    @Test
    fun aSlowSeekLogsItsDelay() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.control.seekDelayMs = 700
            val live = k.control.positionMs()
            settleBack(k)
            k.r.at(k.r.scope.testScheduler.currentTime + 700)
            assertEquals(listOf("seek dir=back targetMs=${live - 10_000} toPlayingMs=700 route=transcode"), k.lines())
        }

    @Test
    fun goingLiveLogsDirLive() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(2)
            val live = TransportMath.liveMs(program(0, 60, 1), k.r.nowMs())
            k.press(LiveCommand.GoLive)
            val seeks = k.lines().filter { it.startsWith("seek") }
            assertEquals(listOf("seek dir=live targetMs=$live toPlayingMs=0 route=transcode"), seeks)
        }

    @Test
    fun aResumeLogsThePauseLine() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(11)
            k.press(LiveCommand.PlayPause)
            assertEquals(listOf("pause heldMs=600000 resumeMs=0"), k.lines())
        }

    @Test
    fun aPauseEndedByAFlipLogsResumeMinusOne() =
        runTest {
            val k = keys(withNeighbour = true)
            k.r.atMin(1)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(3)
            k.keys.trySend(LiveCommand.Flip(FlipDirection.Down))
            k.press(LiveCommand.FlipReleased)
            k.r.atMin(4)
            // The flip's tune settles flipSettle after the release, and ends the pause there.
            val pauses = k.lines().filter { it.startsWith("pause") }
            assertEquals(listOf("pause heldMs=${2 * MIN + settleMs} resumeMs=-1"), pauses)
        }

    @Test
    fun noLineCarriesAnIdOrTitle() =
        runTest {
            val k = keys()
            k.r.atMin(1)
            settleBack(k)
            k.press(LiveCommand.PlayPause)
            k.r.atMin(3)
            k.press(LiveCommand.PlayPause)
            k.press(LiveCommand.GoLive)
            val lines = k.lines()
            assertTrue(lines.size >= 3)
            val shape = Regex("^(seek|pause) [a-zA-Z]+=[-0-9a-z]+( [a-zA-Z]+=[-0-9a-z]+)*$")
            lines.forEach { assertTrue(it, shape.matches(it)) }
        }
}
