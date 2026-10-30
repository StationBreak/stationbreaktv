// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.playback.PlaybackEnd
import kotlin.time.Duration.Companion.minutes
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
    fun aPauseQueuedBehindAFlipIsDroppedAndTheChannelIsNotPausedOnReturn() =
        runTest {
            val f = flipping()
            // Both in one batch: the pause is taken in order, after the flip has put the number up.
            f.keys.trySend(LiveCommand.Flip(FlipDirection.Up))
            f.keys.trySend(LiveCommand.PlayPause)
            runCurrent()
            assertEquals(listOf("press dropped reason=transport-not-watching state=Choosing"), f.r.dropped())
            // Back down to the channel it started on, and let it settle.
            f.send(LiveCommand.Flip(FlipDirection.Down))
            f.send(LiveCommand.FlipReleased)
            advanceTimeBy(3.seconds)
            runCurrent()
            assertTrue(
                f.r.tune.controls
                    .none { (_, control) -> "pause" in control.calls },
            )
            assertNull(f.r.live.transport.value)
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

    @Test
    fun aTransportPressOnAPictureReachesTheSitting() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH7, 7, listOf(program(0, 600, 1)))
            val keys = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = keys.receiveAsFlow())
            advanceTimeBy(5.seconds)
            keys.trySend(LiveCommand.PlayPause)
            runCurrent()
            assertEquals(emptyList<String>(), r.dropped())
            assertEquals(listOf("pause"), checkNotNull(r.tune.lastControl).calls)
        }

    @Test
    fun pressesLeftInTheInboxAreDroppedAtTheNextTune() =
        runTest {
            val r = Rig(this)
            // Item 1 ends early: the ident holds to minute 60, and nothing takes presses meanwhile.
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
            r.tune.script(itemOf(1), r.tune.playsThenEnds(1.minutes, PlaybackEnd.Ended))
            r.start()
            r.atMin(2)
            assertTrue(r.state is LiveState.Ident)
            // Two presses already in the inbox, tagged for the next block (as a press racing its cut would be).
            val sitting = checkNotNull(r.live.currentSitting())
            repeat(2) { sitting.commands.offer(Tagged(sitting.sittingId, T0 + 60 * MIN, TransportOp.Toggle)) }
            r.atMin(51)
            assertEquals(listOf("press dropped reason=transport-at-tune n=2 state=Ident"), r.dropped())
            assertTrue(checkNotNull(r.tune.controlOf(itemOf(2))).calls.isEmpty())
        }

    @Test
    fun theHeldBlockIsClearedWhenACardShows() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1)))
            r.tune.script(itemOf(1), r.tune.playsThenEnds(1.minutes, PlaybackEnd.Ended))
            r.start()
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(T0, checkNotNull(r.live.currentSitting()).heldBlockStartMs)
            r.atMin(2)
            assertTrue(r.state is LiveState.Ident)
            assertNull(checkNotNull(r.live.currentSitting()).heldBlockStartMs)
        }

    @Test
    fun aPressTheInboxRefusesIsDroppedWithALine() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH7, 7, listOf(program(0, 600, 1)))
            val keys = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = keys.receiveAsFlow())
            advanceTimeBy(5.seconds)
            runCurrent()
            checkNotNull(r.live.currentSitting()).commands.close()
            keys.trySend(LiveCommand.PlayPause)
            runCurrent()
            assertEquals(listOf("press dropped reason=transport-refused state=Watching"), r.dropped())
            // A closed inbox means the sitting is ending: nothing breaks and nothing is tuned again meanwhile.
            advanceTimeBy(30.seconds)
            runCurrent()
            assertTrue(r.reports.none { it is TuneReport.Failed })
            assertEquals(1, r.tunes.size)
        }

    @Test
    fun anInboxClosedUnderTheLiveHoldBreaksNothing() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH7, 7, listOf(program(0, 600, 1)))
            r.start()
            advanceTimeBy(5.seconds)
            runCurrent()
            checkNotNull(r.live.currentSitting()).commands.close()
            advanceTimeBy(30.seconds)
            runCurrent()
            assertTrue(r.reports.none { it is TuneReport.Failed })
            assertEquals(1, r.tunes.size)
            assertTrue(r.state is LiveState.Watching)
        }

    @Test
    fun anInboxClosedUnderTheBehindPlayBreaksNothing() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH7, 7, listOf(program(0, 600, 1)))
            val keys = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = keys.receiveAsFlow())
            advanceTimeBy(5.seconds)
            keys.trySend(LiveCommand.PlayPause)
            runCurrent()
            checkNotNull(r.live.currentSitting()).commands.close()
            advanceTimeBy(30.seconds)
            runCurrent()
            assertTrue(r.reports.none { it is TuneReport.Failed })
            assertEquals(1, r.tunes.size)
            assertTrue(checkNotNull(r.live.transport.value).paused)
        }
}
