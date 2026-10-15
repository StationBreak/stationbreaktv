// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import tv.stationbreak.feature.onboarding.server.ServerHandOff
import tv.stationbreak.feature.onboarding.signin.SignInResult
import tv.stationbreak.feature.onboarding.signin.SignedInHandOff

@OptIn(ExperimentalCoroutinesApi::class)
class SignedInViewModelTest {
    private val server = FakeMediaServer()
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, server)
    private val signedIn = SignedInHandOff()
    private val handOff = ServerHandOff()
    private val settings = InMemoryServerSettings(ServerRecord(ServerAddress(Scheme.Https, "jellyfin.example.test", 443)), setOf(ItemId("movies")))

    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)

    @Before
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel() = SignedInViewModel(sessions, signedIn, handOff, settings)

    @Test
    fun showsTheNameTheUserTypedAndTheServer() {
        signedIn.put(SignInResult(session, persisted = true, username = "sam"))

        val state = viewModel().state.value

        assertEquals("sam", state.name)
        assertEquals("Home Server", state.serverName)
        assertFalse(state.switching)
    }

    @Test
    fun afterQuickConnectOrARestoreThereIsNoNameSoTheServerIsNamedInstead() {
        signedIn.put(SignInResult(session, persisted = true, username = null))

        assertNull(viewModel().state.value.name)
    }

    @Test
    fun noSignInHeldIsReportedSoLaunchRoutingCanTakeOver() {
        assertTrue(viewModel().missingSession)
    }

    @Test
    fun theUserIsNeverSignedOutWithoutAskingForIt() {
        runBlocking { sessions.save(session) }
        signedIn.put(SignInResult(session, persisted = true, username = "sam"))

        viewModel()

        assertEquals(setOf(SessionRepository.SESSION_KEY), store.snapshot().keys)
        assertTrue(server.revokeCalls.isEmpty())
    }

    @Test
    fun switchUserSignsOutClearsTheHoldAndHandsTheSameServerBackToSignIn() =
        runTest {
            runBlocking { sessions.save(session) }
            signedIn.put(SignInResult(session, persisted = true, username = "sam"))
            val vm = viewModel()
            var switched = 0
            val job = backgroundScope.launch(UnconfinedTestDispatcher()) { vm.switched.collect { switched++ } }

            vm.onSwitchUser()

            assertTrue(store.snapshot().isEmpty())
            assertEquals(listOf(session), server.revokeCalls)
            assertNull(signedIn.peek())
            assertEquals(https, handOff.peek()?.server?.address)
            assertEquals(
                "Home Server",
                handOff
                    .peek()
                    ?.server
                    ?.server
                    ?.name,
            )
            assertEquals(1, switched)
            job.cancel()
        }

    @Test
    fun aSecondSwitchWhileTheFirstRunsDoesNothing() {
        runBlocking { sessions.save(session) }
        signedIn.put(SignInResult(session, persisted = true, username = "sam"))
        val vm = viewModel()

        vm.onSwitchUser()
        vm.onSwitchUser()

        assertEquals(1, server.revokeCalls.size)
        assertTrue(vm.state.value.switching)
    }

    @Test
    fun theStateShowsNoNameInText() {
        signedIn.put(SignInResult(session, persisted = true, username = "sam"))

        assertFalse("sam" in viewModel().state.value.toString())
    }

    @Test
    fun theHeldSessionIsGoneBeforeTheSignOutStartsSoNothingCanReuseItMidway() {
        runBlocking { sessions.save(session) }
        signedIn.put(SignInResult(session, persisted = true, username = "sam"))
        server.revokeGate = kotlinx.coroutines.CompletableDeferred()
        val vm = viewModel()

        vm.onSwitchUser()

        // The revoke is still waiting: whoever looks now finds no session to use.
        assertNull(signedIn.peek())
        server.revokeGate?.complete(Unit)
    }

    @Test
    fun switchUserStartsTheLibraryChoiceAgainSoARestartAsksForItAndNotSetupComplete() =
        runTest {
            runBlocking { sessions.save(session) }
            signedIn.put(SignInResult(session, persisted = true, username = "sam"))
            val vm = viewModel()
            val job = backgroundScope.launch(UnconfinedTestDispatcher()) { vm.switched.collect { } }

            vm.onSwitchUser()

            assertNull(settings.currentLibraries)
            assertEquals(ServerRecord(ServerAddress(Scheme.Https, "jellyfin.example.test", 443)), settings.current)
            job.cancel()
        }

    @Test
    fun aFailedRevokeShowsNothingAndStillSwitches() =
        runTest {
            runBlocking { sessions.save(session) }
            server.revokeResult = tv.stationbreak.core.domain.ServerResult.Failure(tv.stationbreak.core.domain.MediaServerError.Timeout())
            signedIn.put(SignInResult(session, persisted = true, username = "sam"))
            val vm = viewModel()
            var switched = 0
            val job = backgroundScope.launch(UnconfinedTestDispatcher()) { vm.switched.collect { switched++ } }

            vm.onSwitchUser()

            assertEquals(1, switched)
            assertTrue(store.snapshot().isEmpty())
            job.cancel()
        }
}
