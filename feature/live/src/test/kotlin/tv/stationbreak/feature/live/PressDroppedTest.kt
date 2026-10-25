// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.live.ui.drainStalePresses
import tv.stationbreak.feature.live.ui.keyLogLine
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

    private fun Rig.dropped() = reports.filterIsInstance<TuneReport.PressDropped>().map { it.reason }

    @Test
    fun aMenuWithNoPreviousWritesOneMenuReason() =
        runTest {
            val f = flipping()
            f.send(LiveCommand.Previous)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(listOf("menu-no-previous"), f.r.dropped())
        }

    @Test
    fun aMenuClearsQueuedPressesAndEachWritesALine() =
        runTest {
            val f = flipping { schedule.airingsFailOn = setOf(2, 5) }
            f.up()
            advanceTimeBy(2.seconds)
            runCurrent()
            f.r.schedule.airingsDelayMs = 5_000
            f.up()
            advanceTimeBy(1.seconds)
            f.send(LiveCommand.Previous)
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(listOf("superseded-by-menu"), f.r.dropped())
        }

    @Test
    fun aPressCancelsAPendingMenuAndWritesALine() =
        runTest {
            val f = flipping()
            f.up()
            advanceTimeBy(2.seconds)
            runCurrent()
            f.r.schedule.onAirDelayMs = 5_000
            f.r.schedule.onAirUncancellable = true
            f.send(LiveCommand.Previous)
            advanceTimeBy(1.seconds)
            f.up()
            advanceTimeBy(3.seconds)
            runCurrent()
            assertTrue(f.r.dropped().toString(), "menu-superseded" in f.r.dropped())
        }

    @Test
    fun presentsDrainedAtASittingWriteOneLineWithTheCount() {
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        val lines = mutableListOf<String>()
        drainStalePresses(keys, "Watching", false, lines::add)
        assertTrue("nothing to drain, nothing logged", lines.isEmpty())
        repeat(3) { keys.trySend(LiveCommand.Previous) }
        drainStalePresses(keys, "Watching", false, lines::add)
        assertEquals(listOf("press dropped reason=drained-at-sit n=3 state=Watching"), lines)
        keys.trySend(LiveCommand.Previous)
        drainStalePresses(keys, "Ident", true, lines::add)
        assertEquals("press dropped reason=no-session n=1 state=Ident", lines.last())
    }

    @Test
    fun onlyAMappedKeysDownIsLogged() {
        val state = LiveState.NoChannels
        assertEquals(
            "key code=19 action=down repeat=0 handled=true state=NoChannels",
            keyLogLine(KeyEventType.KeyDown, Key.DirectionUp, 19, 0, true, state),
        )
        assertNull(keyLogLine(KeyEventType.KeyUp, Key.DirectionUp, 19, 0, true, state))
        assertNull(keyLogLine(KeyEventType.KeyDown, Key.Back, 4, 0, false, state))
    }
}
