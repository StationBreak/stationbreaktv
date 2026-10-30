// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import kotlin.time.Instant

/** A block's look-ahead runs start, then any drops and rearms, then keep, inside one block (spec 20). */
class BoundaryLookAheadTest {
    @Test
    fun rearmOnlyActsBetweenStartAndKeep() =
        runTest {
            val r = Rig(this, startMin = 10)
            r.schedule.channel(CH7, 7, listOf(program(0, 30, 1), program(30, 60, 2)))
            val onAir = checkNotNull(r.schedule.onAir(CH7, Instant.fromEpochMilliseconds(r.nowMs()), 3))
            val look = BoundaryLookAhead(NextUp(r.session, r.tune, LiveTimings(), r::nowMs))
            // Before start: no block to look ahead from, so nothing is done.
            assertFalse(look.rearm())
            look.start(backgroundScope, onAir)
            // Running: nothing more to do.
            assertFalse(look.rearm())
            look.drop()
            assertTrue(look.rearm())
            r.at(20 * MIN)
            assertEquals(1, r.tune.calls.count { it is Call.PreResolve })
            look.keep()
            // After keep: the block is over, so nothing is done.
            assertFalse(look.rearm())
            r.at(25 * MIN)
            assertEquals(1, r.tune.calls.count { it is Call.PreResolve })
        }
}
