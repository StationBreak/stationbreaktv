// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Duration.Companion.seconds

/** Warm the neighbour in the last flip's direction, once a picture plays (spec 24). */
class NeighboursWarmTest {
    private val ch2 = ChannelId("ch-2")

    /** Channels 2, 5 and 9 (item = its number); the sitting is on 5, so Up is 9 and Down is 2. */
    private fun TestScope.rig(setup: Rig.() -> Unit = {}): Rig {
        val r = Rig(this)
        r.schedule.channel(ch2, 2, listOf(program(0, 600, 2)))
        r.schedule.channel(CH5, 5, listOf(program(0, 600, 5)))
        r.schedule.channel(CH9, 9, listOf(program(0, 600, 9)))
        r.setup()
        return r
    }

    private fun Rig.neighbours() = Neighbours(session, tune, schedule, clock)

    private suspend fun Rig.order() = FlipOrder(schedule.airings(clock.now()))

    private fun Rig.warms() = tune.calls.filterIsInstance<Call.Warm>().map { it.item }

    private fun Rig.preResolves() = tune.calls.filterIsInstance<Call.PreResolve>()

    @Test
    fun warmsDownBeforeAnyFlip() =
        runTest {
            val r = rig()
            val order = r.order()
            r.neighbours().prepareAround(checkNotNull(order.byId(CH5)), order, picture = true)
            assertEquals(listOf(itemOf(2)), r.warms())
        }

    @Test
    fun warmsUpAfterAnUpFlip() =
        runTest {
            val r = rig()
            val order = r.order()
            val n = r.neighbours()
            n.noteFlip(FlipDirection.Up)
            n.prepareAround(checkNotNull(order.byId(CH5)), order, picture = true)
            assertEquals(listOf(itemOf(9)), r.warms())
        }

    @Test
    fun warmsOnlyTheTarget() =
        runTest {
            val r = rig()
            val order = r.order()
            r.neighbours().prepareAround(checkNotNull(order.byId(CH5)), order, picture = true)
            assertEquals(2, r.preResolves().size)
            assertTrue(itemOf(9) !in r.warms())
        }

    @Test
    fun directionChangeWarmsOtherHeldHandleWithoutReResolve() =
        runTest {
            val r = rig()
            val order = r.order()
            val n = r.neighbours()
            val current = checkNotNull(order.byId(CH5))
            n.prepareAround(current, order, picture = true)
            n.noteFlip(FlipDirection.Up)
            n.prepareAround(current, order, picture = true)
            assertEquals(listOf(itemOf(2), itemOf(9)), r.warms())
            assertEquals(2, r.preResolves().size)
        }

    @Test
    fun theSameTargetIsNotWarmedAgainOnAnotherPass() =
        runTest {
            val r = rig()
            val order = r.order()
            val n = r.neighbours()
            val current = checkNotNull(order.byId(CH5))
            n.prepareAround(current, order, picture = true)
            n.prepareAround(current, order, picture = true)
            assertEquals(listOf(itemOf(2)), r.warms())
        }

    @Test
    fun noWarmOnCard() =
        runTest {
            val r = rig()
            val order = r.order()
            r.neighbours().prepareAround(checkNotNull(order.byId(CH5)), order, picture = false)
            assertEquals(2, r.preResolves().size)
            assertTrue(r.warms().isEmpty())
        }

    @Test
    fun noWarmBeforeFirstFrame() =
        runTest {
            val r = rig()
            r.lastChannel.save(r.account, CH5)
            r.start()
            // The tune answered but the minimum snow is still showing: no look-ahead, so no warm.
            assertTrue(r.warms().isEmpty())
            assertTrue(r.preResolves().isEmpty())
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(listOf(itemOf(2)), r.warms())
        }

    @Test
    fun staleHandleDropsStandby() =
        runTest {
            val r = rig { schedule.channel(ch2, 2, listOf(program(0, 12, 2), program(12, 600, 12))) }
            val order = r.order()
            val n = r.neighbours()
            val current = checkNotNull(order.byId(CH5))
            n.prepareAround(current, order, picture = true)
            r.jumpMs += 3 * MIN
            n.prepareAround(current, order, picture = true)
            val fates =
                r.tune.handleFates.values
                    .map { it.toList() }
            assertTrue("the old handle was discarded", fates.any { it == listOf("discard") })
            assertEquals(listOf(itemOf(2), itemOf(12)), r.warms())
        }

    @Test
    fun transcodeHandleNotWarmed() =
        runTest {
            val r = rig { tune.warmResult = { false } }
            val order = r.order()
            val n = r.neighbours()
            val current = checkNotNull(order.byId(CH5))
            n.prepareAround(current, order, picture = true)
            assertEquals(2, r.preResolves().size)
            assertTrue(r.tune.calls.none { it is Call.Discard })
            // Declined: asked again on the next pass is allowed, but it still resolves nothing new.
            n.prepareAround(current, order, picture = true)
            assertEquals(2, r.preResolves().size)
        }

    @Test
    fun aFlipUpMakesTheNextLookAheadWarmUp() =
        runTest {
            val ch12 = ChannelId("ch-12")
            val r = rig { schedule.channel(ch12, 12, listOf(program(0, 600, 12))) }
            r.lastChannel.save(r.account, CH5)
            val keys = Channel<LiveCommand>(Channel.UNLIMITED)
            r.start(commands = keys.receiveAsFlow())
            advanceTimeBy(1.seconds)
            runCurrent()
            // On 5 before any flip: Down is the target.
            assertEquals(listOf(itemOf(2)), r.warms())
            keys.trySend(LiveCommand.Flip(FlipDirection.Up))
            keys.trySend(LiveCommand.FlipReleased)
            runCurrent()
            advanceTimeBy(2.seconds)
            runCurrent()
            // On 9 after the flip: Up (12) is warmed, not Down (5).
            assertEquals(itemOf(12), r.warms().last())
            assertTrue(itemOf(5) !in r.warms())
        }
}
