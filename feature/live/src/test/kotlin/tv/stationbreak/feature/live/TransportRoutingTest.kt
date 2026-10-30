// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import kotlin.time.Duration.Companion.seconds

/** A transport press acts only on a picture that plays; on snow, a number or a card it is dropped with a line. */
class TransportRoutingTest {
    private fun Rig.dropped() = reports.filterIsInstance<TuneReport.PressDropped>().map { it.line() }

    @Test
    fun aTransportPressWhileTuningIsDropped() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH7, 7, listOf(program(0, 600, 1)))
            r.tune.script(itemOf(1), FakeTune.playsAfter(5.seconds))
            val keys = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = keys.receiveAsFlow())
            advanceTimeBy(1.seconds)
            keys.trySend(LiveCommand.PlayPause)
            runCurrent()
            assertEquals(listOf("press dropped reason=transport-not-watching state=Tuning"), r.dropped())
        }

    @Test
    fun aTransportPressWhileChoosingIsDropped() =
        runTest {
            val f = flipping()
            // A press of Up without its release: the number is up over the picture.
            f.send(LiveCommand.Flip(FlipDirection.Up))
            f.send(LiveCommand.Pause)
            assertEquals(listOf("press dropped reason=transport-not-watching state=Choosing"), f.r.dropped())
        }

    @Test
    fun aTransportPressOnTheIdentIsDropped() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH7, 7, listOf(slate(0, 600)))
            val keys = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = keys.receiveAsFlow())
            advanceTimeBy(1.seconds)
            keys.trySend(LiveCommand.GoLive)
            runCurrent()
            assertEquals(listOf("press dropped reason=transport-not-watching state=Ident"), r.dropped())
        }
}
