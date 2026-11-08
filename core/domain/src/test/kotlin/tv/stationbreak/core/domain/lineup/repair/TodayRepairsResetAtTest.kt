// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.model.schedule.ScheduleContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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

    // --- Fix round 1: below the reset seq is not shown, for pruning and trimming too ----------------------------

    @Test
    fun pruned_dropsTheRepairsBelowTheResetSeq_withTheirFixes_andKeepsTheBuildsFixesOfTheirDay() {
        val pruned = record.pruned(appliedSeq = 7L, resetSeq = 6L)

        assertEquals(listOf(newer), pruned.selection, "the seq-4 repair can never show again")
        assertEquals(listOf(built, newerFix), pruned.fixes, "its day shows its build, so the build's fix stays")
        assertSame(pruned, pruned.pruned(appliedSeq = 7L, resetSeq = 6L))
    }

    @Test
    fun trimmedToFit_dropsARepairBelowTheResetSeq_beforeTheShownOne() {
        val shown = repairOf(n = 1L, atMs = NOW_MS + 1, dayEndMs = day1End, seq = 4L)
        // Older than nothing: by age alone the shown one would go first.
        val below = repairOf(n = 2L, atMs = NOW_MS + 2, dayEndMs = day2End, seq = 2L)
        val both = TodayRepairs(listOf(shown, below))

        val trimmed = both.trimmedToFit(NOW_MS, 150, savedSeq = 4L, resetSeq = 3L) { it.selection.size * 100 }

        assertEquals(listOf(shown), trimmed.record.selection, "the repair below the reset seq is not shown")
    }

    @Test
    fun aRepairBelowTheResetSeq_thenALostItemOnItsDay_theLostCheckFixesIt() {
        val first = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA, libB))
        assertNotNull(first.changed(before = setOf(libA, libB), after = setOf(libA)))
        val ofSeq1 = first.record().selection
        // The seq-1 repairs were recorded before a setup that saved seq 2: the days show their build.
        val seeded = TodayRepairs(ofSeq1, lastSeq = 1L)
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA), seeded)
        rig.applied = 2L
        rig.resetSeq = 2L
        assertNull(rig.day(laughs.id).record?.repair)
        val later = atMs + 3 * hour
        val airing = rig.day(laughs.id, later).blockAt(later).content
        val gone = assertIs<ScheduleContent.Program>(airing).itemId
        rig.gone(gone)

        assertTrue(rig.lost(setOf(libA), at = later) > 0, "the lost block is fixed")

        val now = rig.day(laughs.id, later).blockAt(later).content
        assertNotEquals(gone, (now as? ScheduleContent.Program)?.itemId, "and the fix shows")
        assertTrue(rig.record().fixes.isNotEmpty())
    }

    @Test
    fun theSourcesMemo_isHit_withAResetSeq() {
        val first = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA, libB))
        assertNotNull(first.changed(before = setOf(libA, libB), after = setOf(libA)))
        val ofSeq1 = first.record().selection
        // Seq 1 below the reset seq, seq 3 shown: what the source follows is not the record itself.
        val seeded = TodayRepairs(selection = ofSeq1 + ofSeq1.map { it.copy(seq = 3L) }, lastSeq = 3L)
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA), seeded)
        rig.applied = 3L
        rig.resetSeq = 2L
        var hashed = 0
        DigestProbe.hashed = { hashed++ }
        try {
            repeat(3) {
                rig.onAir(laughs.id, atMs)
                rig.onAir(whodunit.id, atMs)
            }
        } finally {
            DigestProbe.hashed = null
        }

        assertEquals(2, hashed, "the two repaired days, hashed once: every lookup after the first is a memo hit")
        assertEquals(
            3L,
            rig
                .day(laughs.id)
                .record
                ?.repair
                ?.seq,
        )
    }

    // The seq-1 repairs of a change, seeded on a TV of their own with [extra] beside them.
    private fun seededWithSeq1(extra: (List<ChannelRepair>) -> List<ChannelRepair> = { emptyList() }): Rig {
        val first = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA, libB))
        assertNotNull(first.changed(before = setOf(libA, libB), after = setOf(libA)))
        val ofSeq1 = first.record().selection
        val seeded = TodayRepairs(selection = ofSeq1 + extra(ofSeq1), lastSeq = 3L)
        return Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA), seeded)
    }

    @Test
    fun aLostCheckThatAlreadyCovers_stillDropsTheRepairsBelowTheResetSeq() {
        val rig = seededWithSeq1()
        rig.applied = 1L
        assertEquals(RepairOutcome.Saved(0), runBlocking { rig.repairer.repairLost(ACCOUNT, atMs) })
        assertNotNull(rig.record().lostCheck, "the check covers this generation now")
        // A setup saved seq 2 since.
        rig.applied = 2L
        rig.resetSeq = 2L

        runBlocking { rig.repairer.repairLost(ACCOUNT, atMs) }

        assertEquals(emptyList(), rig.record().selection, "dropped before the check is looked at")
    }

    @Test
    fun aDrop_prunesTheRepairsBelowTheResetSeq() {
        val rig = seededWithSeq1 { seq1 -> seq1.map { it.copy(seq = 3L) } }
        rig.applied = 2L
        rig.resetSeq = 2L

        assertEquals(RepairOutcome.Saved(true), runBlocking { rig.repairer.dropSelection(ACCOUNT, 3L) })

        assertEquals(emptyList(), rig.record().selection, "seq 3 dropped, and seq 1 can never show")
    }
}
