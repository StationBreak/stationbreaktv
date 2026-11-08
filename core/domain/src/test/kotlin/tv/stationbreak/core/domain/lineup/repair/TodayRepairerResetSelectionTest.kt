// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.RefreshMeta
import tv.stationbreak.core.domain.lineup.SERVER
import tv.stationbreak.core.domain.lineup.USER
import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A setup's save (spec 26, Part 4 §2): `resetSelection` empties the record through `LineupService.resetRecord`, for
 * every state the lineup file can be in, takes the seq one above the stored seq, the old record's latest seq and the
 * floor below `atLeast`, and saves it (as the reset seq too). An old repair never shows after a setup.
 */
internal class TodayRepairerResetSelectionTest : RepairTestBase() {
    private val lineup = lineupOf(laughs, whodunit)
    private val items = comedies + crimes + comediesB

    // A TV whose change from {A, B} to {A} saved seq 1 (shown), then whose change back failed with its undo too
    // (seq 2, above the saved seq: hidden). Then the viewer signed out: no selection, the seq kept.
    private fun signedOut(): Rig {
        val rig = Rig(lineup, items, setOf(libA, libB))
        assertNotNull(rig.changed(before = setOf(libA, libB), after = setOf(libA)))
        val failed =
            rig.changeOutcome(before = setOf(libA), after = setOf(libA, libB), save = false) {
                rig.store.inner.failWrites = true
            }
        assertEquals(RepairOutcome.UndoFailed, failed)
        rig.store.inner.failWrites = false
        assertEquals(setOf(1L, 2L), rig.record().selection.mapTo(HashSet()) { it.seq })
        rig.unknown = true
        return rig
    }

    // The setup's save: [chosen] and the seq (as the reset seq too) when [commit], with what was asked for.
    @Suppress("LongParameterList") // A setup as a test drives it, each part defaulted.
    private fun Rig.setup(
        account: LibraryAccount = ACCOUNT,
        atLeast: Long = 0L,
        chosen: Set<ItemId> = setOf(libA),
        commit: Boolean = true,
        returns: Boolean = commit,
        asked: MutableList<Long> = mutableListOf(),
    ): RepairOutcome<Long> =
        runBlocking {
            repairer.resetSelection(account, atLeast) { seq ->
                asked += seq
                if (commit) {
                    this@setup.chosen = chosen
                    applied = seq
                    resetSeq = seq
                    unknown = false
                }
                returns
            }
        }

    private fun Rig.file(): ByteArray? = store.inner.peek(ACCOUNT)

    // --- Every state of the file ----------------------------------------------------------------------------------

    @Test
    fun ownReadableRecord_isEmptied_andTheSeqIsAboveItsLatest() {
        val rig = signedOut()
        val asked = mutableListOf<Long>()

        assertEquals(RepairOutcome.Saved(3L), rig.setup(asked = asked), "above the stored 1 and the record's 2")

        assertEquals(listOf(3L), asked)
        assertEquals(TodayRepairs.NONE, rig.record())
        val stored = LineupCodec.decode(checkNotNull(rig.file())) as LineupCodec.Decoded.Loaded
        assertEquals(TodayRepairs.NONE, stored.repairs, "on disk too")
        assertNull(rig.day(laughs.id).record?.repair)
    }

    @Test
    fun noFile_takesTheStoredSeqPlusOne() {
        val rig = signedOut()
        rig.applied = 5L
        runBlocking { rig.store.inner.forget(ACCOUNT) }

        assertEquals(RepairOutcome.Saved(6L), rig.setup())
        assertNull(rig.file(), "nothing is written")
    }

    @Test
    fun anUnreadableFile_isSetAside_andTheHeldRecordFloorsTheSeq() {
        val rig = signedOut()
        val garbage = "not a lineup".encodeToByteArray()
        runBlocking { rig.store.inner.save(ACCOUNT, garbage) }

        // The record this session still holds (seq 2, hidden) floors the seq, and is emptied.
        assertEquals(RepairOutcome.Saved(3L), rig.setup())
        assertEquals(TodayRepairs.NONE, rig.service.repairs(ACCOUNT).value)
        assertNull(rig.file())
        assertContentEquals(garbage, rig.store.inner.setAsideCopy(ACCOUNT))
    }

    @Test
    fun anotherAccountsFile_isSetAside_andItsSeqsAreNoFloor() {
        val rig = signedOut()
        val other = Lineup(id(9), USER, 1, 0, finalised = false, channels = emptyList())
        val seqs = TodayRepairs(selection = listOf(repairOf(seq = 40L)))
        runBlocking { rig.store.inner.save(ACCOUNT, LineupCodec.encode(other, RefreshMeta(), seqs)) }

        // The record this session still holds (seq 2, hidden) floors the seq, and is emptied.
        assertEquals(RepairOutcome.Saved(3L), rig.setup())
        assertEquals(TodayRepairs.NONE, rig.service.repairs(ACCOUNT).value)
        assertNull(rig.file())
    }

