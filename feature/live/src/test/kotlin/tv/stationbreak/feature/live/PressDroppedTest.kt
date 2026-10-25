// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.live.ui.liveKeyLine
import kotlin.time.Duration.Companion.seconds

/** A press the live channel does not act on leaves a line saying why (#392). */
class PressDroppedTest {
    @Test
    fun aPressWithNothingOnTheAirWritesTheDroppedLine() =
        runTest {
            val r = Rig(this)
            val keys = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = keys.receiveAsFlow())
            keys.trySend(LiveCommand.Flip(FlipDirection.Up))
            runCurrent()
            val dropped = r.reports.filterIsInstance<TuneReport.PressDropped>()
            assertEquals(listOf("press dropped reason=off-screen state=NoChannels"), dropped.map { it.line() })
        }

    @Test
    fun aCommittedPressWritesNoDroppedLine() =
        runTest {
            val f = flipping()
            f.up()
            advanceTimeBy(2.seconds)
            runCurrent()
            assertTrue(f.r.reports.any { it is TuneReport.Flip })
            assertTrue(f.r.reports.none { it is TuneReport.PressDropped })
        }

    @Test
    fun theKeyLineIsNumbersAndLabelsOnly() {
        assertEquals(
            "key code=19 action=down repeat=2 handled=true state=Watching",
            liveKeyLine(19, "down", 2, true, LiveState.Watching(null)),
        )
        assertEquals(
            "key code=82 action=down repeat=0 handled=false state=NoChannels",
            liveKeyLine(82, "down", 0, false, LiveState.NoChannels),
        )
    }
}
