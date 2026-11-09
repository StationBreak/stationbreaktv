// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

    @Test
    fun lastLibrary_noticeAndStillTicked() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO))
            choices.result = ChangeResult.LastLibrary
            val vm = open(choices)

            vm.select()
            runCurrent()

            assertEquals(listOf(emptySet<ItemId>()), choices.changes)
            assertEquals(Notice.LastLibrary, vm.now.notice)
            assertTrue(vm.now.row(ALPHA.id).ticked)
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

    @Test
    fun toggle_appliesOnce_perPress() =
        runTest {
            val choices = FakeLibraryChoices(view(setOf(ALPHA.id)), listOf(ALPHA, BRAVO))
            val gate = CompletableDeferred<Unit>()
            choices.gate = gate
            val vm = open(choices)
            vm.focusOn(BRAVO.id)

            vm.select()
            runCurrent()
            vm.select()
            runCurrent()
            assertEquals("a press while the change is out sends nothing", 1, choices.changes.size)

            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(setOf(ALPHA.id, BRAVO.id)), choices.changes)
            assertEquals(Notice.Saved, vm.now.notice)
            assertTrue(vm.now.row(BRAVO.id).ticked)

            vm.select()
            runCurrent()
            assertEquals("the next press sends again", listOf(setOf(ALPHA.id)), choices.changes.drop(1))
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

    // Fix round 1: a change still out from an earlier visit holds the next visit's Select, so it is never dropped.
    @Test
    fun aChangeFromAnEarlierVisit_holdsSelect_untilItEnds() =
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
            vm.focusOn(CHARLIE.id)
            vm.select()
            runCurrent()
            assertEquals("ignored while the earlier change is out", 1, choices.changes.size)

            choices.gate = null
            gate.complete(Unit)
            runCurrent()
            vm.select()
            runCurrent()

            assertEquals(listOf(setOf(ALPHA.id, BRAVO.id), setOf(ALPHA.id, BRAVO.id, CHARLIE.id)), choices.changes)
            assertEquals(listOf(true, true, true), vm.now.rows.map { it.ticked })
        }
}
