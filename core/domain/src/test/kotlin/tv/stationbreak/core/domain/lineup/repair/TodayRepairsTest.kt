// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TodayRepairsTest {
    private val day1End = NOW_MS + 6 * 3_600_000L
    private val day2End = day1End + 24 * 3_600_000L

    private fun two() =
        TodayRepairs(
            selection =
                listOf(
                    repairOf(n = 1L, dayEndMs = day1End, seq = 4L),
                    repairOf(n = 2L, atMs = day1End + 60_000L, dayEndMs = day2End, seq = 7L),
                ),
            fixes =
                listOf(
                    fixOf(n = 1L, dayEndMs = day1End),
                    fixOf(n = 2L, dayEndMs = day2End, blockStartMs = day1End + HALF_HOUR_MS),
                ),
            lostCheckedGeneration = 12L,
        )

    @Test
    fun rolled_dropsEndedDays() {
        val rolled = two().rolled(day1End)
        assertEquals(listOf(2L), rolled.selection.map { it.n })
        assertEquals(listOf(2L), rolled.fixes.map { it.n })
        assertEquals(12L, rolled.lostCheckedGeneration)
        // A day that ends exactly now is over; one that ends later is kept.
        assertEquals(2, two().rolled(day1End - 1).selection.size)
        assertEquals(0, two().rolled(day2End).selection.size)
    }

    @Test
    fun rolled_isTheSameInstanceWhenNothingDrops() {
        val repairs = two()
        assertSame(repairs, repairs.rolled(NOW_MS))
        assertSame(TodayRepairs.NONE, TodayRepairs.NONE.rolled(NOW_MS))
    }

    @Test
    fun latestSeq_isTheHighestSelectionSeq() {
        assertEquals(7L, two().latestSeq())
        assertNull(TodayRepairs.NONE.latestSeq())
        assertNull(TodayRepairs(fixes = listOf(fixOf())).latestSeq())
    }

    @Test
    fun digestFor_isStableAndFollowsTheRecordOfThatChannelDay() {
        val repairs = two()
        val laughs = ChannelId("starter:laughs")
        assertEquals(repairs.digestFor(laughs, 1L), two().digestFor(laughs, 1L))
        assertNotEquals(repairs.digestFor(laughs, 1L), repairs.digestFor(laughs, 2L))
        // Another channel's repair and an unrelated fix change nothing of this day's digest.
        val other = repairs.copy(selection = repairs.selection + repairOf(channel = "starter:other", n = 1L))
        assertEquals(repairs.digestFor(laughs, 1L), other.digestFor(laughs, 1L))
        // A change of its own blocks or its fixes does.
        val reseq = repairOf(n = 1L, dayEndMs = day1End, seq = 5L)
        val moved = repairs.copy(selection = listOf(reseq) + repairs.selection[1])
        assertNotEquals(repairs.digestFor(laughs, 1L), moved.digestFor(laughs, 1L))
        val unfixed = repairs.copy(fixes = repairs.fixes.drop(1))
        assertNotEquals(repairs.digestFor(laughs, 1L), unfixed.digestFor(laughs, 1L))
    }

    @Test
    fun digestFor_aDayWithNoRecordHasTheEmptyDigest() {
        assertEquals("", TodayRepairs.NONE.digestFor(ChannelId("starter:laughs"), 1L))
        assertTrue(TodayRepairs.NONE.isEmpty)
    }
}
