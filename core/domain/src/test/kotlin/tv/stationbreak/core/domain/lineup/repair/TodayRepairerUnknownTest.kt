// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.LineupEvent
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.model.schedule.ScheduleContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 26, fix round 5: a saved selection that cannot be read (the provider gives null) never drops or undoes
 * anything; a saved seq is never dropped; a fix always carries the seq its day shows; the seq being saved is known to
 * the service while its save runs.
 */
internal class TodayRepairerUnknownTest : RepairTestBase() {
    // --- A null read drops and undoes nothing -------------------------------------------------------------------

    @Test
    fun repairLost_withNoSavedSelectionRead_writesNothing_andTheRepairsStay() {
        val rig = lostRig()
        // The shown seq-3 repair, and a leftover the check would otherwise drop.
        val stray = rig.repairOf(laughs.id).copy(seq = 9)
        val seeded = { held: TodayRepairs -> held.copy(selection = listOf(held.selection.single(), stray)) }
        assertTrue(runBlocking { rig.service.recordRepairs(ACCOUNT) { seeded(it.repairs) } })
        val before = rig.record()
        val saves = rig.store.calls.count { it == "save" }
        val reads = rig.library.currentIdsCalls
        rig.unknown = true

        assertEquals(
            RepairOutcome.SelectionUnknown,
            runBlocking { rig.repairer.repairLost(ACCOUNT, atMs) },
        )

        assertEquals(before, rig.record(), "nothing dropped, nothing checked")
        assertEquals(saves, rig.store.calls.count { it == "save" }, "nothing written")
        assertEquals(reads, rig.library.currentIdsCalls, "nothing read")
    }

    @Test
    fun aThrowingSave_whoseSeqReadGivesNull_undoesNothing_andIsReported() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))

        assertFailsWith<IllegalStateException> {
            rig.changeOutcome(before = setOf(libA, libB), after = setOf(libA)) {
                rig.unknown = true
                error("x")
            }
        }

        assertEquals(listOf(1L), rig.record().selection.map { it.seq }, "not undone")
        assertEquals(1, rig.events.count { it == LineupEvent.RepairUndoFailed })
    }

    @Test
    fun aFailedSave_whoseSeqReadGivesNull_saysUndoFailed() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))

        val outcome =
            rig.changeOutcome(before = setOf(libA, libB), after = setOf(libA), save = false) { rig.unknown = true }

        assertEquals(RepairOutcome.UndoFailed, outcome)
        assertEquals(listOf(1L), rig.record().selection.map { it.seq })
    }

    @Test
    fun aChange_withNoSavedSelectionRead_recordsNothing() {
        val rig = lostRig()
        val before = rig.record()
        rig.unknown = true

        val outcome =
            runBlocking { rig.repairer.selectionChanged(ACCOUNT, setOf(libA, libB), atMs, atLeast = 0L) { true } }

        assertEquals(RepairOutcome.SelectionUnknown, outcome)
        assertEquals(before, rig.record(), "the shown repair is not dropped")
    }

    // --- dropSelection never drops a saved seq ------------------------------------------------------------------

    @Test
    fun dropSelection_ofASavedSeq_isANoOp() {
        val rig = lostRig()
        val before = rig.record()

        assertEquals(RepairOutcome.AlreadySaved, runBlocking { rig.repairer.dropSelection(ACCOUNT, 3L) })
        assertEquals(RepairOutcome.AlreadySaved, runBlocking { rig.repairer.dropSelection(ACCOUNT, 2L) })
        rig.unknown = true
        assertEquals(RepairOutcome.SelectionUnknown, runBlocking { rig.repairer.dropSelection(ACCOUNT, 4L) })

        assertEquals(before, rig.record())
        rig.unknown = false
        assertEquals(
            3L,
            rig
                .day(laughs.id)
                .record
                ?.repair
                ?.seq,
            "the saved repair still shows",
        )
    }

    // --- A fix carries the seq its day shows --------------------------------------------------------------------

    @Test
    fun aDayWhoseRepairDoesNotCompose_getsAFixItShows() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))
        val day = rig.day(laughs.id)
        val later = day.day.blocks.first { it.startMs > atMs + hour && it.content is ScheduleContent.Program }
        val goneId = (later.content as ScheduleContent.Program).itemId
        // A seq-3 repair whose blocks stop short of the day's end: it does not compose, so the day is its build.
        val broken =
            ChannelRepair(
                laughs.id,
                day.n,
                day.epochEndMs,
                atMs,
                3,
                setOf(libA),
                noonMs,
                noonMs + hour,
                libA,
                listOf(KeptBlock(noonMs, noonMs + hour, KeptContent.Program(comedies[0].item.id, 0))),
                null,
            )
        assertTrue(runBlocking { rig.service.recordRepairs(ACCOUNT) { TodayRepairs(selection = listOf(broken)) } })
        rig.applied = 3
        assertNull(rig.day(laughs.id).record?.repair, "not composed")
        rig.gone(goneId)

        assertTrue(rig.lost(setOf(libA)) > 0)

        val fix = rig.record().fixes.single { it.blockStartMs == later.startMs }
        assertEquals(3L, fix.seq, "the seq the day's record shows")
        val shown = rig.day(laughs.id).blockAt(later.startMs)
        assertNotEquals(goneId, (shown.content as? ScheduleContent.Program)?.itemId, "and the day shows the fix")
    }

    // --- The seq being saved ------------------------------------------------------------------------------------

    @Test
    fun theServiceKnowsTheSeqBeingSaved_onlyWhileItsSaveRuns() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        var during: Long? = null

        rig.changeOutcome(before = setOf(libA, libB), after = setOf(libA)) { during = rig.service.savingNow }

        assertEquals(1L, during)
        assertNull(rig.service.savingNow)
    }
}
