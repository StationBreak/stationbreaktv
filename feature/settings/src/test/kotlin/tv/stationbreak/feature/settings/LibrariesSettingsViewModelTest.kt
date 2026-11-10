// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.selection.ChangeResult
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.domain.selection.FakeLibraryChoices
import tv.stationbreak.core.domain.selection.LibraryListing
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

private val ALPHA = Library(ItemId("lib-alpha"), "Alpha", LibraryKind.Movies)
private val BRAVO = Library(ItemId("lib-bravo"), "Bravo", LibraryKind.TvShows)
private val CHARLIE = Library(ItemId("lib-charlie"), "Charlie", LibraryKind.Mixed)
private val DELTA = ItemId("lib-delta")

private fun view(
    chosen: Set<ItemId>,
    adding: Set<ItemId> = emptySet(),
    stalled: Set<ItemId> = emptySet(),
) = ChoiceView(chosen, adding, gone = false, stalled = stalled)

class LibrariesSettingsViewModelTest {
    private var vm: LibrariesSettingsViewModel? = null

    @After
    fun tearDown() {
        vm?.close()
    }

    private fun TestScope.open(choices: FakeLibraryChoices): LibrariesSettingsViewModel {
        val made = LibrariesSettingsViewModel(choices, StandardTestDispatcher(testScheduler))
        vm = made
        made.open()
        runCurrent()
        return made
    }

    private val LibrariesSettingsViewModel.now get() = state.value

    private fun LibrariesSettingsState.row(id: ItemId) = rows.single { it.id == id }

    private fun LibrariesSettingsViewModel.focusOn(id: ItemId) {
        repeat(now.rows.size) { move(up = true) }
        repeat(now.rows.indexOfFirst { it.id == id }) { move(up = false) }
    }

