// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.ChannelDay
import tv.stationbreak.core.domain.lineup.LineupEvent
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.selectionOf
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A selection change's save and its undo (spec 26, fix round 4): the programme on air never changes, whether the save
 * commits, fails, or commits and then throws; a fix of a lost block goes with the repair it patches.
 */
internal class TodayRepairerSaveTest : RepairTestBase() {
    // The days a tune sees now, read in a save callback (no runBlocking: the repairer's thread is in use).
    private suspend fun Rig.daysNow(at: Long): List<ChannelDay> =
        source.channelDays(ACCOUNT, selectionOf(chosen, applied)!!, Instant.fromEpochMilliseconds(at))

    private fun List<ChannelDay>.blockAt(
        channel: ChannelId,
        at: Long,
    ): ScheduleBlock = first { it.channel.id == channel }.blockAt(at)

    // What plays at [at] in [block]: the item and how far into it (a kept window may start the block at the half hour).
    private fun playing(
        block: ScheduleBlock,
        at: Long,
    ): Pair<Any?, Long> =
        when (val content = block.content) {
            is ScheduleContent.Program -> content.itemId to content.mediaStartOffsetMs + (at - block.startMs)
            ScheduleContent.Slate, ScheduleContent.Break -> null to 0L
        }

    // --- A fix goes with its repair -----------------------------------------------------------------------------

    @Test
    fun aFixOfTheShownRepair_staysOnAir_whileASecondChangeIsSaved_andAfterItIsDropped() {
        val rig = lostRig()
        assertTrue(rig.lost(setOf(libA)) > 0)
        val fix = rig.record().fixes.single { it.isOfTheLostBlock() }
        assertEquals(3L, fix.seq, "the fix patches the repair shown")
        // Half an hour into the gone block: what airs is the fix's piece, not the gone item.
        val later = lostSlotStart + 30 * minute
        val onAir = playing(rig.day(laughs.id, later).blockAt(later), later)
        assertNotEquals(lost.item.id, onAir.first, "the fix airs")

        val outcome =
            rig.changeOutcome(before = setOf(libA), after = setOf(libA, libB), at = later) { seq ->
                assertTrue(seq > 3L)
                assertTrue(rig.record().fixes.any { it == fix }, "the shown repair's fix is kept beside the change")
                assertEquals(onAir, playing(rig.daysNow(later).blockAt(laughs.id, later), later), "while the save runs")
            }

        val second = assertIs<RepairOutcome.Saved<SelectionRepaired>>(outcome).result.seq
        assertEquals(onAir, playing(rig.day(laughs.id, later).blockAt(later), later), "once the change shows")
        // Part 4's start-up resume drops the change: the seq-3 repair shows again, with its fix.
        rig.applied = 3
        rig.chosen = setOf(libA)
        assertEquals(RepairOutcome.Saved(true), runBlocking { rig.repairer.dropSelection(ACCOUNT, second) })
        assertTrue(rig.record().fixes.none { it.seq == second }, "the change's own fixes go with it")
        assertEquals(fix, rig.record().fixes.single { it.isOfTheLostBlock() })
        assertEquals(onAir, playing(rig.day(laughs.id, later).blockAt(later), later), "after the drop")
    }

    // --- A save that commits is never undone --------------------------------------------------------------------

    @Test
    fun aSaveThatCommitsAndThenIsCancelled_keepsTheRepair_andTheCancellationGoesToTheCaller() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA, libB))
        val onAir = rig.day(laughs.id).blockAt(atMs)

        val thrown =
            assertFailsWith<CancellationException> {
                rig.changeOutcome(before = setOf(libA, libB), after = setOf(libA)) { seq ->
                    // The write goes through, then the caller is cancelled before it returns.
                    rig.chosen = setOf(libA)
                    rig.applied = seq
                    throw CancellationException("after the write")
                }
            }

        assertEquals("after the write", thrown.message)
        assertEquals(1L, rig.applied)
        assertEquals(
            setOf(laughs.id, whodunit.id),
            rig
                .record()
                .selection
                .map { it.channel }
                .toSet(),
            "kept",
        )
        assertEquals(
            1L,
            rig
                .day(laughs.id)
                .record
                ?.repair
                ?.seq,
            "and shown",
        )
        assertEquals(onAir, rig.day(laughs.id).blockAt(atMs))
        assertTrue(rig.events.none { it == LineupEvent.RepairUndoFailed })
    }

    @Test
    fun aSaveThatIsCancelledBeforeItWrites_isUndone() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA, libB))
        val onAir = rig.day(laughs.id).blockAt(atMs)

        assertFailsWith<CancellationException> {
            rig.changeOutcome(before = setOf(libA, libB), after = setOf(libA)) { throw CancellationException("x") }
        }

        assertEquals(TodayRepairs.NONE, rig.record(), "the record is as it was")
        assertEquals(onAir, rig.day(laughs.id).blockAt(atMs))
    }

    @Test
    fun aSaveThatCommitsButReturnsFalse_isNotUndone_andSaysSaved() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))

        val outcome =
            rig.changeOutcome(before = setOf(libA, libB), after = setOf(libA), save = false) { seq ->
                rig.chosen = setOf(libA)
                rig.applied = seq
            }

        assertEquals(1L, assertIs<RepairOutcome.Saved<SelectionRepaired>>(outcome).result.seq)
        assertEquals(
            1L,
            rig
                .day(laughs.id)
                .record
                ?.repair
                ?.seq,
        )
    }

    // --- An undo that fails -------------------------------------------------------------------------------------

    @Test
    fun anUndoWhoseWriteFails_saysUndoFailed_isReported_andTheNextCheckDropsTheRepair() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        val onAir = rig.day(laughs.id).blockAt(atMs)

        val outcome =
            rig.changeOutcome(before = setOf(libA, libB), after = setOf(libA), save = false) {
                rig.store.inner.failWrites = true
            }

        assertEquals(RepairOutcome.UndoFailed, outcome)
        assertEquals(1, rig.events.count { it == LineupEvent.RepairUndoFailed }, "reported once")
        assertEquals(listOf(1L), rig.record().selection.map { it.seq }, "the repair is still recorded")
        assertNull(rig.day(laughs.id).record?.repair, "above the saved seq: never shown")
        assertEquals(onAir, rig.day(laughs.id).blockAt(atMs))

        rig.store.inner.failWrites = false
        runBlocking { rig.repairer.repairLost(ACCOUNT, atMs) }

        assertEquals(emptyList(), rig.record().selection, "the next check drops it")
        assertNotNull(rig.record().lostCheck, "and checks")
        assertEquals(onAir, rig.day(laughs.id).blockAt(atMs))
    }
}
