// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program

/** The place arithmetic of pause and seek (spec 20, ADR-0021): all in media milliseconds. */
class TransportMathTest {
    private val block = program(0, 60, 1)

    @Test
    fun backClampsAtTheInPoint() {
        val inPoint = TransportMath.inPointMs(block)
        assertEquals(inPoint, TransportMath.back(inPoint - 5_000, inPoint))
        assertEquals(inPoint + 5_000, TransportMath.back(inPoint + 5_000, inPoint))
    }

    @Test
    fun backIsTheBlockStartNotTheJoinPoint() {
        // The viewer joined 30 minutes in, but back goes to the block's start (media 0), not to where they joined.
        val joinedAt = TransportMath.liveMs(block, T0 + 30 * MIN)
        assertEquals(30 * MIN, joinedAt)
        assertEquals(0L, TransportMath.back(joinedAt - 10 * MIN - 30 * MIN, TransportMath.inPointMs(block)))
        assertEquals(0L, TransportMath.back(-10 * MIN, TransportMath.inPointMs(block)))
    }

    @Test
    fun aheadClampsAtTheLesserOfLiveAndCut() {
        assertEquals(20 * MIN, TransportMath.ahead(25 * MIN, 20 * MIN, 60 * MIN))
        assertEquals(60 * MIN, TransportMath.ahead(70 * MIN, 80 * MIN, 60 * MIN))
        assertEquals(10 * MIN, TransportMath.ahead(10 * MIN, 20 * MIN, 60 * MIN))
    }

    @Test
    fun reachesLiveOnlyWhenTheLiveClampHits() {
        assertTrue(TransportMath.reachesLive(25 * MIN, 20 * MIN, 60 * MIN))
        assertTrue(TransportMath.reachesLive(20 * MIN, 20 * MIN, 60 * MIN))
        assertFalse(TransportMath.reachesLive(10 * MIN, 20 * MIN, 60 * MIN))
        // The block has ended: live is past the cut, so the clamp is the cut and it is not live.
        assertFalse(TransportMath.reachesLive(70 * MIN, 80 * MIN, 60 * MIN))
    }

    @Test
    fun behindIsNeverNegative() {
        assertEquals(5_000L, TransportMath.behindMs(20_000, 15_000))
        assertEquals(0L, TransportMath.behindMs(15_000, 20_000))
    }

    @Test
    fun liveIsNotCappedAtTheCut() {
        assertEquals(60 * MIN, TransportMath.cutMs(block))
        assertEquals(75 * MIN, TransportMath.liveMs(block, T0 + 75 * MIN))
    }

    @Test
    fun aSplitItemsInPointIsItsMediaStart() {
        val split = program(0, 30, 2, mediaStartMs = 40 * MIN)
        assertEquals(40 * MIN, TransportMath.inPointMs(split))
        assertEquals(70 * MIN, TransportMath.cutMs(split))
    }
}