    @Test
    fun aNewerAppsFile_isLeftUntouched_andTheHeldRecordFloorsTheSeq() {
        val rig = signedOut()
        val newer = """{"version":5}""".encodeToByteArray()
        runBlocking { rig.store.inner.save(ACCOUNT, newer) }

        // The record this session still holds (seq 2, hidden) floors the seq, and is emptied.
        assertEquals(RepairOutcome.Saved(3L), rig.setup())
        assertEquals(TodayRepairs.NONE, rig.service.repairs(ACCOUNT).value)
        assertContentEquals(newer, rig.file())
        assertEquals(3L, rig.resetSeq, "the reset seq is the seq saved: a newer build hides what is below it")
    }

    @Test
    fun setup_recordReadFails_failsAndOldRepairsNeverShow() {
        val rig = signedOut()
        val before = checkNotNull(rig.file())
        rig.store.inner.failReads = true
        val asked = mutableListOf<Long>()

        assertEquals(RepairOutcome.Refused, rig.setup(asked = asked))

        assertEquals(emptyList(), asked, "nothing is saved: the setup fails")
        assertTrue(rig.unknown, "no selection: the source shows nothing")
        val airings = runBlocking { rig.source.airings(Instant.fromEpochMilliseconds(atMs)) }
        assertEquals(emptyList(), airings, "no old repair shows, nor anything else")
        rig.store.inner.failReads = false
        assertContentEquals(before, rig.file(), "the file stays as it was")
    }

    @Test
    fun anEmptiedRecordThatCannotBeWritten_failsTheSetup() {
        val rig = signedOut()
        val before = checkNotNull(rig.file())
        rig.store.inner.failWrites = true
        val asked = mutableListOf<Long>()

        assertEquals(RepairOutcome.Refused, rig.setup(asked = asked))

        assertEquals(emptyList(), asked)
        rig.store.inner.failWrites = false
        assertContentEquals(before, rig.file())
    }

    @Test
    fun aSetAsideThatFails_failsTheSetup() {
        val rig = signedOut()
        runBlocking { rig.store.inner.save(ACCOUNT, "not a lineup".encodeToByteArray()) }
        rig.store.inner.failWrites = true
        val asked = mutableListOf<Long>()

        assertEquals(RepairOutcome.Refused, rig.setup(asked = asked))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun anUnknownStoredSeq_isSelectionUnknown_andTheRecordIsLeftAlone() {
        val rig = signedOut()
        val before = rig.record()
        rig.storedSeq = { null }
        val asked = mutableListOf<Long>()

        assertEquals(RepairOutcome.SelectionUnknown, rig.setup(asked = asked))

        assertEquals(emptyList(), asked)
        assertEquals(before, rig.record())
    }

    // --- The seq --------------------------------------------------------------------------------------------------

    @Test
    fun theSeq_isFlooredAtAtLeast() {
        val rig = signedOut()

        assertEquals(RepairOutcome.Saved(20L), rig.setup(atLeast = 20L))
    }

    @Test
    fun theLowestAtLeast_neverWraps() {
        assertEquals(RepairOutcome.Saved(3L), signedOut().setup(atLeast = Long.MIN_VALUE))
    }

    @Test
    fun aSaveThatReturnsFalse_isSaved_onlyWhenTheStoredSeqReachedIt() {
        assertEquals(RepairOutcome.Unsaved, signedOut().setup(commit = false))
        assertEquals(RepairOutcome.Saved(3L), signedOut().setup(commit = true, returns = false))
    }

    // --- Sign-out, then setup -------------------------------------------------------------------------------------

    @Test
    fun signOutSetupRestart_oldRepairIgnored() {
        val rig = signedOut()

        assertEquals(RepairOutcome.Saved(3L), rig.setup(chosen = setOf(libA, libB)))
        val restarted = rig.restarted(lineup)

        assertEquals(setOf(libA, libB), restarted.chosen)
        assertEquals(TodayRepairs.NONE, restarted.record(), "read from the file after the restart")
        assertNull(restarted.day(laughs.id).record?.repair, "neither the shown seq-1 repair nor the hidden seq-2 one")
        assertNull(restarted.day(whodunit.id).record?.repair)
    }

    @Test
    fun otherAccountSetup_oldAccountsRepairsNeverShow() {
        val rig = signedOut()
        val other = LibraryAccount(ServerId(SERVER.value), UserId(id(77).value))

        // Another account is set up on this TV first: it has no lineup file, so its seq is the stored seq plus one,
        // the seq of the first account's hidden repair.
        assertEquals(RepairOutcome.Saved(2L), rig.setup(account = other))
        assertEquals(setOf(1L, 2L), rig.record().selection.mapTo(HashSet()) { it.seq }, "the first account's record")

        // Then the first account signs in again and is set up: its record goes, whatever seq it held.
        rig.unknown = true
        assertEquals(RepairOutcome.Saved(3L), rig.setup(chosen = setOf(libA, libB)))
        val restarted = rig.restarted(lineup)

        assertEquals(TodayRepairs.NONE, restarted.record())
        assertNull(restarted.day(laughs.id).record?.repair)
        assertNull(restarted.day(whodunit.id).record?.repair)
    }
}
