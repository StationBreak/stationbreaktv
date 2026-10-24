// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Look-ahead (spec 15): the channels one step above and below are resolved once the picture plays. */
class LiveChannelNeighboursTest {
    private val ch2 = ChannelId("ch-2")

    private class Watching(
        val r: Rig,
        val job: Job,
        val keys: Channel<LiveCommand>,
    )

    /** Channels 2, 5 and 9 (item = its number), the sitting on 5; [setup] edits the schedule before the start. */
    private fun TestScope.rig(setup: Rig.() -> Unit = {}): Rig {
        val r = Rig(this)
        r.schedule.channel(ch2, 2, listOf(program(0, 600, 2)))
        r.schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
        r.schedule.channel(CH9, 9, listOf(program(0, 600, 9)))
        r.setup()
        return r
    }

    private suspend fun TestScope.watching(setup: Rig.() -> Unit = {}): Watching {
        val r = rig(setup)
        r.lastChannel.save(r.account, CH5)
        val keys = Channel<LiveCommand>(Channel.UNLIMITED)
        val job = r.start(commands = keys.receiveAsFlow())
        advanceTimeBy(1.seconds)
        runCurrent()
        return Watching(r, job, keys)
    }

    private fun Watching.up() {
        keys.trySend(LiveCommand.Flip(FlipDirection.Up))
    }

    private fun Rig.preResolves() = tune.calls.filterIsInstance<Call.PreResolve>()

    private fun Rig.discards() = tune.calls.filterIsInstance<Call.Discard>()

    @Test
    fun afterTheFirstPictureBothNeighboursArePreResolved() =
        runTest {
            val r = rig()
            r.lastChannel.save(r.account, CH5)
            r.start()
            // The tune answered but the minimum snow is still showing: no picture yet.
            assertTrue(r.preResolves().isEmpty())
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(r.state is LiveState.Watching)
            assertEquals(setOf(itemOf(9), itemOf(2)), r.preResolves().map { it.item }.toSet())
            assertEquals(2, r.preResolves().size)
        }

    @Test
    fun noPreResolveWhileChoosing() =
        runTest {
            val w = watching()
            val before = w.r.preResolves().size
            w.up()
            runCurrent()
            advanceTimeBy(300.milliseconds)
            assertTrue(w.r.state is LiveState.Choosing)
            assertEquals(before, w.r.preResolves().size)
        }

    @Test
    fun aFlipToANeighbourUsesItsHandleAtTheCommitsOffset() =
        runTest {
            val w = watching()
            val resolvedWith =
                w.r
                    .preResolves()
                    .single { it.item == itemOf(9) }
                    .start
            w.up()
            runCurrent()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            val used =
                w.r.tune.calls
                    .filterIsInstance<Call.TuneHandle>()
                    .single { it.item == itemOf(9) }
            // The program started at the day's start and the clock was ten minutes in when the test began.
            assertEquals(10.minutes + used.at.milliseconds, used.start)
            assertNotEquals(resolvedWith, used.start)
            assertTrue(w.r.tunes.none { it.item == itemOf(9) })
        }

    @Test
    fun aStaleHandleIsDiscardedNotPlayed() =
        runTest {
            val r = rig { schedule.channel(CH9, 9, listOf(program(0, 12, 9), program(12, 600, 19))) }
            val neighbours = Neighbours(r.session, r.tune, r.schedule, r.clock)
            val order = FlipOrder(r.schedule.airings(r.clock.now()))
            neighbours.prepareAround(checkNotNull(order.byId(CH5)), order)
            // Past the end of 9's first block, which is what 9's handle was resolved for.
            r.jumpMs += 3 * MIN
            val onAir = checkNotNull(r.schedule.onAir(CH9, r.clock.now(), 0))
            assertNull(neighbours.take(CH9, onAir))
            assertTrue(r.discards().any { it.item == itemOf(9) })
            assertTrue(r.tune.calls.none { it is Call.TuneHandle })
        }

    @Test
    fun aHeldHandleIsMadeAgainWhenItsBlockEnds() =
        runTest {
            val w = watching { schedule.channel(CH9, 9, listOf(program(0, 12, 9), program(12, 600, 19))) }
            // Past the end of 9's first block, with no key pressed.
            advanceTimeBy(2.minutes)
            runCurrent()
            assertTrue("the old handle was discarded", w.r.discards().any { it.item == itemOf(9) })
            assertTrue("the new block was resolved", w.r.preResolves().any { it.item == itemOf(19) })
            w.up()
            runCurrent()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            assertTrue(
                w.r.tune.calls
                    .any { it is Call.TuneHandle && it.item == itemOf(19) },
            )
        }

    @Test
    fun aFlipToANeighbourShowsTheSnowBeforeThePicture() =
        runTest {
            val w = watching()
            val before = w.r.seen.size
            w.up()
            runCurrent()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            // The snow shows for its minimum, then the picture.
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(
                w.r.tune.calls
                    .any { it is Call.TuneHandle && it.item == itemOf(9) },
            )
            val after = w.r.seen.drop(before)
            val snow = after.indexOfFirst { it is LiveState.Tuning }
            val picture = after.indexOfFirst { it is LiveState.Watching }
            assertTrue("the snow showed", snow >= 0)
            assertTrue("and then the picture", picture > snow)
        }

    @Test
    fun aFlipToANeighbourReportsItsOwnKind() =
        runTest {
            val w = watching()
            w.up()
            runCurrent()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            val kinds =
                w.r.reports
                    .filterIsInstance<TuneReport.Tune>()
                    .map { it.kind }
            assertEquals(listOf(TuneKind.Fresh, TuneKind.Neighbour), kinds)
        }

    @Test
    fun aCommitDiscardsTheOtherHandleBeforeNewOnesAreMade() =
        runTest {
            val w = watching()
            val resolved = w.r.preResolves().size
            w.up()
            runCurrent()
            advanceTimeBy(500.milliseconds)
            runCurrent()
            // The snow shows for its minimum, then the picture.
            advanceTimeBy(1.seconds)
            runCurrent()
            val calls = w.r.tune.calls
            val discard = calls.indexOfFirst { it is Call.Discard && it.item == itemOf(2) }
            assertTrue("the handle for 2 was discarded", discard >= 0)
            val laterResolves = calls.withIndex().count { (i, c) -> c is Call.PreResolve && i > discard }
            assertTrue("new handles were made after it", laterResolves > 0)
            assertTrue(w.r.preResolves().size > resolved)
        }

    @Test
    fun leavingDiscardsEveryHandle() =
        runTest {
            val w = watching()
            assertTrue(w.r.discards().isEmpty())
            w.job.cancel()
            runCurrent()
            assertEquals(
                setOf(itemOf(9), itemOf(2)),
                w.r
                    .discards()
                    .map { it.item }
                    .toSet(),
            )
        }

    @Test
    fun aSingleChannelLineupPreResolvesNothing() =
        runTest {
            val r = Rig(this)
            r.schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
            r.lastChannel.save(r.account, CH5)
            r.start()
            advanceTimeBy(2.seconds)
            runCurrent()
            assertTrue(r.state is LiveState.Watching)
            assertTrue(r.preResolves().isEmpty())
        }
}
