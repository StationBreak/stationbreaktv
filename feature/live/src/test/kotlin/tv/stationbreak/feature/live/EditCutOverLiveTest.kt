// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import kotlin.time.Duration.Companion.minutes

/**
 * An edit to the channel being watched (spec 22, ruling 16): the airing item is not interrupted; from its end the
 * channel follows the new rules at the clock, which may join an item part-way.
 */
class EditCutOverLiveTest {
    @Test
    fun anEditToThePlayingChannelLeavesTheAiringItemAlone() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.schedule.channel(CH7, 7, listOf(program(0, 50, 1), program(50, 120, 2)))
            r.start()
            assertEquals(1, r.tunes.size)
            assertEquals(itemOf(1), r.tunes.single().item)

            // The rig's clock is the start (minute 10) plus the virtual time. The edit comes at minute 20: from now
            // the day is laid out by the new rules, whose block at the clock is another item.
            r.atMin(10)
            r.schedule.channel(CH7, 7, listOf(program(0, 40, 8), program(40, 100, 9)))

            r.atMin(20)
            r.atMin(39)
            assertEquals("the airing item is not retuned", 1, r.tunes.size)
            assertTrue(r.state is LiveState.Watching)

            // Its block ends at minute 50: the channel follows the new rules at the clock, 10 minutes into the item.
            r.atMin(40)
            assertEquals(2, r.tunes.size)
            assertEquals(itemOf(9), r.tunes.last().item)
            assertEquals(10.minutes, r.tunes.last().start)
            r.assertEveryPreResolveEndsOnce()
        }

    // #556: the guide shows what is playing, with its real start and end, whatever the schedule says after an edit.
    @Test
    fun theAiringItemIsExposedToTheGuideWithItsRealStartAndEndAfterAnEdit() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.schedule.channel(CH7, 7, listOf(program(0, 50, 1), program(50, 120, 2)))
            assertNull(r.live.playingAiring)
            r.start()

            r.atMin(10)
            r.schedule.channel(CH7, 7, listOf(program(0, 40, 8), program(40, 100, 9)))
            r.atMin(20)

            val held = checkNotNull(r.live.playingAiring)
            assertEquals(CH7, held.channel)
            assertEquals(program(0, 50, 1), held.block)
        }

    @Test
    fun aHandleResolvedForTheOldNextBlockIsDiscardedWhenTheNewRulesPlayAnother() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.schedule.channel(CH7, 7, listOf(program(0, 50, 1), program(50, 120, 2)))
            r.start()

            // Resolved ahead for the old next block (the first block ends at clock minute 50, virtual minute 40),
            // then the edit arrives.
            r.at(40 * MIN - 45_000)
            val resolved = r.tune.calls.any { it is Call.PreResolve && it.item == itemOf(2) }
            assertTrue("resolved for the old next block", resolved)
            r.schedule.channel(CH7, 7, listOf(program(0, 50, 1), program(50, 120, 3)))
            r.atMin(40)

            assertEquals(itemOf(3), r.tunes.last().item)
            assertTrue(r.tune.calls.none { it is Call.TuneHandle })
            r.assertEveryPreResolveEndsOnce()
        }

    @Test
    fun aFlipOrderFollowsAnEditAtOnce() =
        runTest {
            val f = flipping()
            // A new custom channel appears at 6: Up from 5 now goes there, not to 9.
            f.r.schedule.channel(CH7, 6, listOf(program(0, 600, 7)))
            val lineup =
                f.r.schedule.channels
                    .map { it.key to it.value.number }
            f.r.channels.publish(*lineup.toTypedArray())
            runCurrent()

            f.up()

            assertEquals(LiveState.Choosing(ChannelTag(6, "Test Channel 6"), overPicture = true), f.r.state)
        }
}
