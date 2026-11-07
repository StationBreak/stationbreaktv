// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.playback.WarmReason
import tv.stationbreak.core.playback.WarmUse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The SBLIVE warm fields (spec 24): numbers and fixed names only. */
class TuneReportWarmTest {
    private val swapped = WarmUse(warm = true, reason = WarmReason.None, ageMs = 1_500, readyMs = 40)
    private val cold = WarmUse(warm = false, reason = WarmReason.WrongDirection, ageMs = 0, readyMs = 0)

    private fun tune(warm: WarmUse?) = TuneReport.Tune(TuneKind.Neighbour, 0, 250, 280, null, null, 0, warm = warm)

    private fun flip(warm: WarmUse?) = TuneReport.Flip(300, 320, 200, 320, "playing", "direct", null, warm = warm)

    @Test
    fun aSwappedTuneAndFlipLineSayWarmTrue() {
        val fields = "warm=true warmReason=none warmAgeMs=1500 warmReadyMs=40"
        assertTrue(tune(swapped).line().contains(fields))
        assertTrue(flip(swapped).line().contains(fields))
    }

    @Test
    fun aColdTuneAndFlipLineSayWhy() {
        val fields = "warm=false warmReason=wrongDirection warmAgeMs=0 warmReadyMs=0"
        assertTrue(tune(cold).line().contains(fields))
        assertTrue(flip(cold).line().contains(fields))
    }

    @Test
    fun aLineWithoutWarmInfoIsAsItWas() {
        assertFalse(tune(null).line().contains("warm"))
        assertFalse(flip(null).line().contains("warm"))
    }

    @Test
    fun theViaStaysLastOnAFlipLine() {
        val line = TuneReport.Flip(1, 2, 3, 4, "playing", null, null, warm = cold, via = "guide").line()
        assertTrue(line.endsWith(" via=guide"))
        assertTrue(line.contains("warmReadyMs=0 via=guide"))
    }

    @Test
    fun theMemoryLineIsNumbersOnly() {
        val line = TuneReport.Memory.now().line()
        assertTrue(line, Regex("""mem flip javaKb=\d+ javaMaxKb=\d+ nativeKb=\d+""").matches(line))
    }

    @Test
    fun aFlipThatPlayedReportsTheTunesWarmUseAndMemoryWithoutIds() =
        runTest {
            val r = Rig(this)
            val ch2 = ChannelId("ch-2")
            r.schedule.channel(ch2, 2, listOf(program(0, 600, 2)))
            r.schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
            r.schedule.channel(CH9, 9, listOf(program(0, 600, 9)))
            r.lastChannel.save(r.account, CH5)
            val keys = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = keys.receiveAsFlow())
            advanceTimeBy(1.seconds)
            runCurrent()
            r.tune.script(itemOf(9), { FakeTune.plays(timings = TuneTimings(firstFrameMs = 20, warm = swapped)) })
            keys.trySend(LiveCommand.Flip(FlipDirection.Up))
            keys.trySend(LiveCommand.FlipReleased)
            runCurrent()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            advanceTimeBy(1.seconds)
            runCurrent()
            val lines = r.reports.map { it.line() }
            val flipLine = lines.single { it.startsWith("flip ") }
            val tuneLine = lines.last { it.startsWith("tune kind=neighbour") }
            assertTrue(flipLine, flipLine.contains("warm=true warmReason=none warmAgeMs=1500 warmReadyMs=40"))
            assertTrue(tuneLine, tuneLine.contains("warm=true warmReason=none"))
            assertTrue(lines.any { it.startsWith("mem flip javaKb=") })
            lines.forEach { line -> assertFalse(line, Regex("ch-|item|secret|http|/").containsMatchIn(line)) }
            assertEquals(1, lines.count { it.startsWith("mem flip") })
        }
}
