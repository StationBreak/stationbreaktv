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

    // Size stands in for bytes: a fix is 10, a selection repair 100.
    private fun sizeOf(record: TodayRepairs) = record.fixes.size * 10 + record.selection.size * 100

    @Test
    fun trimmedToFit_dropsOldFixesThenCoveringFixesThenTheOldestSelection() {
        val covering = fixOf(n = 1L, dayEndMs = day1End, blockStartMs = NOW_MS - HALF_HOUR_MS)
        val old = fixOf(n = 2L, dayEndMs = day2End, blockStartMs = day1End)
        val newer = fixOf(n = 3L, dayEndMs = day2End + 1, blockStartMs = day2End)
        val early = repairOf(n = 1L, atMs = NOW_MS + 1, dayEndMs = day1End)
        val late = repairOf(n = 2L, atMs = day1End + 5, dayEndMs = day2End)
        val record = TodayRepairs(listOf(late, early), listOf(newer, covering, old), lostCheckedGeneration = 5L)

        assertSame(record, record.trimmedToFit(NOW_MS, 1_000) { sizeOf(it) }.record)
        val one = record.trimmedToFit(NOW_MS, 225) { sizeOf(it) }
        assertEquals(setOf(covering, newer), one.record.fixes.toSet())
        assertEquals(Triple(1, 0, 0), Triple(one.fixes, one.coveringFixes, one.selection))
        val two = record.trimmedToFit(NOW_MS, 215) { sizeOf(it) }
        assertEquals(listOf(covering), two.record.fixes, "the oldest day's fix went first, then the next")
        val fixes = record.trimmedToFit(NOW_MS, 205) { sizeOf(it) }
        assertEquals(emptyList(), fixes.record.fixes)
        assertEquals(Triple(2, 1, 0), Triple(fixes.fixes, fixes.coveringFixes, fixes.selection))
        val some = record.trimmedToFit(NOW_MS, 105) { sizeOf(it) }
        assertEquals(listOf(late), some.record.selection, "the oldest atMs goes first")
        assertEquals(Triple(2, 1, 1), Triple(some.fixes, some.coveringFixes, some.selection))
        val last = record.trimmedToFit(NOW_MS, 5) { sizeOf(it) }
        assertEquals(TodayRepairs(lostCheckedGeneration = 5L), last.record)
        assertEquals(2, last.selection)
    }

    @Test
    fun digestFor_hashesEachRecordedDayOncePerRecord() {
        val record = TodayRepairs(selection = listOf(repairOf(n = 1L)), fixes = listOf(fixOf(n = 1L), fixOf(n = 2L)))
        val first = (1..3).map { record.digestFor(ChannelId("starter:laughs"), 1L) }
        record.digestFor(ChannelId("starter:laughs"), 2L)
        record.digestFor(ChannelId("starter:laughs"), 3L)
        record.digestFor(ChannelId("starter:other"), 1L)

        assertEquals(2, record.hashed, "two recorded days, each hashed once, however often asked")
        assertEquals(1, first.toSet().size)
        assertEquals("", record.digestFor(ChannelId("starter:laughs"), 3L))
        val copy = record.copy()
        assertEquals(first.first(), copy.digestFor(ChannelId("starter:laughs"), 1L), "an equal record, the same digest")
        assertEquals(2, copy.hashed, "a new instance works its own out")
    }
}
