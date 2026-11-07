// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.leftovers
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The repair engine's contract with its callers (spec 26, the final review): a selection repair shows only once its
 * selection is saved, the lost-block check holds for its own days, every switched-on channel is repaired, and each
 * call says what it did.
 */
internal class TodayRepairerContractTest : RepairTestBase() {
    // --- The seq gate -------------------------------------------------------------------------------------------

    @Test
    fun aRepairWhoseSelectionIsNotSaved_isHeldBack_untilItsSeqIsApplied() {
        val items = comedies + crimes + comediesB + crimesB
        val plain = Rig(lineupOf(laughs, whodunit), items, setOf(libA))
        val rig = Rig(lineupOf(laughs, whodunit), items, setOf(libA, libB))
        rig.applied = 6
        val onAir = rig.day(laughs.id).blockAt(atMs)

        assertNotNull(rig.change(before = setOf(libA, libB), after = setOf(libA), seq = 7))

        // Recorded, but the saved selection's seq is below it: the day is built as without the repair.
        assertEquals(7L, rig.repairOf(laughs.id).seq)
        for (channel in listOf(laughs.id, whodunit.id)) {
            val held = rig.day(channel)
            assertNull(held.record?.repair, "no repair is laid over the day")
            assertEquals(plain.day(channel).day.blocks, held.day.blocks, "the unrepaired day")
        }

        rig.applied = 7

        val repaired = rig.day(laughs.id)
        assertEquals(rig.repairOf(laughs.id), repaired.record?.repair)
        assertEquals(onAir, repaired.blockAt(atMs), "the repaired day, its programme on air kept")
    }

    @Test
    fun theGate_holdsBackSelectionRepairsOnly_neverAFix() {
        val rig = lostRig()
        rig.applied = 0

        assertTrue(rig.lost(setOf(libA)) > 0)

        val day = rig.day(laughs.id)
        assertNull(day.record?.repair, "the seq-3 repair is held back")
        assertEquals(rig.record().fixes, day.record?.fixes, "the fix is laid over the unrepaired day")
    }

