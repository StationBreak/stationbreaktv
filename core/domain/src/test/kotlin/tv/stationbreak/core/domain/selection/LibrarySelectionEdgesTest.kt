// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.model.ItemId
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

    // D5: a setup only ever follows a clear; one over a stored choice says so, so its caller can clear again.
    @Test
    fun aSetupOverAStoredChoice_isRefused_asAChoiceStillStored() {
        val tv = Tv().ready(setOf(libA, libB))
        val stored = tv.stored()

        assertEquals(SetupResult.ChoiceStillStored, runBlocking { tv.selection.choose(setOf(libA)) })

        assertEquals(stored, tv.stored())
    }

    // Fix round 2, item 2: a failed clear is reported, and the next clear and setup go through.
    @Test
    fun aFailedClear_isReported_andTheNextClearThenSetupGoThrough() {
        val tv = Tv().ready(setOf(libA, libB))
        tv.settings.inner.failSave = true

        assertFalse(runBlocking { tv.selection.clear() })
        assertTrue(SelectionEvent.ClearFailed in tv.events)
        assertEquals(SetupResult.ChoiceStillStored, runBlocking { tv.selection.choose(setOf(libA)) })

        tv.settings.inner.failSave = false
        assertTrue(runBlocking { tv.selection.clear() })
        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libA)) })
    }

    // Fix round 2, item 1: the open half of D1, with a FETCHED row. A read chosen library is unticked (its cache
    // write fails), ticked again (the catch-up and the hold write fail too), and the next operation must hold it,
    // then stamp it, and keep the tick: the add stays in A.
    @Test
    fun aFetchedRow_unticked_thenTickedAgain_throughFailedCacheWrites_staysAnAdd_heldThenStamped() {
        val tv = Tv().ready(setOf(libA, libB))
        assertTrue(assertNotNull(tv.row(libB)).fetched)
        tv.library.failApplies = 1
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA)))
        tv.library.failApplies = 2
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))
        val stamp = assertNotNull(tv.stored()).adding.getValue(libB)
        val row = assertNotNull(tv.row(libB))
        assertTrue(row.fetched && row.addStamp == null && row.heldUntilMs == null, "still the read chosen row")
        val before = tv.library.applied.size

        runBlocking { tv.selection.reconcile() }

        assertEquals(mapOf(libB to stamp), assertNotNull(tv.stored()).adding, "the re-tick stands")
        val writes = tv.library.applied.drop(before)
        assertEquals(2, writes.size, "a hold, then the stamp")
        assertFalse(libB in writes[0].keep)
        assertEquals(stamp, writes[1].addStamps[libB])
        assertEquals(stamp, tv.row(libB)?.addStamp)
    }

    // Fix round 2, item 5 (arch L3): a selection that never resumed (built after the start's resume failed, its gate
    // opened by the start) puts the cache in line on its first operation.
    @Test
    fun aSelectionThatNeverResumed_catchesUpOnItsFirstOperation() {
        // A record this build wrote, so it names its owner (an older one is adopted only by a resume: round 4).
        val stored = StoredSelection(setOf(libA, libB), seq = 1L, owner = ACCOUNT)
        val tv = Tv(chosen = setOf(libA), stored = stored)
        tv.stampInRoom(libB, 1L, setOf(libA))
        tv.gate.open()

        tv.listing(setOf(libA, libB))

        assertEquals(listOf(libB to 1L), tv.library.reverted, "the unproven chosen row was branched")
    }

    // D6: once the setup's save has committed, a step after it that fails does not fail the setup.
    @Test
    fun aSetupWhoseRestartFails_isStillSaved_andStillStartsTheFirstSync() {
        val tv = Tv().ready(setOf(libA, libB))
        // Another account's choice with an add under way is still stored (its sign-in's clear failed): the setup
        // replaces it, and asks for the add pass to stop that add.
        val foreign = StoredSelection(setOf(libA), seq = 5L, adding = mapOf(libB to 5L), owner = other)
        assertTrue(runBlocking { tv.settings.inner.saveSelection(foreign) })
        tv.failRestart = true

        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libA, libC)) })

        assertEquals(setOf(libA, libC), assertNotNull(tv.stored()).chosen)
        assertEquals(1, tv.firstSync.retryCalls)
        assertTrue(SelectionEvent.SetupFollowUpFailed in tv.events)
    }

    // Binding item 4 (Task 4.5): the setup's save committed, but the read for its cache write gave nothing. The cache
    // is left behind, so the next operation puts it in line first.
    @Test
    fun aSetupWhoseReadAfterTheSaveGivesNothing_leavesTheCacheBehind() {
        val tv = Tv().ready(setOf(libA, libB, libC))
        assertTrue(runBlocking { tv.selection.clear() })
        // The read right after the setup's save is the one that gives nothing.
        tv.settings.beforeSave = { tv.settings.unreadableLoad = tv.settings.loads.get() + 1 }

        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libA, libC)) })

        tv.settings.beforeSave = {}
        assertNull(tv.row(libC), "the setup's cache write was skipped")
        assertTrue(SelectionEvent.RoomBehind in tv.events)
        // The next operation (a listing that counts) catches the cache up before anything else.
        tv.listing(setOf(libA, libC))
        assertNotNull(tv.row(libC), "the catch-up wrote the setup's choice to the cache")
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

    // Fix round 3, item 1: a sign-in or a switch of user saves the new session (which activates the new account) and
    // then clears; when that clear fails, the old account's choice is still stored. Nothing of it may reach the new
    // account's cache, by any locked operation or the picker's listing; Done then stores the new choice.
    private fun switchedWithAFailedClear(): Tv {
        val tv = Tv().ready(setOf(libA, libB, libC))
        assertEquals(ACCOUNT, assertNotNull(tv.stored()).owner, "the resume wrote whose it is")
        // A cache write fails, so the selection is behind and its next operation would catch up.
        tv.library.failApplies = 1
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB, libC)))
        runBlocking { tv.fake.useAccount(other) }
        tv.settings.inner.failSave = true
        assertFalse(runBlocking { tv.selection.clear() })
        tv.settings.inner.failSave = false
        return tv
    }

    @Test
    fun aFailedClearAfterASwitch_neverReachesTheNewAccountsCache_andDoneThenStores() {
        val tv = switchedWithAFailedClear()
        val calls = tv.library.applyCalls
        val listings = assertNotNull(tv.stored()).listings

        assertEquals(ChangeResult.Failed, tv.change(setOf(libA)))
        runBlocking { tv.selection.reconcile() }
        runBlocking { tv.selection.followServer(other, listed(setOf(libA, libB))) }
        assertEquals(emptySet<ItemId>(), runBlocking { tv.selection.firstSyncLibraries(other) })

        assertEquals(calls, tv.library.applyCalls, "nothing written to the new account's cache")
        assertTrue(runBlocking { tv.fake.storedLibraries(other) }.isEmpty())
        assertEquals(listings, assertNotNull(tv.stored()).listings, "the old choice's bookkeeping was not followed")
        assertTrue(SelectionEvent.ForeignChoice in tv.events)

        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libD)) })
        val stored = assertNotNull(tv.stored())
        assertEquals(setOf(libD), stored.chosen)
        assertEquals(other, stored.owner)
    }

    // Part 4A final fix F2: the restart makes the clear the sign-in meant, so the launch routes to the picker.
    @Test
    fun aFailedClearAfterASwitch_isClearedAtTheRestart_withoutReachingTheNewAccountsCache() {
        val tv = switchedWithAFailedClear()
        val calls = tv.library.applyCalls

        runBlocking { tv.restartedSelection().resumeAtStart() }

        assertEquals(calls, tv.library.applyCalls)
        assertTrue(runBlocking { tv.fake.storedLibraries(other) }.isEmpty())
        assertNull(tv.stored(), "cleared, as the sign-in meant")
    }

    // An older build's record names no owner. Those builds cleared the choice at every sign-in and switch of user,
    // so it is the signed-in account's: the resume adopts it, and writes that down.
    @Test
    fun anOlderRecordWithNoOwner_isAdoptedAtResume_byTheActiveAccount() {
        val tv = Tv()
        assertNull(assertNotNull(tv.stored()).owner)

        tv.resume()

        val stored = assertNotNull(tv.stored())
        assertEquals(ACCOUNT, stored.owner)
        assertEquals(1L, stored.seq, "a bookkeeping write: the seq is as it was")
    }

    // Even over an empty library cache (a rebuilt one): the upgraded TV's first sync still reads its choice.
    @Test
    fun anOlderRecord_overAnEmptyCache_isAdopted_andTheFirstSyncReadsItsChoice() {
        val tv = Tv(chosen = emptySet(), stored = StoredSelection(setOf(libA, libB), seq = 1L))
        assertTrue(tv.rows().isEmpty())

        tv.resume()

        assertEquals(ACCOUNT, assertNotNull(tv.stored()).owner)
        assertEquals(setOf(libA, libB), runBlocking { tv.selection.firstSyncLibraries(ACCOUNT) })
    }

    @Test
    fun anOlderRecord_withNoActiveAccount_isLeftAlone() {
        val tv = Tv()
        runBlocking { tv.fake.forget(ACCOUNT) }

        tv.resume()

        assertNull(assertNotNull(tv.stored()).owner)
    }

    // Fix round 4, item 1: an older build's record left while no one was signed in is nobody's yet. The next account
    // to sign in is not its owner: when that sign-in's clear fails, nothing of it reaches the new account's cache, and
    // nothing names the new account its owner.
    @Test
    fun anOlderRecordLeftSignedOut_neverReachesTheNextAccountsCache_whenItsClearFails() {
        val tv = Tv()
        runBlocking { tv.fake.forget(ACCOUNT) }
        tv.resume()
        assertNull(assertNotNull(tv.stored()).owner, "left alone at the resume")
        runBlocking { tv.fake.useAccount(other) }
        tv.settings.inner.failSave = true
        assertFalse(runBlocking { tv.selection.clear() })
        tv.settings.inner.failSave = false
        val calls = tv.library.applyCalls
        val before = assertNotNull(tv.stored())

        assertEquals(ChangeResult.Failed, tv.change(setOf(libA)))
        runBlocking { tv.selection.reconcile() }
        runBlocking { tv.selection.followServer(other, listed(setOf(libA, libB))) }
        assertEquals(emptySet<ItemId>(), runBlocking { tv.selection.firstSyncLibraries(other) })

        assertEquals(calls, tv.library.applyCalls, "nothing written to the new account's cache")
        assertTrue(runBlocking { tv.fake.storedLibraries(other) }.isEmpty())
        assertEquals(before, tv.stored(), "not followed, and still no owner")
        assertNull(assertNotNull(tv.stored()).owner)
        assertTrue(SelectionEvent.ForeignChoice in tv.events)
    }

    // Fix round 4, item 1: the account that adopted an older record at resume keeps its choice for the life of the
    // process, even when the write that names it the owner failed.
    @Test
    fun anAdoptionWhoseWriteFailed_stillLetsTheAdoptingAccountUseItsChoice() {
        val tv = Tv()
        tv.settings.failSaves = 1
        tv.resume()
        assertNull(assertNotNull(tv.stored()).owner, "the adoption's write failed")

        assertEquals(setOf(libA, libB), runBlocking { tv.selection.firstSyncLibraries(ACCOUNT) })
        tv.listing(setOf(libA, libB, libC))
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB, libC)))

        val stored = assertNotNull(tv.stored())
        assertEquals(mapOf(libC to stored.seq), stored.adding)
        assertEquals(ACCOUNT, stored.owner, "the next save names it")
        assertFalse(SelectionEvent.ForeignChoice in tv.events)
    }
}
