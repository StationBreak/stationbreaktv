// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A signed-in server whose sign-out only counts, says whether the server was told, and can be held. */
private class CountingSignOut(
    private val name: String? = "Home",
    private val revoked: Boolean = true,
    private val fails: Boolean = false,
    private val cancels: Boolean = false,
) : AccountSignOut {
    var signOuts = 0
    var gate: CompletableDeferred<Unit>? = null

    override fun serverName(): String? = name

    override suspend fun signOut(): Boolean {
        signOuts += 1
        gate?.await()
        check(!fails) { "https://server.example.test refused" }
        if (cancels) throw CancellationException("the screen went")
        return revoked
    }
}

/** #336: the question the guide's Settings sheet asks before signing out, with the safe answer focused. */
class SignOutViewModelTest {
    private val logged = mutableListOf<String>()

    private fun TestScope.open(account: AccountSignOut): SignOutViewModel {
        val main = StandardTestDispatcher(testScheduler)
        val made = SignOutViewModel(account, main, log = { logged += it })
        made.open()
        return made
    }

    private val SignOutViewModel.now get() = state.value

    private fun TestScope.told(vm: SignOutViewModel): () -> Int {
        var count = 0
        backgroundScope.launch { vm.signedOut.collect { count += 1 } }
        runCurrent()
        return { count }
    }

    @Test
    fun itOpensOnStaySignedIn_withTheServersName() =
        runTest {
            val vm = open(CountingSignOut())
            assertEquals(SignOutState(SignOutStep.Asking, SignOutChoice.Stay, "Home"), vm.now)
        }

    @Test
    fun okStraightAway_andOkAgainAndAgain_staysSignedIn() =
        runTest {
            val account = CountingSignOut()
            val vm = open(account)
            val told = told(vm)
            repeat(5) { assertTrue("OK on Stay signed in closes the question", vm.select()) }
            runCurrent()
            assertEquals(0, account.signOuts)
            assertEquals(0, told())
            assertEquals(SignOutChoice.Stay, vm.now.focus)
        }

    @Test
    fun backStaysSignedIn_fromEitherAnswer() =
        runTest {
            val account = CountingSignOut()
            val vm = open(account)
            assertTrue(vm.back())
            vm.move(up = false)
            assertTrue(vm.back())
            runCurrent()
            assertEquals(0, account.signOuts)
        }

    @Test
    fun downThenOkSignsOutOnce_andTellsTheHost() =
        runTest {
            val account = CountingSignOut()
            val vm = open(account)
            val told = told(vm)
            vm.move(up = false)
            assertEquals(SignOutChoice.SignOut, vm.now.focus)

            assertFalse(vm.select())
            assertEquals(SignOutStep.Busy, vm.now.step)
            // A second press in the same moment, and a move or Back while it runs, change nothing.
            assertFalse(vm.select())
            vm.move(up = true)
            assertFalse(vm.back())
            runCurrent()
            assertEquals(1, account.signOuts)
            assertEquals(1, told())
        }

    @Test
    fun whileItRunsTheQuestionSaysSo_andKeysWait() =
        runTest {
            val account = CountingSignOut()
            account.gate = CompletableDeferred()
            val vm = open(account)
            val told = told(vm)
            vm.move(up = false)
            vm.select()
            runCurrent()
            assertEquals(SignOutStep.Busy, vm.now.step)
            assertEquals(SignOutChoice.SignOut, vm.now.focus)
            assertEquals(0, told())

            account.gate?.complete(Unit)
            runCurrent()
            assertEquals(1, told())
        }

    @Test
    fun upReturnsToStay_andTheNextOpeningStartsThereToo() =
        runTest {
            val vm = open(CountingSignOut())
            vm.move(up = false)
            vm.move(up = true)
            assertEquals(SignOutChoice.Stay, vm.now.focus)
            vm.move(up = false)
            vm.open()
            assertEquals(SignOutChoice.Stay, vm.now.focus)
        }

    @Test
    fun aSignOutTheServerWasNotToldOfSaysSo_andOkOrBackThenGoesOnOnce() =
        runTest {
            val account = CountingSignOut(revoked = false)
            val vm = open(account)
            val told = told(vm)
            vm.move(up = false)
            vm.select()
            runCurrent()
            assertEquals(SignOutStep.NotRevoked, vm.now.step)
            assertEquals(0, told())

            assertFalse(vm.select())
            runCurrent()
            assertEquals(1, told())
            // A second press, OK or Back, leaves no second time.
            assertFalse(vm.back())
            assertFalse(vm.select())
            runCurrent()
            assertEquals(1, told())
            assertEquals(1, account.signOuts)
        }

    @Test
    fun aSignOutThatThrowsAsksAgainOnStay_saysItWentWrong_andLogsAFixedWord() =
        runTest {
            val vm = open(CountingSignOut(fails = true))
            val told = told(vm)
            vm.move(up = false)
            vm.select()
            runCurrent()
            // Never "signed out on this TV": what was cleared is not known.
            assertEquals(SignOutState(SignOutStep.Asking, SignOutChoice.Stay, "Home", failed = true), vm.now)
            assertEquals(listOf("SignOutChosen", "SignOutFailed"), logged)
            assertTrue("the error is never logged", logged.none { "example" in it })
            assertEquals(0, told())
            // The next key clears the line; OK on Stay closes the question.
            vm.move(up = true)
            assertFalse(vm.now.failed)
            assertTrue(vm.select())
        }

    @Test
    fun aCancelledSignOutIsNotAFailure() =
        runTest {
            val vm = open(CountingSignOut(cancels = true))
            val told = told(vm)
            vm.move(up = false)
            vm.select()
            runCurrent()
            assertEquals(listOf("SignOutChosen"), logged)
            assertFalse(vm.now.failed)
            assertEquals(SignOutStep.Busy, vm.now.step)
            assertEquals(0, told())
        }

    @Test
    fun closingTheQuestionPutsTheFocusBackOnStay() =
        runTest {
            val vm = open(CountingSignOut())
            vm.move(up = false)
            vm.close()
            assertEquals(SignOutChoice.Stay, vm.now.focus)
        }

    @Test
    fun aServerWithNoNameShowsNone() =
        runTest {
            assertNull(open(CountingSignOut(name = null)).now.serverName)
        }
}