    @Test
    fun dropSelection_takesOutOnlyThatSeq_andTheSeqIsNotUsedAgain() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA, libB))
        assertNotNull(rig.change(before = setOf(libA, libB), after = setOf(libA), seq = 7))
        val fixes = rig.record().fixes

        val dropped = runBlocking { rig.repairer.dropSelection(ACCOUNT, 7) }

        assertEquals(RepairOutcome.Saved(true), dropped)
        assertEquals(emptyList(), rig.record().selection)
        assertEquals(fixes, rig.record().fixes)
        assertEquals(7L, rig.record().latestSeq())
        assertEquals(RepairOutcome.Saved(false), runBlocking { rig.repairer.dropSelection(ACCOUNT, 7) })
        val restarted = rig.restarted(lineupOf(laughs, whodunit))
        assertEquals(7L, restarted.record().latestSeq(), "the seq is stored")
    }

    // --- The lost-block check -----------------------------------------------------------------------------------

    @Test
    fun repairLost_withNoFix_keepsTheFileAtVersion3() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))

        assertEquals(RepairOutcome.Saved(0), runBlocking { rig.repairer.repairLost(ACCOUNT, setOf(libA), atMs) })

        assertNotNull(rig.record().lostCheck, "the check is held in memory")
        val bytes = assertNotNull(rig.store.inner.peek(ACCOUNT))
        val stored = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes))
        assertEquals(3, stored.fileVersion)
        assertEquals(TodayRepairs.NONE, stored.repairs)
    }

    @Test
    fun repairLost_onTheNextDay_checksAgain_atTheSameGeneration() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))
        rig.lost(setOf(libA))
        val check = assertNotNull(rig.record().lostCheck)
        assertEquals(rig.day(laughs.id).epochEndMs, check.untilMs, "the check holds for its own day")
        val reads = rig.library.currentIdsCalls
        rig.lost(setOf(libA))
        assertEquals(reads, rig.library.currentIdsCalls, "the same day, the same generation: no check")

        val tomorrow = check.untilMs + hour
        rig.lost(setOf(libA), at = tomorrow)

        assertTrue(rig.library.currentIdsCalls > reads, "a day no check has seen is checked")
        assertEquals(rig.day(laughs.id, tomorrow).epochEndMs, assertNotNull(rig.record().lostCheck).untilMs)
    }

    @Test
    fun repairLost_onADayWithNoRepair_whenTheSelectionIsGone_readsOnlyTheBuiltPoolsLiveness() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))
        val day = rig.day(laughs.id)
        val later = day.day.blocks.first { it.startMs > atMs + hour && it.content is ScheduleContent.Program }
        val goneId = (later.content as ScheduleContent.Program).itemId
        rig.gone(goneId)
        val snapshots = rig.library.snapshotCutoffs.size
        val byId = rig.library.scheduleItemsOfCalls
        val liveness = rig.library.currentIdsCalls

        assertTrue(rig.lost(playable = null) > 0)

        val fix = rig.record().fixes.first { it.blockStartMs == later.startMs }
        assertTrue(fix.pieces.isNotEmpty(), "filled from the built day pool")
        assertTrue(fix.pieces.all { piece -> day.pool.any { it.id == piece.itemId } })
        assertEquals(snapshots, rig.library.snapshotCutoffs.size, "no snapshot is read")
        assertEquals(byId, rig.library.scheduleItemsOfCalls, "no item is read by id")
        assertTrue(rig.library.currentIdsCalls > liveness, "only the pool's liveness is read")
    }

    // --- Every switched-on channel is repaired ------------------------------------------------------------------

    @Test
    fun afterAChange_everyChannelsFirstTune_readsTheRecordAlone() {
        val westerns = custom("custom-westerns", 620, rules("Westerns", Source.Genre("Western")))
        val docs = (0 until 3).map { film(180 + it, "Documentary") }
        val lineup = lineupOf(laughs, whodunit, ownB, westerns, leftovers("odds-and-ends", 199, null))
        val rig = Rig(lineup, comedies + crimes + comediesB + docs, setOf(libA, libB))
        assertEquals(HiddenReason.NoMatches, rig.day(westerns.id).hiddenAt(atMs), "hidden at the change")

        assertNotNull(rig.change(before = setOf(libA, libB), after = setOf(libA)))

        val repaired =
            rig
                .record()
                .selection
                .map { it.channel }
                .toSet()
        assertEquals(lineup.channels.map { it.id }.toSet(), repaired, "every switched-on channel")
        val hidden = rig.repairOf(westerns.id)
        assertEquals(HiddenReason.NoMatches, hidden.tailHidden)
        assertEquals(listOf(KeptBlock(hidden.keptFromMs, hidden.dayEndMs, null, 0)), hidden.blocks)
        val cold = rig.restarted(lineup)
        rig.library.snapshotCutoffs.clear()

        val airings = runBlocking { cold.source.airings(Instant.fromEpochMilliseconds(atMs)) }
        // Today's day of every channel (a tune's next blocks past the day's end read tomorrow's, as ever).
        for (day in cold.days()) assertEquals(rig.repairOf(day.channel.id), day.record?.repair)

        assertEquals(emptyList(), rig.library.snapshotCutoffs, "no snapshot is read for any channel")
        assertEquals(0, cold.source.snapshotReads())
        assertEquals(HiddenReason.NoMatches, airings.first { it.id == westerns.id }.hidden, "still hidden")
        assertNull(airings.first { it.id == laughs.id }.hidden)
    }

    @Test
    fun aDayHiddenAtTheChange_thatTheChangeFills_airsFromTheHalfHour() {
        val ownC = custom("custom-own-c", 603, rules("OwnC", Source.Library(LIB_C_ID)))
        val comediesC = (0 until 3).map { film(500 + it, "Comedy", library = libC) }
        val rig = Rig(lineupOf(laughs, ownC), comedies + comediesC, setOf(libA))
        assertNotNull(rig.day(ownC.id).hiddenAt(atMs), "hidden at the change")

        assertNotNull(rig.change(before = setOf(libA), after = setOf(libA, libC)))

        val repair = rig.repairOf(ownC.id)
        assertEquals(noonMs, repair.keptFromMs, "from the half hour")
        assertEquals(repair.keptFromMs, repair.pinEndMs, "nothing was on the air to keep")
        assertNull(repair.pinLibrary)
        assertNull(repair.tailHidden)
        assertTrue(ids(comediesC).containsAll(repair.tailIds()) && repair.tailIds().isNotEmpty())
        assertContiguous(repair)
        assertNull(rig.day(ownC.id).hiddenAt(atMs), "shown now")
    }

    // --- What each call says ------------------------------------------------------------------------------------

    @Test
    fun outcomes_noLineup_refused_saved() {
        val none = Rig(lineupOf(laughs), comedies, setOf(libA))
        runBlocking { none.service.forget(ACCOUNT) }
        val noLineup = runBlocking { none.repairer.selectionChanged(ACCOUNT, setOf(libA), 1, atMs) }
        assertEquals(RepairOutcome.NoLineup, noLineup)

        val failing = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        failing.store.inner.failWrites = true
        val refused = runBlocking { failing.repairer.selectionChanged(ACCOUNT, setOf(libA), 1, atMs) }
        assertEquals(RepairOutcome.Refused, refused)

        val ok = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        val saved = runBlocking { ok.repairer.selectionChanged(ACCOUNT, setOf(libA), 1, atMs) }
        assertIs<RepairOutcome.Saved<List<Pin>>>(saved)
    }

    @Test
    fun concurrentCalls_takeTurns_soNoneIsStale() {
        val rig = lostRig()
        val outcomes =
            runBlocking {
                listOf(
                    async { rig.repairer.repairLost(ACCOUNT, setOf(libA), atMs) },
                    async { rig.repairer.selectionChanged(ACCOUNT, setOf(libA, libB), 9, atMs) },
                    async { rig.repairer.repairLost(ACCOUNT, setOf(libA), atMs) },
                ).awaitAll()
            }

        assertTrue(outcomes.all { it is RepairOutcome.Saved<*> }, "$outcomes")
        assertEquals(9L, rig.record().latestSeq())
    }

    @Test
    fun theRepairerAndTheService_shareOneConfig() {
        val rig = Rig(lineupOf(laughs), comedies, setOf(libA))
        assertTrue(rig.repairer.config === rig.service.repairConfig)
        assertEquals(rig.service.repairConfig.pauseHold, rig.repairer.pauseHold)
    }
}

/** A third library a change adds. */
internal val LIB_C_ID: CanonicalId = CanonicalId.parse("e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1")
