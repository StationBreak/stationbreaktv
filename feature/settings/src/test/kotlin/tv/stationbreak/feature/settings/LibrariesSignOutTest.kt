// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.domain.selection.FakeLibraryChoices
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind

private val ALPHA = Library(ItemId("lib-alpha"), "Alpha", LibraryKind.Movies)
private val BRAVO = Library(ItemId("lib-bravo"), "Bravo", LibraryKind.TvShows)

private fun stored() = ChoiceView(setOf(ALPHA.id), emptySet(), gone = false)

/** A signed-in server whose sign-out only counts, and says whether the server was told. */
private class CountingSignOut(
    private val name: String? = "Home",
    private val revoked: Boolean = true,
) : AccountSignOut {
    var signOuts = 0

    override fun serverName(): String? = name

    override suspend fun signOut(): Boolean {
        signOuts += 1
        return revoked
    }
}

/** #336: the sign-out row under the libraries, armed by a first Select and run by a second. */
class LibrariesSignOutTest {
    private var vm: LibrariesSettingsViewModel? = null

    @After
    fun tearDown() {
        vm?.close()
    }

    private fun TestScope.open(
        account: AccountSignOut,
        choices: FakeLibraryChoices = FakeLibraryChoices(stored(), listOf(ALPHA, BRAVO)),
    ): LibrariesSettingsViewModel {
        val main = StandardTestDispatcher(testScheduler)
        val made = LibrariesSettingsViewModel(choices, main, account = account)
        vm = made
        made.open()
        runCurrent()
        return made
    }

    private val LibrariesSettingsViewModel.now get() = state.value

    // Down past the libraries, onto the sign-out row.
    private fun LibrariesSettingsViewModel.toSignOut() = repeat(now.rows.size + 2) { move(up = false) }

    @Test
    fun theServerIsNamedAndTheSignOutRowIsLastAndNotFocusedOnEntry() =
        runTest {
            val vm = open(CountingSignOut())

            assertEquals("Home", vm.now.serverName)
            assertEquals(SignOutStep.Ready, vm.now.signOut)
            assertEquals(0, vm.now.focus)
            assertFalse(vm.now.signOutFocused)
            vm.toSignOut()
            assertEquals(vm.now.rows.size, vm.now.focus)
            assertTrue(vm.now.signOutFocused)
        }

    @Test
    fun aFirstSelectArmsAndASecondSignsOutOnce() =
        runTest {
            val account = CountingSignOut()
            val vm = open(account)
            var told = 0
            backgroundScope.launch { vm.signedOut.collect { told += 1 } }
            vm.toSignOut()

            vm.select()
            runCurrent()
            assertEquals(SignOutStep.Armed, vm.now.signOut)
            assertEquals(0, account.signOuts)

            vm.select()
            runCurrent()
            assertEquals(1, account.signOuts)
            assertEquals(1, told)

            // A third press while it ran, or after, signs out no more.
            vm.select()
            runCurrent()
            assertEquals(1, account.signOuts)
        }

    @Test
    fun aMoveDisarms() =
        runTest {
            val account = CountingSignOut()
            val vm = open(account)
            vm.toSignOut()
            vm.select()
            vm.move(up = true)
            vm.move(up = false)

            assertEquals(SignOutStep.Ready, vm.now.signOut)
            vm.select()
            runCurrent()
            assertEquals(0, account.signOuts)
            assertEquals(SignOutStep.Armed, vm.now.signOut)
        }

    @Test
    fun backDisarmsAndKeepsTheScreenThenFinishesIt() =
        runTest {
            val vm = open(CountingSignOut())
            vm.toSignOut()
            vm.select()

            assertTrue(vm.disarm())
            assertEquals(SignOutStep.Ready, vm.now.signOut)
            assertFalse(vm.disarm())
        }

    @Test
    fun nobodySignedInShowsNoSignOutAndTheFocusStopsAtTheLastLibrary() =
        runTest {
            val vm = open(AccountSignOut.None)
            vm.toSignOut()

            assertNull(vm.now.signOut)
            assertNull(vm.now.serverName)
            assertEquals(vm.now.rows.size - 1, vm.now.focus)
        }

    // While the server is asked for its libraries the row is not there, so a double Select on opening cannot reach it.
    @Test
    fun whileTheLibrariesLoadThereIsNoSignOutRowAndSelectSignsNothingOut() =
        runTest {
            val account = CountingSignOut()
            val choices = FakeLibraryChoices(null, listOf(ALPHA, BRAVO))
            choices.listingGate = kotlinx.coroutines.CompletableDeferred()
            val vm = open(account, choices)

            assertNull(vm.now.signOut)
            repeat(3) { vm.select() }
            runCurrent()
            assertEquals(0, account.signOuts)
        }

    // A row the focus lands on without a move (the only one left) is not armed by Select: the viewer must move onto it.
    @Test
    fun onlyAMoveOntoTheRowLetsSelectArmIt() =
        runTest {
            val account = CountingSignOut()
            val vm = open(account)
            vm.toSignOut()
            vm.select()
            assertEquals(SignOutStep.Armed, vm.now.signOut)
            vm.move(up = true)
            vm.select()
            vm.select()
            runCurrent()
            assertEquals(0, account.signOuts)
        }

    @Test
    fun aSignOutTheServerWasNotToldOfSaysSoAndSelectThenGoesOn() =
        runTest {
            val account = CountingSignOut(revoked = false)
            val vm = open(account)
            var told = 0
            backgroundScope.launch { vm.signedOut.collect { told += 1 } }
            vm.toSignOut()
            vm.select()
            vm.select()
            runCurrent()

            assertEquals(1, account.signOuts)
            assertEquals(SignOutStep.NotRevoked, vm.now.signOut)
            assertEquals(0, told)
            vm.select()
            runCurrent()
            assertEquals(1, told)
            assertEquals(1, account.signOuts)
        }

    @Test
    fun aBlankServerNameShowsNoName() =
        runTest {
            val vm = open(CountingSignOut(name = null))
            assertNull(vm.now.serverName)
        }
}
