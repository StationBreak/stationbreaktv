// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.ACCOUNT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The selection's edges (spec 26, Part 4 §2; Task 4.3b review): stamps the cache would refuse, races with the add
 * pass, the raise's other branches, writes that land but report false, and what a sign-in must not carry over.
 */
internal class LibrarySelectionEdgesTest : SelectionTestBase() {
    // C = {A, B}; B's add was never proven, so the resume set it back: chosen, pending, marked for a rewalk.
    private fun reverted(): Tv {
        val tv = Tv(chosen = setOf(libA), stored = StoredSelection(setOf(libA, libB), seq = 1L))
        tv.stampInRoom(libB, 1L, setOf(libA))
        tv.seedItems(comediesB)
        tv.ready(setOf(libA, libB))
        assertTrue(assertNotNull(tv.row(libB)).rewalkPending)
        return tv
    }

    // D1: the resume's cache write holds a refused row first, then stamps it; nothing is dropped, nothing loops.
    @Test
    fun aRefusedStampAtResume_isHeldThenStamped_neverLeftOut() {
        val tv = reverted()
        // The untick's cache write fails: the row stays as set back.
        tv.library.failApplies = 1
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))
        // The re-tick's catch-up and its own first cache write both fail too.
        tv.library.failApplies = 2
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))
        val stamp = assertNotNull(tv.stored()).adding.getValue(libB)
        assertNull(tv.row(libB)?.addStamp, "the cache still has it as set back")
        val before = tv.library.applied.size

        runBlocking { tv.restartedSelection().resumeAtStart() }

        val writes = tv.library.applied.drop(before)
        assertEquals(2, writes.size, "a hold, then the stamp")
        assertFalse(libB in writes[0].keep)
        assertEquals(stamp, writes[1].addStamps[libB])
        assertEquals(stamp, tv.row(libB)?.addStamp)
        assertNull(tv.row(libB)?.heldUntilMs)
        assertTrue(SelectionEvent.HeldBeforeStamp in tv.events)
    }

    // D2: a lineup on disk this build reads, while the one held is not writable: no raise, reported, and retried by
    // the next operation's floor.
    @Test
    fun theRaise_overALineupNotHeldAsWritable_isSkipped() {
        val tv = Tv(stored = StoredSelection(setOf(libA, libB), seq = 2L, adding = mapOf(libC to 2L)))
        tv.stampInRoom(libC, 7L, setOf(libA, libB))
        val readable =
            assertNotNull(
                tv.rig.store.inner
                    .peek(ACCOUNT),
            )
        runBlocking {
            // The service first met a newer app's file, so it holds nothing it may write; the file is readable now.
            tv.rig.store.inner
                .save(ACCOUNT, """{"version":99}""".encodeToByteArray())
            tv.rig.service.loaded(ACCOUNT)
            tv.rig.store.inner
                .save(ACCOUNT, readable)
        }

        tv.resume()

        assertEquals(2L, assertNotNull(tv.stored()).seq)
        assertTrue(SelectionEvent.RaiseSkipped in tv.events)
    }

    @Test
    fun theRaise_whenTheLineupCannotBeRead_isSkipped() {
        val tv = Tv(stored = StoredSelection(setOf(libA, libB), seq = 2L, adding = mapOf(libC to 2L)))
        tv.stampInRoom(libC, 7L, setOf(libA, libB))
        tv.rig.store.inner.failReads = true

        tv.resume()

        assertEquals(2L, assertNotNull(tv.stored()).seq)
        assertTrue(SelectionEvent.RaiseSkipped in tv.events)
    }

    // D2: a write that lands and reports false is a commit (the stored seq reads back as the one written).
    @Test
    fun aRaiseWhoseWriteLandsButReportsFalse_isCommitted() {
        val tv = Tv(stored = StoredSelection(setOf(libA, libB), seq = 2L, adding = mapOf(libC to 2L)))
        tv.stampInRoom(libC, 7L, setOf(libA, libB))
        runBlocking { tv.rig.service.forget(ACCOUNT) }
        tv.settings.falseAfterWriting = 1

        tv.resume()

        assertEquals(8L, assertNotNull(tv.stored()).seq)
        assertFalse(SelectionEvent.RaiseSkipped in tv.events)
    }

    @Test
    fun aPlainSaveWhoseWriteLandsButReportsFalse_isSaved() {
        val tv = Tv()
        runBlocking { tv.rig.service.forget(ACCOUNT) }
        tv.ready(setOf(libA, libB, libC))
        tv.settings.falseAfterWriting = 1

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB, libC)))

        val stored = assertNotNull(tv.stored())
        assertEquals(mapOf(libC to stored.seq), stored.adding)
        assertEquals(stored.seq, tv.row(libC)?.addStamp, "the cache followed the commit")
    }

    @Test
    fun aRepairedSaveWhoseWriteLandsButReportsFalse_isSaved() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        tv.settings.falseAfterWriting = 1

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB, libC)))

        assertEquals(setOf(libC), assertNotNull(tv.stored()).adding.keys)
    }

    // D3: the add pass proves the walk between the row read and the set-back: the row lands, never left stamped.
    @Test
    fun aSetBackThatLosesToTheWalksProof_landsTheRow() {
        val tv = Tv(chosen = setOf(libA), stored = StoredSelection(setOf(libA, libB), seq = 1L))
        tv.stampInRoom(libB, 1L, setOf(libA))
        tv.library.beforeRevert = { id, stamp -> tv.fake.proveWalk(ACCOUNT, id, stamp) }

        tv.resume()

        assertTrue(tv.library.reverted.isEmpty())
        assertEquals(listOf(libB to 1L), tv.library.landed)
        assertNull(tv.row(libB)?.addStamp)
    }

    // D4: a plain save over a newer app's file skips a seq, as the raise does.
    @Test
    fun aPlainSaveOverANewerAppsLineup_skipsASeq_andSavesTheResetSeq() {
        val tv = Tv()
        runBlocking {
            tv.rig.store.inner
                .save(ACCOUNT, """{"version":99}""".encodeToByteArray())
        }
        tv.ready(setOf(libA, libB, libC))

        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB, libC)))

        val stored = assertNotNull(tv.stored())
        assertEquals(3L, stored.seq, "2 is skipped")
        assertEquals(3L, stored.resetSeq)
    }

    // D5: a setup only ever follows a clear.
    @Test
    fun aSetupOverAStoredChoice_isRefused() {
        val tv = Tv().ready(setOf(libA, libB))
        val stored = tv.stored()

        assertEquals(SetupResult.Failed, runBlocking { tv.selection.choose(setOf(libA)) })

        assertEquals(stored, tv.stored())
    }

    // D6: once the setup's save has committed, a step after it that fails does not fail the setup.
    @Test
    fun aSetupWhoseRestartFails_isStillSaved_andStillStartsTheFirstSync() {
        val tv = Tv().ready(setOf(libA, libB))
        assertTrue(runBlocking { tv.selection.clear() })
        tv.failRestart = true

        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libA, libC)) })

        assertEquals(setOf(libA, libC), assertNotNull(tv.stored()).chosen)
        assertEquals(1, tv.firstSync.ensureCalls)
        assertTrue(SelectionEvent.SetupFollowUpFailed in tv.events)
    }

    // A4: a clear comes after the next account is active; the old account's selection must not reach its cache.
    @Test
    fun aClear_afterTheNextAccountIsActive_writesNothingToItsCache() {
        val tv = Tv().ready(setOf(libA, libB))
        tv.library.failApplies = 1
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)), "saved; the cache is left behind")
        runBlocking { tv.fake.useAccount(other) }
        val calls = tv.library.applyCalls

        assertTrue(runBlocking { tv.selection.clear() })

        assertEquals(calls, tv.library.applyCalls)
    }

    // A5: the last account's listing does not let this choice's adds through.
    @Test
    fun aListingBeforeASetup_doesNotCountForTheChoiceAfterIt() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        runBlocking { tv.selection.clear() }
        // A listing that came between the clear and the setup (the last account's, or a stale one) counts neither.
        tv.listing(setOf(libA, libB, libC))
        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libA)) })

        assertEquals(ChangeResult.NotListed, tv.change(setOf(libA, libC)))
        tv.listing(setOf(libA, libC))
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libC)))
    }

    // N3: a resume called from inside the selection's own work is a bug, thrown as it is, not a failed resume.
    @Test
    fun aResumeFromInside_isThrown_notReportedAsAFailedResume() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        var thrown: Throwable? = null
        tv.settings.beforeSave = { thrown = runCatching { tv.selection.resumeAtStart() }.exceptionOrNull() }

        tv.change(setOf(libA, libB, libC))

        assertTrue(thrown is IllegalStateException)
        assertFalse(SelectionEvent.SelectionResumeFailed in tv.events)
    }
}
