// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The reset seq (spec 26, Part 4 Amendment 4 item 1): a selection repair of a seq below the last setup's seq never
 * shows, nor do its fixes, at every place the record is held back to the saved selection.
 */
internal class TodayRepairsResetAtTest : RepairTestBase() {
    private val day1End = NOW_MS + 6 * 3_600_000L
    private val day2End = day1End + 24 * 3_600_000L

    private val old = repairOf(n = 1L, dayEndMs = day1End, seq = 4L)
    private val oldFix = fixOf(n = 1L, dayEndMs = day1End, seq = 4L)
    private val built = fixOf(n = 1L, dayEndMs = day1End, blockStartMs = NOW_MS + 4 * HALF_HOUR_MS, seq = 0L)
    private val newer = repairOf(n = 2L, atMs = day1End + 60_000L, dayEndMs = day2End, seq = 7L)
    private val newerFix = fixOf(n = 2L, dayEndMs = day2End, blockStartMs = day1End + HALF_HOUR_MS, seq = 7L)
    private val record = TodayRepairs(listOf(old, newer), listOf(oldFix, built, newerFix))

    @Test
    fun applied_hidesTheRepairsBelowTheResetSeq_withTheirFixes() {
        val shown = record.applied(appliedSeq = 7L, resetSeq = 6L)

        assertEquals(listOf(newer), shown.selection, "the repair of seq 4 was recorded before the setup")
        assertEquals(listOf(built, newerFix), shown.fixes, "its fix goes with it; its day shows the build's fixes")
    }

    @Test
    fun applied_aRepairAtTheResetSeq_shows() {
        val atReset = record.applied(appliedSeq = 7L, resetSeq = 7L)
        assertEquals(listOf(newer), atReset.selection)
        assertEquals(listOf(built, newerFix), atReset.fixes)
    }

    @Test
    fun applied_noResetSeq_isTheSameInstance() {
        val shownWhole = TodayRepairs(listOf(old, newer), listOf(oldFix, newerFix))
        assertSame(shownWhole, shownWhole.applied(appliedSeq = 7L, resetSeq = 0L))
        assertSame(shownWhole, shownWhole.applied(appliedSeq = 7L, resetSeq = 4L), "nothing is below it")
    }

    @Test
    fun applied_aResetSeqAboveEveryRepair_showsOnlyTheBuildsFixes() {
        val shown = record.applied(appliedSeq = 9L, resetSeq = 9L)
        assertEquals(emptyList(), shown.selection)
        assertEquals(listOf(built), shown.fixes)
    }

    // --- The three call sites -----------------------------------------------------------------------------------

    @Test
    fun theSource_hidesARepairBelowTheResetSeq_andShowsItAgainWhenTheResetSeqIsLower() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA, libB))
        assertNotNull(rig.changed(before = setOf(libA, libB), after = setOf(libA)))
        assertEquals(
            1L,
            rig
                .day(laughs.id)
                .record
                ?.repair
                ?.seq,
        )

        rig.resetSeq = 2L
        rig.applied = 2L
        assertNull(rig.day(laughs.id).record?.repair, "below the reset seq: never shown")

        // The same record and seq, another reset seq: the source's memo is keyed by both.
        rig.resetSeq = 0L
        assertEquals(
            1L,
            rig
                .day(laughs.id)
                .record
                ?.repair
                ?.seq,
        )
    }

    @Test
    fun aChange_dropsTheRepairsBelowTheResetSeq_fromWhatItKeeps() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA, libB))
        assertNotNull(rig.changed(before = setOf(libA, libB), after = setOf(libA)))
        rig.resetSeq = 2L
        rig.applied = 2L

        val outcome = rig.changeOutcome(before = setOf(libA), after = setOf(libA, libB))

        val seq = assertIs<RepairOutcome.Saved<SelectionRepaired>>(outcome).result.seq
        assertEquals(3L, seq)
        assertEquals(setOf(3L), rig.record().selection.mapTo(HashSet()) { it.seq }, "the hidden seq-1 repairs went")
    }

    @Test
    fun aLostCheck_dropsTheRepairsBelowTheResetSeq_withTheHiddenOnesAbove() {
        val first = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA, libB))
        assertNotNull(first.changed(before = setOf(libA, libB), after = setOf(libA)))
        val ofSeq1 = first.record().selection
        // Seq 1 recorded before a setup that saved seq 2 (its reset seq); seq 3 recorded and never saved.
        val seeded = TodayRepairs(selection = ofSeq1 + ofSeq1.map { it.copy(seq = 3L) }, lastSeq = 3L)
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA), seeded)
        rig.applied = 2L
        rig.resetSeq = 2L

        runBlocking { rig.repairer.repairLost(ACCOUNT, atMs) }

        val left = rig.record().selection
        assertEquals(emptyList(), left, "the hidden seq-3 repairs, and the seq-1 ones before the setup")
    }
}
