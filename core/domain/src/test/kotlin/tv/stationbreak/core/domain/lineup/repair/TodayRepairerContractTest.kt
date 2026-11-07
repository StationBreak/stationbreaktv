// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.ChannelDay
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.leftovers
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.domain.lineup.selectionOf
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    // The days a tune sees now, read in a save callback (no runBlocking: the repairer's thread is in use).
    private suspend fun Rig.daysNow(at: Long): List<ChannelDay> =
        source.channelDays(ACCOUNT, selectionOf(chosen, applied)!!, Instant.fromEpochMilliseconds(at))

    @Test
    fun aRepairWhoseSelectionIsNotSaved_isHeldBack_untilItsSeqIsSaved() {
        val items = comedies + crimes + comediesB + crimesB
        val plain = Rig(lineupOf(laughs, whodunit), items, setOf(libA, libB))
        val rig = Rig(lineupOf(laughs, whodunit), items, setOf(libA, libB))
        val onAir = rig.day(laughs.id).blockAt(atMs)
        val unrepaired = listOf(laughs.id, whodunit.id).associateWith { plain.day(it).day.blocks }

        val outcome =
            rig.changeOutcome(before = setOf(libA, libB), after = setOf(libA)) { seq ->
                // Recorded, but the saved selection's seq is below it: the days are built as without the repair.
                assertEquals(1L, seq)
                assertEquals(0L, rig.applied)
                for (day in rig.daysNow(atMs)) {
                    assertNull(day.record?.repair, "no repair is laid over the day")
                    assertEquals(unrepaired.getValue(day.channel.id), day.day.blocks, "the unrepaired day")
                }
            }

        assertEquals(1L, assertIs<RepairOutcome.Saved<SelectionRepaired>>(outcome).result.seq)
        val repaired = rig.day(laughs.id)
        assertEquals(rig.repairOf(laughs.id), repaired.record?.repair)
        assertEquals(onAir, repaired.blockAt(atMs), "the repaired day, its programme on air kept")
    }

    @Test
    fun theGate_holdsBackASelectionRepair_withItsFixes() {
        val rig = lostRig()
        assertTrue(rig.lost(setOf(libA)) > 0)
        assertTrue(rig.record().fixes.all { it.seq == 3L }, "the fixes patch the repair shown")
        val shown = rig.day(laughs.id)
        assertEquals(rig.record().fixes, shown.record?.fixes)

        rig.applied = 2

        val day = rig.day(laughs.id)
        assertNull(day.record?.repair, "the seq-3 repair is held back")
        assertEquals(emptyList(), day.record?.fixes.orEmpty(), "and its fixes with it: they patch its blocks only")
    }

    @Test
    fun twoChangesInOneDay_theSecondShowsOnlyOnceSaved_andAFailedSaveGoesBackToTheFirst() {
        val lineup = lineupOf(laughs, whodunit)
        val rig = Rig(lineup, comedies + crimes + comediesB + crimesB, setOf(libA, libB))
        val first = assertNotNull(rig.changed(before = setOf(libA, libB), after = setOf(libA))).seq
        val firstRecord = rig.record()
        val secondAt = atMs + 40 * minute

        fun firstOnAir(day: ChannelDay): ScheduleBlock {
            val repair = firstRecord.selection.single { it.channel == day.channel.id }
            val blocks = RepairedDay.compose(day.epochStartMs, day.epochEndMs, emptyList(), repair, emptyList()).blocks
            return blocks.first { secondAt >= it.startMs && secondAt < it.endMs }
        }

        // Recorded, not saved: every channel still airs the first repair, read from the record alone.
        val failed =
            rig.changeOutcome(before = setOf(libA), after = setOf(libA, libB), at = secondAt, save = false) { seq ->
                assertTrue(seq > first)
                assertEquals(2, rig.record().selection.count { it.channel == laughs.id }, "both repairs are held")
                val reads = rig.library.snapshotCutoffs.size
                for (day in rig.daysNow(secondAt)) {
                    assertEquals(first, day.record?.repair?.seq)
                    assertEquals(firstOnAir(day), day.blockAt(secondAt))
                }
                assertEquals(reads, rig.library.snapshotCutoffs.size, "no snapshot is read")
            }

        assertEquals(RepairOutcome.Unsaved, failed)
        assertEquals(firstRecord.selection, rig.record().selection, "the record is as it was")
        assertEquals(firstRecord.fixes, rig.record().fixes)
        for (day in rig.days(secondAt)) assertEquals(first, day.record?.repair?.seq, "the first shows again")

        // Saved: the second shows.
        val second = assertNotNull(rig.changed(before = setOf(libA), after = setOf(libA, libB), at = secondAt)).seq
        for (day in rig.days(secondAt)) assertEquals(second, day.record?.repair?.seq)

        // Part 4's start-up resume drops a change a crash left unsaved: the first shows again.
        rig.applied = first
        rig.chosen = setOf(libA)
        assertEquals(RepairOutcome.Saved(true), runBlocking { rig.repairer.dropSelection(ACCOUNT, second) })
        for (day in rig.days(secondAt)) {
            assertEquals(first, day.record?.repair?.seq)
            assertEquals(firstOnAir(day), day.blockAt(secondAt))
        }
    }

    @Test
    fun aSaveThatThrows_leavesTheRecordAsItWas_andTheErrorGoesToTheCaller() {
        val rig = Rig(lineupOf(laughs, whodunit), comedies + crimes + comediesB, setOf(libA, libB))
        assertNotNull(rig.changed(before = setOf(libA, libB), after = setOf(libA)))
        val before = rig.record()

        val thrown =
            assertFailsWith<IllegalStateException> {
                rig.changeOutcome(before = setOf(libA), after = setOf(libA, libB), fail = IllegalStateException("x"))
            }

        assertEquals("x", thrown.message)
        assertEquals(before.selection, rig.record().selection)
        assertEquals(before.fixes, rig.record().fixes)
        assertEquals(1L, rig.applied)
    }

    @Test
    fun aSecondChange_dropsTheRepairsOfAChangeNeverSaved_andPrunesWhatTheSavedSeqSuperseded() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        val first = assertNotNull(rig.changed(before = setOf(libA, libB), after = setOf(libA))).seq
        // A crash left a change of seq 9 recorded and never saved.
        val stray = rig.repairOf(laughs.id).copy(seq = 9)
        val seeded = { held: TodayRepairs -> held.copy(selection = listOf(held.selection.single(), stray)) }
        assertTrue(runBlocking { rig.service.recordRepairs(ACCOUNT) { seeded(it.repairs) } })

        val second = assertNotNull(rig.changed(before = setOf(libA), after = setOf(libA, libB))).seq

        assertEquals(10L, second, "above every seq held")
        assertEquals(listOf(first, second), rig.record().selection.map { it.seq }, "the stray is gone")
        rig.lost(setOf(libA))
        assertEquals(listOf(second), rig.record().selection.map { it.seq }, "the next write prunes the first too")
    }

    @Test
    fun repairLost_dropsASelectionRepairAboveTheSavedSeq_thenChecks() {
        // A crash (or an undo that failed) left the seq-3 repair recorded above the saved seq 2, with a fix of it.
        val rig = lostRig()
        assertTrue(rig.lost(setOf(libA)) > 0)
        rig.applied = 2
        val onAir = rig.day(laughs.id).blockAt(atMs)
        val reads = rig.library.currentIdsCalls

        runBlocking { rig.repairer.repairLost(ACCOUNT, setOf(libA), atMs + minute) }

        assertEquals(emptyList(), rig.record().selection, "the leftover is dropped")
        assertTrue(rig.record().fixes.none { it.seq == 3L }, "with its fixes")
        assertNotNull(rig.record().lostCheck, "and the check ran")
        assertTrue(rig.library.currentIdsCalls > reads, "over the days as they show")
        assertEquals(onAir, rig.day(laughs.id).blockAt(atMs), "what shows did not change")
    }

    @Test
    fun afterTheLastSelectionRepairRollsOff_theFileIsVersion3_andTheNextSeqIsHigher() {
        val rig = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        // A change on a day that has already ended: the record rolls it off at once.
        val yesterday = atMs - 24 * hour
        val first = assertNotNull(rig.changed(before = setOf(libA, libB), after = setOf(libA), at = yesterday)).seq
        assertEquals(first, rig.applied, "saved with the selection")
        assertEquals(TodayRepairs.NONE, rig.record())
        val bytes = assertNotNull(rig.store.inner.peek(ACCOUNT))
        assertEquals(3, assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes)).fileVersion)

        val next = assertNotNull(rig.changed(before = setOf(libA), after = setOf(libA, libB))).seq

        assertTrue(next > first, "the saved selection's seq keeps the next one above it: $first then $next")
        assertEquals(next, rig.repairOf(laughs.id).seq)
        assertNotNull(rig.day(laughs.id).record?.repair, "and it shows once saved")
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
    fun outcomes_noLineup_refused_unsaved_saved() {
        val none = Rig(lineupOf(laughs), comedies, setOf(libA))
        runBlocking { none.service.forget(ACCOUNT) }
        val noLineup = runBlocking { none.repairer.selectionChanged(ACCOUNT, setOf(libA), atMs) { true } }
        assertEquals(RepairOutcome.NoLineup, noLineup)

        val failing = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        failing.store.inner.failWrites = true
        val refused = runBlocking { failing.repairer.selectionChanged(ACCOUNT, setOf(libA), atMs) { true } }
        assertEquals(RepairOutcome.Refused, refused)

        val unsaved = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        assertEquals(RepairOutcome.Unsaved, unsaved.changeOutcome(setOf(libA, libB), setOf(libA), save = false))
        assertEquals(TodayRepairs.NONE, unsaved.record())

        val ok = Rig(lineupOf(laughs), comedies + comediesB, setOf(libA, libB))
        val saved = runBlocking { ok.repairer.selectionChanged(ACCOUNT, setOf(libA), atMs) { true } }
        assertEquals(1L, assertIs<RepairOutcome.Saved<SelectionRepaired>>(saved).result.seq)
    }

    @Test
    fun concurrentCalls_takeTurns_soNoneIsStale() {
        val rig = lostRig()
        val outcomes =
            runBlocking {
                listOf(
                    async { rig.repairer.repairLost(ACCOUNT, setOf(libA), atMs) },
                    async { rig.repairer.selectionChanged(ACCOUNT, setOf(libA, libB), atMs) { true } },
                    async { rig.repairer.repairLost(ACCOUNT, setOf(libA), atMs) },
                ).awaitAll()
            }

        assertTrue(outcomes.all { it is RepairOutcome.Saved<*> }, "$outcomes")
        assertEquals(4L, rig.record().latestSeq(), "one above the seeded, saved seq 3")
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
