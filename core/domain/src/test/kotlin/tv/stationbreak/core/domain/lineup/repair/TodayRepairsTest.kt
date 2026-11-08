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
                    fixOf(n = 1L, dayEndMs = day1End, seq = 4L),
                    fixOf(n = 2L, dayEndMs = day2End, blockStartMs = day1End + HALF_HOUR_MS, seq = 7L),
                ),
            lostCheck = LostCheck(12L, day1End),
        )

    @Test
    fun rolled_dropsEndedDays() {
        val rolled = two().rolled(day1End)
        assertEquals(listOf(2L), rolled.selection.map { it.n })
        assertEquals(listOf(2L), rolled.fixes.map { it.n })
        assertNull(rolled.lostCheck, "the check goes with its earliest day")
        assertEquals(7L, rolled.lastSeq)
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
        assertEquals(0L, TodayRepairs.NONE.latestSeq())
        assertEquals(0L, TodayRepairs(fixes = listOf(fixOf())).latestSeq())
        assertEquals(9L, two().copy(lastSeq = 9L).latestSeq(), "the stored seq when it is higher")
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
        val record = TodayRepairs(listOf(late, early), listOf(newer, covering, old), LostCheck(5L, day2End))

        assertSame(record, record.trimmedToFit(NOW_MS, 1_000, null) { sizeOf(it) }.record)
        val one = record.trimmedToFit(NOW_MS, 225, null) { sizeOf(it) }
        assertEquals(setOf(covering, newer), one.record.fixes.toSet())
        assertEquals(Triple(1, 0, 0), Triple(one.fixes, one.coveringFixes, one.selection))
        val two = record.trimmedToFit(NOW_MS, 215, null) { sizeOf(it) }
        assertEquals(listOf(covering), two.record.fixes, "the oldest day's fix went first, then the next")
        val fixes = record.trimmedToFit(NOW_MS, 205, null) { sizeOf(it) }
        assertEquals(emptyList(), fixes.record.fixes)
        assertEquals(Triple(2, 1, 0), Triple(fixes.fixes, fixes.coveringFixes, fixes.selection))
        val some = record.trimmedToFit(NOW_MS, 105, null) { sizeOf(it) }
        assertEquals(listOf(late), some.record.selection, "the oldest atMs goes first")
        assertEquals(Triple(2, 1, 1), Triple(some.fixes, some.coveringFixes, some.selection))
        val last = record.trimmedToFit(NOW_MS, 5, null) { sizeOf(it) }
        assertEquals(TodayRepairs.NONE, last.record, "nothing stays, the seq neither")
        assertEquals(2, last.selection)
    }

    @Test
    fun digestFor_hashesEachRecordedDayOncePerRecord() {
        var hashed = 0
        DigestProbe.hashed = { hashed++ }
        try {
            val fixes = listOf(fixOf(n = 1L), fixOf(n = 2L))
            val record = TodayRepairs(selection = listOf(repairOf(n = 1L)), fixes = fixes)
            val laughs = ChannelId("starter:laughs")
            val first = (1..3).map { record.digestFor(laughs, 1L) }
            record.digestFor(laughs, 2L)
            record.digestFor(laughs, 3L)
            record.digestFor(ChannelId("starter:other"), 1L)

            assertEquals(2, hashed, "two recorded days, each hashed once, however often asked")
            assertEquals(1, first.toSet().size)
            assertEquals("", record.digestFor(laughs, 3L))
            val copy = record.copy()
            assertEquals(first.first(), copy.digestFor(laughs, 1L), "an equal record, the same digest")
            assertEquals(4, hashed, "a new instance works its own out")
        } finally {
            DigestProbe.hashed = null
        }
    }

    @Test
    fun lastSeq_withARepairHeld_survivesRollAndTrim_andGoesWithTheLast() {
        // The seq-9 repair was dropped (a failed save); 4 and 7 are held.
        val record = two().copy(lastSeq = 9L)
        val rolled = record.rolled(day1End)
        assertEquals(listOf(7L), rolled.selection.map { it.seq })
        assertEquals(9L, rolled.lastSeq, "a repair is held: the seq stays")
        // Size 225 drops one fix only; 115 drops both fixes and the oldest repair (seq 4).
        assertEquals(9L, record.trimmedToFit(NOW_MS, 215, null) { sizeOf(it) }.record.lastSeq)
        val trimmed = record.trimmedToFit(NOW_MS, 105, null) { sizeOf(it) }.record
        assertEquals(listOf(7L), trimmed.selection.map { it.seq })
        assertEquals(9L, trimmed.lastSeq, "a repair is held: the seq stays")

        val gone = record.rolled(day2End)
        assertEquals(TodayRepairs.NONE, gone, "with no selection repair held, nothing is")
        assertTrue(gone.isEmpty)
        assertEquals(0L, record.trimmedToFit(NOW_MS, 0, null) { sizeOf(it) }.record.lastSeq)
        assertEquals(0L, record.withoutSelection(4L).withoutSelection(7L).lastSeq)
    }

    @Test
    fun lastSeq_isStoredOnlyWithASelectionRepair() {
        assertEquals(TodayRepairs(fixes = two().fixes), TodayRepairs(fixes = two().fixes, lastSeq = 9L).stored())
        assertEquals(9L, two().copy(lastSeq = 9L).stored().lastSeq)
        assertTrue(TodayRepairs(lastSeq = 9L).isEmpty, "a seq alone is not stored")
    }

    @Test
    fun nextSeq_isAboveTheRecordsAndTheSavedSelections() {
        assertEquals(8L, two().nextSeq(appliedSeq = 3L))
        assertEquals(13L, two().nextSeq(appliedSeq = 12L))
        assertEquals(10L, two().copy(lastSeq = 9L).nextSeq(appliedSeq = 0L))
        assertEquals(1L, TodayRepairs.NONE.nextSeq(appliedSeq = 0L))
        assertEquals(5L, TodayRepairs.NONE.nextSeq(appliedSeq = 4L))
    }

    @Test
    fun applied_leavesOutTheSelectionRepairsOfALaterSeq_withTheirFixes() {
        val record = two()
        assertSame(record, record.applied(7L, resetSeq = 0L))
        val held = record.applied(6L, resetSeq = 0L)
        assertEquals(listOf(4L), held.selection.map { it.seq })
        assertEquals(listOf(record.fixes[0]), held.fixes, "the held-back repair's fix is held back with it")
        assertEquals(emptyList(), record.applied(0L, resetSeq = 0L).selection)
        assertEquals(emptyList(), record.applied(0L, resetSeq = 0L).fixes)
    }

    @Test
    fun applied_showsEachDaysRepairWithItsOwnFixesOnly() {
        val first = repairOf(n = 1L, dayEndMs = day1End, seq = 4L)
        val second = first.copy(seq = 6L, atMs = first.atMs + 60_000L)
        val built = fixOf(n = 3L, dayEndMs = day2End, blockStartMs = day1End, seq = 0L)
        val ofFirst = fixOf(n = 1L, dayEndMs = day1End, seq = 4L)
        val ofSecond = fixOf(n = 1L, dayEndMs = day1End, blockStartMs = NOW_MS + 4 * HALF_HOUR_MS, seq = 6L)
        val stray = fixOf(n = 1L, dayEndMs = day1End, blockStartMs = NOW_MS + 6 * HALF_HOUR_MS, seq = 0L)
        val record = TodayRepairs(listOf(first, second), listOf(built, ofFirst, ofSecond, stray))

        val atFirst = record.applied(5L, resetSeq = 0L)
        assertEquals(listOf(first), atFirst.selection)
        assertEquals(listOf(built, ofFirst), atFirst.fixes, "the first repair's fix, and the built day's")
        val atSecond = record.applied(6L, resetSeq = 0L)
        assertEquals(listOf(second), atSecond.selection)
        assertEquals(listOf(built, ofSecond), atSecond.fixes)
        val none = record.applied(3L, resetSeq = 0L)
        assertEquals(emptyList(), none.selection)
        assertEquals(listOf(built, stray), none.fixes, "a day that shows no repair shows the fixes of its build")
    }

    @Test
    fun pruned_takesTheFixesOfASupersededRepairWithIt() {
        val first = repairOf(n = 1L, dayEndMs = day1End, seq = 4L)
        val second = first.copy(seq = 6L)
        val later = first.copy(seq = 8L)
        val ofFirst = fixOf(n = 1L, dayEndMs = day1End, seq = 4L)
        val ofSecond = fixOf(n = 1L, dayEndMs = day1End, seq = 6L)
        val ofLater = fixOf(n = 1L, dayEndMs = day1End, seq = 8L)
        val built = fixOf(n = 1L, dayEndMs = day1End, seq = 0L)
        val record = TodayRepairs(listOf(first, second, later), listOf(ofFirst, ofSecond, ofLater, built))

        val pruned = record.pruned(6L)

        assertEquals(listOf(second, later), pruned.selection, "the shown one and the one being saved stay")
        assertEquals(listOf(ofSecond, ofLater), pruned.fixes, "each with its own fix; the others go")
        assertSame(pruned, pruned.pruned(6L))
    }

    @Test
    fun withoutSelection_dropsOnlyThatSeq_andItsFixes() {
        val record = two()
        val dropped = record.withoutSelection(7L)
        assertEquals(listOf(4L), dropped.selection.map { it.seq })
        assertEquals(listOf(record.fixes[0]), dropped.fixes, "the seq-7 repair's fix goes with it")
        assertEquals(7L, dropped.latestSeq(), "the seq 4 repair is held: the seq stays")
        assertNull(dropped.lostCheck)
        assertSame(record, record.withoutSelection(5L))
    }

    @Test
    fun trimmedToFit_dropsTheRepairsAboveTheSavedSeqFirst_withTheirFixes() {
        val shown = repairOf(n = 1L, atMs = NOW_MS + 1, dayEndMs = day1End, seq = 4L)
        // Repaired later than the shown one: by age alone the shown one would go first.
        val unsaved = repairOf(n = 1L, atMs = NOW_MS + 2, dayEndMs = day1End, seq = 5L)
        val ofShown = fixOf(n = 1L, dayEndMs = day1End, seq = 4L)
        val ofUnsaved = fixOf(n = 1L, dayEndMs = day1End, blockStartMs = NOW_MS + 4 * HALF_HOUR_MS, seq = 5L)
        val record = TodayRepairs(listOf(unsaved, shown), listOf(ofShown, ofUnsaved), LostCheck(5L, day1End))

        val one = record.trimmedToFit(NOW_MS, 125, savedSeq = 4L) { sizeOf(it) }

        assertEquals(listOf(shown), one.record.selection, "the repair not shown goes before anything else")
        assertEquals(listOf(ofShown), one.record.fixes, "its fix goes with it; the shown one's fix stays")
        assertEquals(record.lostCheck, one.record.lostCheck, "no fix of a shown day went")
        assertEquals(Triple(0, 0, 1), Triple(one.fixes, one.coveringFixes, one.selection))
        // With no saved seq known, every repair counts as shown: the fixes go first, then the oldest repair.
        val unknown = record.trimmedToFit(NOW_MS, 125, savedSeq = null) { sizeOf(it) }
        assertEquals(listOf(unsaved), unknown.record.selection)
    }

    @Test
    fun trimmedToFit_countsTheFixesOfAnUnsavedRepairWithIt_notAgainAmongTheFixes() {
        val shown = repairOf(n = 1L, atMs = NOW_MS + 1, dayEndMs = day1End, seq = 4L)
        val unsaved = repairOf(n = 2L, atMs = NOW_MS + 2, dayEndMs = day2End, seq = 5L)
        // The unsaved repair's fix is the oldest: in the fix order it would come first.
        val ofUnsaved = fixOf(n = 2L, dayEndMs = day1End, blockStartMs = NOW_MS + 4 * HALF_HOUR_MS, seq = 5L)
        val ofShown = fixOf(n = 1L, dayEndMs = day2End, blockStartMs = day1End, seq = 4L)
        val record = TodayRepairs(listOf(shown, unsaved), listOf(ofUnsaved, ofShown))

        val trimmed = record.trimmedToFit(NOW_MS, 105, savedSeq = 4L) { sizeOf(it) }

        assertEquals(listOf(shown), trimmed.record.selection)
        assertEquals(emptyList(), trimmed.record.fixes)
        assertEquals(Triple(1, 0, 1), Triple(trimmed.fixes, trimmed.coveringFixes, trimmed.selection))
    }

    @Test
    fun trimmedToFit_thatDropsAShownRepair_clearsTheLostCheck() {
        val older = repairOf(n = 1L, atMs = NOW_MS + 1, dayEndMs = day1End, seq = 4L)
        val newer = repairOf(n = 2L, atMs = NOW_MS + 2, dayEndMs = day2End, seq = 4L)
        val record = TodayRepairs(listOf(older, newer), emptyList(), LostCheck(5L, day1End))

        val trimmed = record.trimmedToFit(NOW_MS, 150, savedSeq = 4L) { sizeOf(it) }

        assertEquals(listOf(newer), trimmed.record.selection)
        assertNull(trimmed.record.lostCheck, "the day now shows its build, which no check has seen")
    }

    @Test
    fun trimmedToFit_thatDropsAFix_clearsTheLostCheck() {
        val record = two()
        val kept = record.trimmedToFit(NOW_MS, 1_000, null) { sizeOf(it) }.record
        assertEquals(record.lostCheck, kept.lostCheck)
        val one = record.trimmedToFit(NOW_MS, 215, null) { sizeOf(it) }
        assertEquals(1, one.fixes)
        assertNull(one.record.lostCheck, "the next check makes the dropped fix again")
    }

    @Test
    fun trimmedToFit_thatCannotFitAtAll_keepsNothing() {
        val record = two()
        val trimmed = record.trimmedToFit(NOW_MS, 5, null) { 50 + sizeOf(it) }
        assertEquals(TodayRepairs.NONE, trimmed.record)
        assertEquals(Triple(1, 1, 2), Triple(trimmed.fixes, trimmed.coveringFixes, trimmed.selection))
    }

    @Test
    fun lostCheck_aloneIsNotStored() {
        val check = TodayRepairs(lostCheck = LostCheck(3L, day1End))
        assertTrue(check.isEmpty)
        assertEquals(TodayRepairs.NONE, check.stored())
    }
}
