// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A sitting that runs ahead of the clock (a dead item moved the channel on early): what it shows, and how it ends. */
class LiveChannelDriftTest {
    @Test
    fun aDriftedSittingsBannerShowsTheSlotThatIsActuallyOnTheClock() =
        runTest {
            // 20 min left of the first show when it dies: the second show starts 19 min 57 s early.
            val r = Rig(this, startMin = 40)
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2), program(120, 180, 3)))
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()
            r.at(3_000)

            val tuning = assertIs<LiveState.Tuning>(r.seen.last { it is LiveState.Tuning }).banner
            val driftMs = 60 * MIN - (40 * MIN + 3_000)
            assertEquals("Test Movie 2", tuning.title)
            assertEquals(T0 + 60 * MIN - driftMs, tuning.slotStartMs)
            assertEquals(T0 + 120 * MIN - driftMs, tuning.slotEndMs)
            // The clock is 3 s into the slot that is shown: it starts now, at the clock.
            assertEquals(r.nowMs(), tuning.slotStartMs)
        }

    @Test
    fun aDriftedSittingKeepsItsChannelWhenTheScheduleHasNothingAtTheDriftedTime() =
        runTest {
            val r = Rig(this, startMin = 40)
            // Nothing is on after minute 120 (the channel is hidden the next day, say).
            r.schedule.channel(CH7, 7, listOf(program(0, 60, 1), program(60, 120, 2)))
            r.tune.script(itemOf(1), FakeTune.dead(), FakeTune.dead())
            r.start()
            r.at(3_000)
            assertEquals(Call.Tune(itemOf(2), kotlin.time.Duration.ZERO, 3_000), r.tunes.last())

            // The drifted time reaches 120 while the clock is still inside the second show (at 100 min 3 s).
            val driftMs = 60 * MIN - (40 * MIN + 3_000)
            val endOfDrifted = 120 * MIN - 40 * MIN - driftMs
            r.at(endOfDrifted)

            // Back to the clock at once, on the same channel: no card and no other channel.
            val last = r.tunes.last()
            assertEquals(itemOf(2), last.item)
            assertEquals(40.minutes + 3.seconds, last.start)
            assertEquals(endOfDrifted, last.at)
            assertTrue(r.seen.drop(1).none { it == LiveState.Ident(null) })
        }
}
