// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.RefreshMeta
import tv.stationbreak.core.domain.lineup.USER
import tv.stationbreak.core.domain.lineup.id
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.engine.lineup.Lineup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Onboarding's library choice (spec 26, Part 4 §2 "setup"): every sign-in clears it, and Done stores it through the
 * repairer's reset, so no repair of an earlier choice ever shows over it.
 */
internal class LibrarySetupTest : SelectionTestBase() {
    // A TV whose earlier choice made a repair (an untick of B, on air), then was cleared by a sign-in.
    private fun signedInAgain(): Tv {
        val tv = Tv().ready(setOf(libA, libB))
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))
        assertTrue(
            tv.rig
                .record()
                .selection
                .isNotEmpty(),
            "the untick's repair is recorded",
        )
        assertTrue(runBlocking { tv.selection.clear() })
        assertNull(tv.stored())
        return tv
    }

    @Test
    fun choose_savesTheChoiceAsAReset_withNothingBeingAdded() {
        val tv = signedInAgain()
        val kept = assertNotNull(runBlocking { tv.settings.loadSelectionSeq() })

        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libA, libC)) })

        val stored = assertNotNull(tv.stored())
        assertEquals(setOf(libA, libC), stored.chosen)
        assertTrue(stored.seq > kept)
        assertEquals(stored.seq, stored.resetSeq)
        assertTrue(stored.adding.isEmpty() && stored.missing.isEmpty() && !stored.gone)
        assertEquals(stored.seq, tv.selection.selectionChanges.value)
        assertEquals(ChoiceView(setOf(libA, libC), emptySet(), gone = false), tv.selection.selection.value)
    }

    @Test
    fun signOutSetupRestart_oldRepairIgnored() {
        val tv = signedInAgain()

        runBlocking { tv.selection.choose(setOf(libA, libB)) }

        assertTrue(
            tv.rig
                .record()
                .selection
                .isEmpty(),
            "the record was emptied before the save",
        )
        val stored = assertNotNull(tv.stored())
        // After a restart the same store is read again: nothing below the reset seq can show.
        val after =
            tv.rig.restarted(
                assertNotNull(
                    tv.rig.service
                        .lineup(ACCOUNT)
                        .value,
                ).lineup,
            )
        val shown = after.record().applied(stored.seq, stored.resetSeq).selection
        assertTrue(shown.isEmpty())
    }

    @Test
    fun otherAccountSetup_oldAccountsRepairsNeverShow() {
        val tv = signedInAgain()
        // The file at this account's place is another account's (a switch of user left it): set aside, never a floor.
        val others = Lineup(id(9), USER, 1, 0, finalised = false, channels = emptyList())
        runBlocking {
            tv.rig.store.inner
                .save(ACCOUNT, LineupCodec.encode(others, RefreshMeta(), TodayRepairs(lastSeq = 50L)))
        }
        val kept = assertNotNull(runBlocking { tv.settings.loadSelectionSeq() })

        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libA)) })

        assertNull(
            tv.rig.store.inner
                .peek(ACCOUNT),
            "set aside",
        )
        val stored = assertNotNull(tv.stored())
        assertEquals(kept + 1, stored.seq, "another account's seqs are no floor")
        assertEquals(stored.seq, stored.resetSeq)
    }

    @Test
    fun setup_recordReadFails_failsAndOldRepairsNeverShow() {
        val tv = signedInAgain()
        val kept = runBlocking { tv.settings.loadSelectionSeq() }
        tv.rig.store.inner.failReads = true

        assertEquals(SetupResult.Failed, runBlocking { tv.selection.choose(setOf(libA)) })

        assertNull(tv.stored(), "nothing chosen, so nothing shows")
        assertEquals(kept, runBlocking { tv.settings.loadSelectionSeq() })
        assertEquals(0, tv.firstSync.ensureCalls)
    }

    // Binding item 5 (the same account, its first sync long finished): setup must start the first sync itself.
    @Test
    fun sameAccountSetup_withLastSuccess_startsFirstSync_afterTheSaveCommits() {
        val tv = signedInAgain()
        tv.settings.failSaves = 1

        assertEquals(SetupResult.Failed, runBlocking { tv.selection.choose(setOf(libA, libC)) })
        assertEquals(0, tv.firstSync.ensureCalls, "nothing saved, nothing to read")

        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libA, libC)) })
        assertEquals(1, tv.firstSync.ensureCalls)
        assertTrue(tv.restarts >= 1, "any add under way is cancelled")
    }

    @Test
    fun setup_landsAProvenStampedRow_andRevertsAnUnprovenOne() {
        val stored = StoredSelection(setOf(libA), seq = 4L, adding = mapOf(libB to 3L, libC to 4L))
        val tv = Tv(stored = stored, chosen = setOf(libA))
        tv.stampInRoom(libB, 3L, setOf(libA))
        tv.fake.proveWalk(ACCOUNT, libB, 3L)
        tv.stampInRoom(libC, 4L, setOf(libA, libB))
        tv.ready(setOf(libA, libB, libC))
        runBlocking { tv.selection.clear() }

        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libA, libB, libC)) })

        assertEquals(listOf(libB to 3L), tv.library.landed)
        assertEquals(listOf(libC to 4L), tv.library.reverted)
        assertTrue(assertNotNull(tv.row(libC)).rewalkPending)
        assertEquals(1, tv.stayCurrentRequests, "the reverted library is read again soon")
    }

    @Test
    fun anEmptyChoice_isNeverStored() {
        val tv = signedInAgain()

        assertEquals(SetupResult.Failed, runBlocking { tv.selection.choose(emptySet()) })

        assertNull(tv.stored())
    }

    @Test
    fun clear_dropsTheChoice_keepsTheSeq_andCancelsTheAdds() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        tv.change(setOf(libA, libB, libC))
        val seq = assertNotNull(tv.stored()).seq
        val restarts = tv.restarts

        assertTrue(runBlocking { tv.selection.clear() })

        assertNull(tv.stored())
        assertEquals(seq, runBlocking { tv.settings.loadSelectionSeq() })
        assertEquals(restarts + 1, tv.restarts)
        assertNull(tv.selection.selection.value)
    }
}
