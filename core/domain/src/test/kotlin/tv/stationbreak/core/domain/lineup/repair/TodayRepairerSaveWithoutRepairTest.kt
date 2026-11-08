// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A selection save with no repair of its own (spec 26, Part 4 §2): a tick that only adds, or a bookkeeping drop. It
 * takes the next seq in the repairer's turn, floored at `atLeast`, and drops first every selection repair above the
 * saved seq, so a hidden one can never come to show under the new seq (Amendment 1, H1).
 */
internal class TodayRepairerSaveWithoutRepairTest : RepairTestBase() {
    private fun Rig.saveWithoutRepair(
        atLeast: Long = 0L,
        commit: Boolean = true,
        returns: Boolean = commit,
        asked: MutableList<Long> = mutableListOf(),
    ): RepairOutcome<Long> =
        runBlocking {
            repairer.saveWithoutRepair(ACCOUNT, atLeast) { seq ->
                asked += seq
                if (commit) applied = seq
                returns
            }
        }

    @Test
    fun undoFailed_thenTick_hiddenRepairNeverShows() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        val onAir = rig.day(laughs.id).blockAt(atMs)
        // A change whose save failed and whose undo failed too: its repair stays, above the saved seq 0.
        val failed =
            rig.changeOutcome(before = setOf(libA, libB), after = setOf(libA), save = false) {
                rig.store.inner.failWrites = true
            }
        assertEquals(RepairOutcome.UndoFailed, failed)
        rig.store.inner.failWrites = false
        assertEquals(
            listOf(1L),
            rig
                .record()
                .selection
                .map { it.seq }
                .distinct(),
        )

        // A tick that only adds a library: its seq is above the hidden repair's.
        val outcome = rig.saveWithoutRepair()

        val seq = assertIs<RepairOutcome.Saved<Long>>(outcome).result
        assertEquals(2L, seq)
        assertEquals(2L, rig.applied)
        assertEquals(emptyList(), rig.record().selection, "the hidden repair went before the save")
        assertNull(rig.day(laughs.id).record?.repair, "and never shows under the new seq")
        assertEquals(onAir, rig.day(laughs.id).blockAt(atMs))
    }

    @Test
    fun theShownRepair_stays() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        rig.changed(before = setOf(libA, libB), after = setOf(libA))
        val shown = rig.record()

        assertEquals(RepairOutcome.Saved(2L), rig.saveWithoutRepair())

        assertEquals(shown.selection, rig.record().selection, "nothing above the saved seq: nothing is written")
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
    fun theSeq_isFlooredAtAtLeast() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))
        val asked = mutableListOf<Long>()

        assertEquals(RepairOutcome.Saved(10L), rig.saveWithoutRepair(atLeast = 10L, asked = asked))
        assertEquals(RepairOutcome.Saved(11L), rig.saveWithoutRepair(atLeast = 4L, asked = asked), "next above 10")
        assertEquals(listOf(10L, 11L), asked)
    }

    @Test
    fun aSaveThatReturnsFalse_isSaved_onlyWhenTheSavedSeqReachedIt() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))

        assertEquals(RepairOutcome.Saved(1L), rig.saveWithoutRepair(commit = true, returns = false))
        assertEquals(RepairOutcome.Unsaved, rig.saveWithoutRepair(commit = false))
        assertEquals(1L, rig.applied)
    }

    @Test
    fun noSavedSelection_isSelectionUnknown_andNothingIsSaved() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))
        rig.unknown = true
        val asked = mutableListOf<Long>()

        assertEquals(RepairOutcome.SelectionUnknown, rig.saveWithoutRepair(asked = asked))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun noLineup_isNoLineup_andNothingIsSaved() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))
        runBlocking { rig.service.forget(ACCOUNT) }
        val asked = mutableListOf<Long>()

        assertEquals(RepairOutcome.NoLineup, rig.saveWithoutRepair(asked = asked))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun aDropThatCannotBeWritten_isRefused_andNothingIsSaved() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        rig.changeOutcome(before = setOf(libA, libB), after = setOf(libA), save = false) {
            rig.store.inner.failWrites = true
        }
        val asked = mutableListOf<Long>()

        assertEquals(RepairOutcome.Refused, rig.saveWithoutRepair(asked = asked))
        assertEquals(emptyList(), asked, "a save now would show the hidden repair")
        assertTrue(rig.record().selection.isNotEmpty())
    }

    // --- selectionChanged's floor -------------------------------------------------------------------------------

    @Test
    fun aChange_isFlooredAtAtLeast_andTakesTheNextSeqAboveIt() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))

        val first = rig.changeOutcome(before = setOf(libA, libB), after = setOf(libA), atLeast = 9L)
        assertEquals(9L, assertIs<RepairOutcome.Saved<SelectionRepaired>>(first).result.seq)
        val second = rig.changeOutcome(before = setOf(libA), after = setOf(libA, libB), atLeast = 3L)
        assertEquals(10L, assertIs<RepairOutcome.Saved<SelectionRepaired>>(second).result.seq)
        assertEquals(
            10L,
            rig
                .day(laughs.id)
                .record
                ?.repair
                ?.seq,
        )
    }
}