    @Test
    fun ticksShowStoredSelection() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id, CHARLIE.id)), listOf(ALPHA, BRAVO, CHARLIE))
            val vm = open(choices)

            assertEquals(LibrariesPhase.Listed, vm.now.phase)
            assertEquals(listOf(ALPHA.id, BRAVO.id, CHARLIE.id), vm.now.rows.map { it.id })
            assertEquals(listOf("Alpha", "Bravo", "Charlie"), vm.now.rows.map { it.name })
            assertEquals(listOf(true, false, true), vm.now.rows.map { it.ticked })
            assertTrue(vm.now.rows.none { it.adding })
            assertEquals(0, vm.now.focus)
            assertNull(vm.now.notice)
        }

    // Design-partner placeholder: a chosen library the listing left out, not yet confirmed missing, shows ticked.
    @Test
    fun chosenButLeftOutOfTheListing_showsTicked() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id, DELTA)), listOf(ALPHA, BRAVO))
            val vm = open(choices)

            assertEquals(listOf(ALPHA.id, BRAVO.id, DELTA), vm.now.rows.map { it.id })
            assertTrue(vm.now.row(DELTA).ticked)
            assertNull("no name: the listing gave none", vm.now.row(DELTA).name)
        }

    // The rule is kept on the press: the last chosen library cannot be unticked, and nothing is sent.
    @Test
    fun lastLibrary_noticeAndStillTicked() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO))
            val vm = open(choices)

            vm.select()
            runCurrent()

            assertTrue("refused on the press", choices.changes.isEmpty())
            assertEquals(Notice.LastLibrary, vm.now.notice)
            assertTrue(vm.now.row(ALPHA.id).ticked)
            assertTrue(!vm.now.row(ALPHA.id).pending)
        }

    // An add does not count: with B only wanted, unticking the one chosen library is refused too.
    @Test
    fun lastLibrary_isKeptOnTheWantedTicks_whileAnAddIsSaving() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO))
            choices.gate = CompletableDeferred()
            val vm = open(choices)
            vm.focusOn(BRAVO.id)
            vm.select()
            runCurrent()

            vm.focusOn(ALPHA.id)
            vm.select()
            runCurrent()

            assertEquals(Notice.LastLibrary, vm.now.notice)
            assertTrue(vm.now.row(ALPHA.id).ticked)
            assertTrue(vm.now.row(BRAVO.id).ticked)
            assertEquals(listOf(setOf(ALPHA.id, BRAVO.id)), choices.changes)
        }

    @Test
    fun listingFails_readOnly() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id), adding = setOf(BRAVO.id)), listOf(ALPHA, BRAVO))
            val vm = open(choices)
            // A listing this visit read gave the names; the next visit cannot reach the server.
            vm.close()
            choices.answer = { LibraryListing.Unavailable(FakeLibraryChoices.timeout, choices.selection.value) }
            vm.open()
            runCurrent()

            assertEquals(LibrariesPhase.Unavailable(FakeLibraryChoices.timeout), vm.now.phase)
            assertEquals(listOf(ALPHA.id, BRAVO.id), vm.now.rows.map { it.id })
            assertEquals(listOf("Alpha", "Bravo"), vm.now.rows.map { it.name })
            assertTrue(vm.now.rows.all { it.ticked })
            vm.select()
            runCurrent()
            assertTrue("read-only: nothing is changed", choices.changes.isEmpty())
        }

    @Test
    fun notSignedIn_readOnlyWithNoRows() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)))
            choices.answer = { LibraryListing.NotSignedIn(choices.selection.value) }
            val vm = open(choices)

            assertEquals(LibrariesPhase.Unavailable(null), vm.now.phase)
            assertTrue(vm.now.rows.isEmpty())
            vm.select()
            vm.move(up = false)
            runCurrent()
            assertTrue(choices.changes.isEmpty())
        }

    // The owner's report: a press flips its row at once, and shows it is saving until the change is stored.
    @Test
    fun select_flipsTheRowAtOnce_showsSaving_thenClears() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id, BRAVO.id)), listOf(ALPHA, BRAVO))
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate
            val vm = open(choices)
            vm.focusOn(BRAVO.id)

            vm.select()

            val pressed = vm.now.row(BRAVO.id)
            assertTrue("flipped on the press, before the change ran", !pressed.ticked)
            assertTrue(pressed.pending)
            assertEquals("an untick says it is removing", RowStatus.Removing, pressed.status)
            assertTrue(!vm.now.row(ALPHA.id).pending)
            runCurrent()
            assertEquals(listOf(setOf(ALPHA.id)), choices.changes)
            assertTrue("still saving while the change is out", vm.now.row(BRAVO.id).pending)
            assertNull(vm.now.notice)

            gate.complete(Unit)
            runCurrent()

            assertTrue(!vm.now.row(BRAVO.id).ticked)
            assertTrue(!vm.now.row(BRAVO.id).pending)
            assertEquals(Notice.Saved, vm.now.notice)
        }

    // A tick saving shows Saving, then Adding once it is stored and being read in.
    @Test
    fun aTick_showsSaving_thenAdding() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO))
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate
            val vm = open(choices)
            vm.focusOn(BRAVO.id)

            vm.select()
            runCurrent()
            val saving = vm.now.row(BRAVO.id)
            assertTrue(saving.ticked && saving.pending && !saving.adding)
            assertEquals(RowStatus.Saving, saving.status)

            gate.complete(Unit)
            runCurrent()
            val adding = vm.now.row(BRAVO.id)
            assertTrue(adding.ticked && !adding.pending && adding.adding)
            assertEquals(RowStatus.Adding, adding.status)
        }

    // The owner's report: a press on another row while a change is out is not dropped; it is sent after it.
    @Test
    fun aPressOnAnotherRow_duringASlowChange_isSentAfterIt() =
        runTest {
            val choices =
                FakeLibraryChoices(view(setOf(ALPHA.id, BRAVO.id)), listOf(ALPHA, BRAVO, CHARLIE))
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate
            val vm = open(choices)
            vm.focusOn(BRAVO.id)
            vm.select()
            runCurrent()

            vm.focusOn(CHARLIE.id)
            vm.select()
            runCurrent()

            assertTrue("flipped at once", vm.now.row(CHARLIE.id).ticked)
            assertTrue(vm.now.row(CHARLIE.id).pending)
            assertEquals("one change at a time", 1, choices.changes.size)

            choices.gate = null
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(setOf(ALPHA.id), setOf(ALPHA.id, CHARLIE.id)), choices.changes)
            assertEquals(listOf(true, false, true), vm.now.rows.map { it.ticked })
            assertTrue(vm.now.rows.none { it.pending })
            assertEquals(Notice.Saved, vm.now.notice)
        }

    // Presses while a change is out are coalesced into one next change, and the last state of each row wins.
    @Test
    fun threeQuickToggles_coalesceIntoTwoChanges_andTheLastStateWins() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO, CHARLIE))
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate
            val vm = open(choices)

            vm.focusOn(BRAVO.id)
            vm.select()
            runCurrent()
            vm.focusOn(CHARLIE.id)
            vm.select()
            vm.focusOn(BRAVO.id)
            vm.select()
            runCurrent()
            assertEquals(listOf(true, false, true), vm.now.rows.map { it.ticked })

            choices.gate = null
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(setOf(ALPHA.id, BRAVO.id), setOf(ALPHA.id, CHARLIE.id)), choices.changes)
            assertEquals(listOf(true, false, true), vm.now.rows.map { it.ticked })
            assertTrue(vm.now.rows.none { it.pending })
        }

    // A row pressed back to what is stored while a change is out sends nothing more.
    @Test
    fun aRowPressedBackToStored_sendsNothingMore() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO, CHARLIE))
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate
            val vm = open(choices)
            vm.focusOn(BRAVO.id)
            vm.select()
            runCurrent()
            vm.focusOn(CHARLIE.id)
            vm.select()
            vm.select()
            assertTrue(!vm.now.row(CHARLIE.id).ticked && !vm.now.row(CHARLIE.id).pending)

            choices.gate = null
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(setOf(ALPHA.id, BRAVO.id)), choices.changes)
        }

    // A change that fails puts its rows back as stored and says Failed; a row pressed after it is still sent.
    @Test
    fun aFailedChange_revertsItsRows_andShowsFailed() =
        runTest {
            val choices =
                FakeLibraryChoices(view(setOf(ALPHA.id, BRAVO.id)), listOf(ALPHA, BRAVO, CHARLIE))
            choices.result = ChangeResult.Failed
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate
            val vm = open(choices)
            vm.focusOn(BRAVO.id)
            vm.select()
            runCurrent()
            vm.focusOn(CHARLIE.id)
            vm.select()
            runCurrent()

            choices.gate = null
            gate.complete(Unit)
            runCurrent()

            assertEquals(
                "B's wish is dropped with its failure; C's is sent on its own",
                listOf(setOf(ALPHA.id), setOf(ALPHA.id, BRAVO.id, CHARLIE.id)),
                choices.changes,
            )
            assertEquals(listOf(true, true, false), vm.now.rows.map { it.ticked })
            assertTrue(vm.now.rows.none { it.pending })
            assertEquals(Notice.Failed, vm.now.notice)
        }

    @Test
    fun addingRowShowsAdding() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO))
            val vm = open(choices)
            vm.focusOn(BRAVO.id)

            vm.select()
            runCurrent()

            val bravo = vm.now.row(BRAVO.id)
            assertTrue(bravo.ticked)
            assertTrue(bravo.adding)
            assertTrue(!vm.now.row(ALPHA.id).adding)
        }

    // Issue #654, binding item 1: an add given up for this run of the app is not shown as adding for ever.
    @Test
    fun stalledAdd_showsNotAdding_withTheFailedNotice() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id), adding = setOf(BRAVO.id)), listOf(ALPHA, BRAVO))
            val vm = open(choices)
            assertTrue(vm.now.row(BRAVO.id).adding)
            assertNull(vm.now.notice)

            // The add pass gives it up while the screen shows.
            choices.selection.value = view(setOf(ALPHA.id), adding = setOf(BRAVO.id), stalled = setOf(BRAVO.id))
            runCurrent()

            val bravo = vm.now.row(BRAVO.id)
            assertTrue("still stored as ticked", bravo.ticked)
            assertTrue("not adding any more", !bravo.adding)
            assertEquals(Notice.Failed, vm.now.notice)
        }

    @Test
    fun stalledAdd_atOpen_showsTheFailedNotice() =
        runTest {
            val stalled = view(setOf(ALPHA.id), adding = setOf(BRAVO.id), stalled = setOf(BRAVO.id))
            val vm = open(FakeLibraryChoices(stalled, listOf(ALPHA, BRAVO)))

            assertTrue(!vm.now.row(BRAVO.id).adding)
            assertEquals(Notice.Failed, vm.now.notice)
        }

    // Issue #654, binding item 2: a change that failed says so and leaves the ticks as stored.
    @Test
    fun failedChange_showsFailed_andTheTicksAsStored() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id, BRAVO.id)), listOf(ALPHA, BRAVO, CHARLIE))
            choices.result = ChangeResult.Failed
            val vm = open(choices)
            vm.focusOn(BRAVO.id)

            vm.select()
            runCurrent()

            assertEquals(listOf(setOf(ALPHA.id)), choices.changes)
            assertEquals(Notice.Failed, vm.now.notice)
            assertEquals(listOf(true, true, false), vm.now.rows.map { it.ticked })
        }

    @Test
    fun notListed_notice() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO))
            choices.result = ChangeResult.NotListed
            val vm = open(choices)
            vm.focusOn(BRAVO.id)

            vm.select()
            runCurrent()

            assertEquals(Notice.NotListed, vm.now.notice)
            assertTrue(!vm.now.row(BRAVO.id).ticked)
        }

    @Test
    fun focusMovesBetweenRows_andStopsAtTheEnds() =
        runTest {
            val vm = open(FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO, CHARLIE)))

            vm.move(up = true)
            assertEquals(0, vm.now.focus)
            vm.move(up = false)
            vm.move(up = false)
            vm.move(up = false)
            assertEquals(2, vm.now.focus)
            vm.move(up = true)
            assertEquals(1, vm.now.focus)
        }

    @Test
    fun aNewVisit_startsClean_andListsAgain() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO))
            choices.result = ChangeResult.LastLibrary
            val vm = open(choices)
            vm.move(up = false)
            vm.focusOn(ALPHA.id)
            vm.select()
            runCurrent()
            assertEquals(Notice.LastLibrary, vm.now.notice)

            vm.close()
            vm.open()
            runCurrent()

            assertEquals(2, choices.listings)
            assertNull(vm.now.notice)
            assertEquals(0, vm.now.focus)
        }

    // Fix round 1: the focus follows the rows. The last row going (a left-out library unticked) leaves it on the new
    // last row, so the next Select acts there.
    @Test
    fun theLastRowGoing_movesTheFocusToTheNewLastRow() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id, DELTA)), listOf(ALPHA, BRAVO))
            val vm = open(choices)
            vm.focusOn(DELTA)
            assertEquals(2, vm.now.focus)

            vm.select()
            runCurrent()
            assertEquals(listOf(ALPHA.id, BRAVO.id), vm.now.rows.map { it.id })
            assertEquals(1, vm.now.focus)

            vm.select()
            runCurrent()
            assertEquals(listOf(setOf(ALPHA.id), setOf(ALPHA.id, BRAVO.id)), choices.changes)
        }

    // A change still out when the screen closes finishes; the next visit shows its row as saving, and takes presses.
    @Test
    fun aChangeFromAnEarlierVisit_showsSaving_andANewPressIsSentAfterIt() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO, CHARLIE))
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate
            val vm = open(choices)
            vm.focusOn(BRAVO.id)
            vm.select()
            runCurrent()

            vm.close()
            vm.open()
            runCurrent()
            assertTrue("the earlier visit's tick, not the stale stored one", vm.now.row(BRAVO.id).ticked)
            assertTrue(vm.now.row(BRAVO.id).pending)
            vm.focusOn(CHARLIE.id)
            vm.select()
            runCurrent()
            assertTrue(vm.now.row(CHARLIE.id).ticked)
            assertEquals("one change at a time", 1, choices.changes.size)

            choices.gate = null
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(setOf(ALPHA.id, BRAVO.id), setOf(ALPHA.id, BRAVO.id, CHARLIE.id)), choices.changes)
            assertEquals(listOf(true, true, true), vm.now.rows.map { it.ticked })
            assertTrue(vm.now.rows.none { it.pending })
        }

    // Fix round 1: reopened while a change is out and the server is slow to list (both on one gate), the screen shows
    // the rows of the last listing at once, the earlier visit's press as removing, and takes a new press, queued.
    @Test
    fun reopenedWhileTheListingWaits_showsTheRowsAtOnce_andQueuesANewPress() =
        runTest {
            val choices =
                FakeLibraryChoices(view(setOf(ALPHA.id, BRAVO.id)), listOf(ALPHA, BRAVO, CHARLIE))
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate
            val vm = open(choices)
            vm.focusOn(BRAVO.id)
            vm.select()
            runCurrent()

            vm.close()
            choices.listingGate = gate
            vm.open()
            runCurrent()

            assertEquals(LibrariesPhase.Listed, vm.now.phase)
            assertEquals(listOf(ALPHA.id, BRAVO.id, CHARLIE.id), vm.now.rows.map { it.id })
            assertEquals(RowStatus.Removing, vm.now.row(BRAVO.id).status)
            vm.focusOn(CHARLIE.id)
            vm.select()
            runCurrent()
            assertEquals(RowStatus.Saving, vm.now.row(CHARLIE.id).status)
            assertEquals("one change at a time", 1, choices.changes.size)

            choices.gate = null
            choices.listingGate = null
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(setOf(ALPHA.id), setOf(ALPHA.id, CHARLIE.id)), choices.changes)
            assertEquals(listOf(true, false, true), vm.now.rows.map { it.ticked })
            assertTrue(vm.now.rows.none { it.pending })
            assertEquals(2, choices.listings)
        }

    // The owner's report: reopened with a change still saving, the focus went back to the top, and the pending row
    // lower down could be off screen.
    @Test
    fun reopen_focusesTheRowThatWasFocusedWhenItClosed() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO, CHARLIE))
            choices.gate = CompletableDeferred()
            val vm = open(choices)
            vm.focusOn(CHARLIE.id)
            vm.select()
            runCurrent()
            assertTrue(vm.now.row(CHARLIE.id).pending)

            vm.close()
            vm.open()
            runCurrent()

            assertTrue("still saving", vm.now.row(CHARLIE.id).pending)
            assertEquals(2, vm.now.focus)
        }

    @Test
    fun reopen_focusesTheFirstRow_whenTheFocusedRowIsGone() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO, CHARLIE))
            val vm = open(choices)
            vm.focusOn(CHARLIE.id)
            vm.close()

            choices.libraries = listOf(ALPHA, BRAVO)
            vm.open()
            runCurrent()

            assertEquals(listOf(ALPHA.id, BRAVO.id), vm.now.rows.map { it.id })
            assertEquals(0, vm.now.focus)
        }

    @Test
    fun reopen_afterNoFocusMove_focusesTheFirstRow() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO, CHARLIE))
            val vm = open(choices)
            vm.close()
            vm.open()
            runCurrent()

            assertEquals(0, vm.now.focus)
        }

    // The listing is slow on the reopen: its rows come from the last listing, and the viewer moves before it answers.
    // The listing, when it lands, does not pull the focus back to the row the last visit closed on.
    @Test
    fun reopen_aMoveBeforeTheListingAnswers_isKept() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO, CHARLIE))
            val vm = open(choices)
            vm.focusOn(CHARLIE.id)
            vm.close()

            val gate = CompletableDeferred<Unit>()
            choices.listingGate = gate
            vm.open()
            runCurrent()
            assertEquals("the last listing's rows, on the row closed on", 2, vm.now.focus)

            vm.move(up = true)
            assertEquals(1, vm.now.focus)
            gate.complete(Unit)
            runCurrent()

            assertEquals(3, vm.now.rows.size)
            assertEquals(1, vm.now.focus)
        }

    // The last listing has the row the visit closed on, the fresh one drops it: the focus falls to the first row, and
    // no restore is left to pull it anywhere later.
    @Test
    fun reopen_aFreshListingThatDropsTheRow_fallsToTheFirstRow_andLeavesNoRestoreBehind() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO, CHARLIE))
            val vm = open(choices)
            vm.focusOn(CHARLIE.id)
            vm.close()

            val gate = CompletableDeferred<Unit>()
            choices.listingGate = gate
            choices.libraries = listOf(ALPHA, BRAVO)
            vm.open()
            runCurrent()
            assertEquals(2, vm.now.focus)
            gate.complete(Unit)
            runCurrent()
            assertEquals(0, vm.now.focus)

            vm.move(up = false)
            // The stored choice changing re-places nothing: the viewer's row stays.
            choices.selection.value = view(setOf(ALPHA.id, BRAVO.id))
            runCurrent()
            assertEquals(1, vm.now.focus)
        }

    // A move while the rows are still loading (nothing to move over) keeps the restore of the row closed on.
    @Test
    fun reopen_aMoveWhileLoading_keepsTheRestoreUntilRowsExist() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id, CHARLIE.id)), listOf(ALPHA, BRAVO, CHARLIE))
            choices.answer = { LibraryListing.Unavailable(FakeLibraryChoices.timeout, choices.selection.value) }
            val vm = open(choices)
            vm.move(up = false)
            assertEquals(listOf(ALPHA.id, CHARLIE.id), vm.now.rows.map { it.id })
            vm.close()

            val gate = CompletableDeferred<Unit>()
            choices.listingGate = gate
            choices.answer = { LibraryListing.Listed(choices.libraries, choices.selection.value) }
            vm.open()
            runCurrent()
            assertEquals(LibrariesPhase.Loading, vm.now.phase)
            vm.move(up = false)
            gate.complete(Unit)
            runCurrent()

            assertEquals(CHARLIE.id, vm.now.rows[vm.now.focus].id)
        }

    // A change that failed while the screen was closed says so on the next open, once.
    @Test
    fun aFailureWhileClosed_isShownOnTheNextOpen_once() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO))
            val vm = open(choices)
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate
            choices.result = ChangeResult.Failed
            vm.move(up = false)
            vm.select()
            runCurrent()
            vm.close()
            gate.complete(Unit)
            runCurrent()

            vm.open()
            runCurrent()
            assertEquals(Notice.Failed, vm.now.notice)

            vm.close()
            vm.open()
            runCurrent()
            assertNull(vm.now.notice)
        }

    // Another account's stored choice: the server lists, but nothing is stored for this account. Read-only, no rows.
    @Test
    fun listedWithNoStoredChoice_isTheReadOnlyState_andSelectDoesNothing() =
        runTest {
            val choices = FakeLibraryChoices(null, listOf(ALPHA, BRAVO))
            val vm = open(choices)

            assertEquals(LibrariesPhase.Unavailable(null), vm.now.phase)
            assertTrue(vm.now.rows.isEmpty())
            vm.select()
            runCurrent()
            assertTrue(choices.changes.isEmpty())
        }

    // The gap runs from the change returning, off Main, to the ViewModel acting: a wait for Main shows in it.
    @Test
    fun aChange_gapIncludesTheWaitForMainAfterItReturned() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO))
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate
            val lines = mutableListOf<String>()
            val clock = TestTimeSource()
            val main = StandardTestDispatcher(testScheduler)
            val worker = UnconfinedTestDispatcher(testScheduler)
            val made = LibrariesSettingsViewModel(choices, main, clock, worker) { lines += it }
            vm = made
            made.open()
            runCurrent()
            made.move(up = false)
            made.select()
            runCurrent()
            assertTrue(lines.isEmpty())

            // The change returns off Main; Main is busy for 5 ms before it runs the continuation.
            gate.complete(Unit)
            clock += 5.milliseconds
            runCurrent()

            assertEquals(listOf("SelectionChangeActed gap=5"), lines)
        }
}
