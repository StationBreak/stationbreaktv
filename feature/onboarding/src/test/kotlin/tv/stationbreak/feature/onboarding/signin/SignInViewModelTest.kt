// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.signin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.UserId
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.server.ConnectedServer
import tv.stationbreak.feature.onboarding.server.ServerHandOff
import java.lang.reflect.Modifier

/** What a [SignInRequest] held when it arrived. The adapter zero-fills the password afterwards, so copy it now. */
private class Sent(
    val address: ServerAddress,
    val username: String,
    val password: String,
    val acknowledgement: CleartextAcknowledgement?,
)

/** A [MediaServer] that records full sign-in requests, can hold one open, and can script the Quick Connect flow. */
private class RecordingServer(
    val fake: FakeMediaServer = FakeMediaServer(),
) : MediaServer by fake {
    val sent = mutableListOf<Sent>()
    var gate: CompletableDeferred<ServerResult<Session>>? = null
    var quickConnectFlow: Flow<QuickConnectState>? = null

    override suspend fun signIn(request: SignInRequest): ServerResult<Session> {
        sent += Sent(request.address, request.username, String(request.password), request.acknowledgement)
        return gate?.await() ?: fake.signIn(request)
    }

    override fun quickConnect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): Flow<QuickConnectState> = quickConnectFlow ?: fake.quickConnect(address, acknowledgement)
}

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions") // One function per behaviour, as in ServerViewModelTest.
class SignInViewModelTest {
    private val server = RecordingServer()
    private val store = InMemorySecretStore()
    private val repository = SessionRepository(store, server)
    private val handOff = ServerHandOff()

    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val http = ServerAddress(Scheme.Http, "jellyfin.example.test", 8096)

    private fun session(
        admin: Boolean,
        address: ServerAddress = https,
    ) = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, address, null, admin)

    @Before
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        handOff.put(ConnectedServer(ResolvedServer(https, info), acknowledgement = null))
    }

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel() = SignInViewModel(server, repository, handOff)

    private fun SignInViewModel.fill(
        name: String = "sam",
        password: String = "dummy-pass-0001",
    ) {
        onUsernameChange(name)
        onPasswordChange(password)
    }

    /** Collects what the screen hands to the next destination, from the start. */
    private fun CoroutineScope.collectHandOver(
        vm: SignInViewModel,
        into: MutableList<SignInResult>,
    ): Job = launch(UnconfinedTestDispatcher()) { vm.signedIn.collect { into += it } }

    // The form

    @Test
    fun startsOnAnEmptyFormForTheServerThatWasHandedOver() {
        val vm = viewModel()

        assertFalse(vm.missingServer)
        assertEquals("Home Server", vm.state.value.serverName)
        assertEquals("", vm.state.value.username)
        assertEquals("", vm.state.value.password)
        assertFalse(vm.state.value.showPassword)
        assertEquals(SignInPhase.Idle, vm.state.value.phase)
        assertFalse(vm.state.value.cleartext)
    }

    @Test
    fun theShowToggleFlipsAndFlipsBack() {
        val vm = viewModel()

        vm.onToggleShowPassword()
        assertTrue(vm.state.value.showPassword)
        vm.onToggleShowPassword()
        assertFalse(vm.state.value.showPassword)
    }

    @Test
    fun aServerReachedOverCleartextIsFlaggedAndItsAcknowledgementTravelsWithTheSignIn() {
        val ack = CleartextAcknowledgement(http)
        handOff.put(ConnectedServer(ResolvedServer(http, info), ack))
        server.fake.signInResult = ServerResult.Success(session(admin = false, address = http))
        val vm = viewModel()
        vm.fill()

        vm.onSignIn()

        assertTrue(vm.state.value.cleartext)
        assertEquals(http, server.sent.single().address)
        assertSame(ack, server.sent.single().acknowledgement)
    }

    // Password sign-in

    @Test
    fun signInSendsTheTypedCredentialsToTheAddressTheServerScreenFound() {
        server.fake.signInResult = ServerResult.Success(session(admin = false))
        val vm = viewModel()
        vm.fill("sam", "dummy-pass-0001")

        vm.onSignIn()

        val sent = server.sent.single()
        assertEquals(https, sent.address)
        assertEquals("sam", sent.username)
        assertEquals("dummy-pass-0001", sent.password)
        assertNull(sent.acknowledgement)
    }

    @Test
    fun theUsernameIsTrimmedButThePasswordIsSentExactlyAsTyped() {
        server.fake.signInResult = ServerResult.Success(session(admin = false))
        val vm = viewModel()
        vm.fill("  sam  ", " pass with spaces ")

        vm.onSignIn()

        assertEquals("sam", server.sent.single().username)
        assertEquals(" pass with spaces ", server.sent.single().password)
    }

    @Test
    fun aBlankPasswordIsSentBecauseJellyfinAllowsAccountsWithoutOne() {
        server.fake.signInResult = ServerResult.Success(session(admin = false))
        val vm = viewModel()
        vm.fill("sam", "")

        vm.onSignIn()

        assertEquals("", server.sent.single().password)
    }

    @Test
    fun aBlankUsernameSendsNothingAndAsksForOne() {
        val vm = viewModel()
        vm.fill("   ", "dummy-pass-0001")

        vm.onSignIn()

        assertTrue(server.sent.isEmpty())
        val phase = vm.state.value.phase
        assertTrue(phase is SignInPhase.InvalidInput)
        assertEquals(R.string.signin_blank_username_message, (phase as SignInPhase.InvalidInput).copy.message)
        // Nothing was submitted, so the password is still there.
        assertEquals("dummy-pass-0001", vm.state.value.password)
    }

    @Test
    fun thePasswordIsClearedTheMomentItIsSubmitted() {
        val gate = CompletableDeferred<ServerResult<Session>>()
        server.gate = gate
        val vm = viewModel()
        vm.fill()

        vm.onSignIn()

        assertEquals(SignInPhase.SigningIn, vm.state.value.phase)
        assertEquals("", vm.state.value.password)
        assertEquals("sam", vm.state.value.username)
        assertEquals("dummy-pass-0001", server.sent.single().password)
        gate.complete(ServerResult.Success(session(admin = false)))
    }

    @Test
    fun noSecondRequestStartsWhileOneIsRunning() {
        server.gate = CompletableDeferred()
        val vm = viewModel()
        vm.fill()

        vm.onSignIn()
        vm.onPasswordChange("dummy-pass-0002")
        vm.onSignIn()
        vm.onQuickConnect()

        assertEquals(1, server.sent.size)
        assertTrue(server.fake.quickConnectCalls.isEmpty())
    }

    @Test
    fun successSavesThroughTheSessionRepositoryThenHandsOverTheSession() =
        runTest {
            val signedIn = session(admin = false)
            server.fake.signInResult = ServerResult.Success(signedIn)
            val vm = viewModel()
            vm.fill()
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)

            vm.onSignIn()

            assertEquals(listOf(SessionRepository.SESSION_KEY), store.writtenKeys)
            val result = handed.single()
            assertSame(signedIn, result.session)
            assertTrue(result.persisted)
            assertEquals(SignInPhase.SignedIn, vm.state.value.phase)
            job.cancel()
        }

    @Test
    fun theHandOverCarriesTheAdministratorFlagAsRecorded() =
        runTest {
            server.fake.signInResult = ServerResult.Success(session(admin = true))
            val vm = viewModel()
            vm.fill()
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)

            vm.onSignIn()

            // Decision 9: no warning, no different path. The flag is recorded and the sign-in goes on as usual.
            assertTrue(handed.single().session.isAdministrator)
            assertEquals(SignInPhase.SignedIn, vm.state.value.phase)
            job.cancel()
        }

    @Test
    fun aSessionThatCouldNotBeSavedIsStillHandedOverAndMarkedNotPersisted() =
        runTest {
            store.failSave = true
            server.fake.signInResult = ServerResult.Success(session(admin = false))
            val vm = viewModel()
            vm.fill()
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)

            vm.onSignIn()

            assertFalse(handed.single().persisted)
            job.cancel()
        }

    @Test
    fun theHandedOverServerIsDroppedOnceSignedIn() {
        server.fake.signInResult = ServerResult.Success(session(admin = false))
        val vm = viewModel()
        vm.fill()

        vm.onSignIn()

        assertNull(handOff.peek())
    }

    @Test
    fun wrongCredentialsKeepTheUsernameClearThePasswordAndSayWhy() {
        server.fake.signInResult = ServerResult.Failure(MediaServerError.HttpUnauthorized())
        val vm = viewModel()
        vm.fill("sam", "dummy-pass-0001")

        vm.onSignIn()

        val state = vm.state.value
        assertEquals("sam", state.username)
        assertEquals("", state.password)
        val failed = state.phase as SignInPhase.Failed
        assertEquals(R.string.error_unauthorized_message, failed.copy.message)
        assertTrue(failed.retypePassword)
        assertTrue(store.writtenKeys.isEmpty())
        assertNotNull(handOff.peek())
    }

    @Test
    fun forbiddenHasItsOwnWordingBecauseAProxyMayBeBehindIt() {
        server.fake.signInResult = ServerResult.Failure(MediaServerError.HttpForbidden())
        val vm = viewModel()
        vm.fill()

        vm.onSignIn()

        val failed = vm.state.value.phase as SignInPhase.Failed
        assertEquals(R.string.error_forbidden_message, failed.copy.message)
        assertNotEquals(R.string.error_unauthorized_message, failed.copy.message)
        assertEquals("", vm.state.value.password)
        assertTrue(failed.retypePassword)
    }

    @Test
    fun aServerLevelFailureOffersEditAddressAndKeepsTheUsername() {
        server.fake.signInResult = ServerResult.Failure(MediaServerError.Timeout(listOf("SocketTimeoutException")))
        val vm = viewModel()
        vm.fill()

        vm.onSignIn()

        val failed = vm.state.value.phase as SignInPhase.Failed
        assertEquals(R.string.error_timeout_message, failed.copy.message)
        assertEquals("sam", vm.state.value.username)
        assertEquals("", vm.state.value.password)
        assertFalse(failed.retypePassword)
    }

    @Test
    fun typingAfterAFailureClearsThePanel() {
        server.fake.signInResult = ServerResult.Failure(MediaServerError.HttpUnauthorized())
        val vm = viewModel()
        vm.fill()
        vm.onSignIn()

        vm.onPasswordChange("x")

        assertEquals(SignInPhase.Idle, vm.state.value.phase)
        assertEquals("x", vm.state.value.password)
    }

    // The password never outlives the screen

    @Test
    fun leavingTheScreenClearsAPasswordThatWasTypedButNotSubmitted() {
        val vm = viewModel()
        vm.fill()

        vm.onLeave()

        assertEquals("", vm.state.value.password)
        assertEquals("sam", vm.state.value.username)
    }

    @Test
    fun theViewModelCannotReceiveASavedStateHandle() {
        // Anything held in a SavedStateHandle goes into the saved-instance Bundle (Angua). No such parameter, no leak.
        val parameters =
            SignInViewModel::class.java.constructors
                .single()
                .parameterTypes
                .map { it.name }
        assertTrue(parameters.toString(), parameters.none { it.contains("SavedStateHandle") })
    }

    @Test
    fun theUiStateHoldsNoTokenAndNoServerAddress() {
        val fields = SignInUiState::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        // Names and flags only: the address and the acknowledgement live in the in-memory hand-off.
        assertEquals(
            setOf("serverName", "cleartext", "username", "password", "showPassword", "phase"),
            fields.map { it.name }.toSet(),
        )
    }

    // A server that was lost

    @Test
    fun withNothingHandedOverTheScreenIsMissingItsServerAndSendsNothing() {
        handOff.clear()
        val vm = viewModel()

        assertTrue(vm.missingServer)
        vm.fill()
        vm.onSignIn()
        vm.onQuickConnect()

        assertTrue(server.sent.isEmpty())
        assertTrue(server.fake.quickConnectCalls.isEmpty())
    }

    // Quick Connect

    @Test
    fun quickConnectShowsTheCodeOnceTheServerIssuesIt() {
        val updates = MutableSharedFlow<QuickConnectState>()
        server.quickConnectFlow = updates
        val vm = viewModel()

        vm.onQuickConnect()
        assertEquals(SignInPhase.QuickConnect(code = null), vm.state.value.phase)
        runBlocking { updates.emit(QuickConnectState.CodeIssued("123456")) }

        assertEquals(SignInPhase.QuickConnect(code = "123456"), vm.state.value.phase)
    }

    @Test
    fun quickConnectAsksForTheHandedOverAddress() {
        val vm = viewModel()

        vm.onQuickConnect()

        assertEquals(
            https,
            server.fake.quickConnectCalls
                .single()
                .first,
        )
    }

    @Test
    fun waitingChangesNothingOnScreen() {
        val updates = MutableSharedFlow<QuickConnectState>()
        server.quickConnectFlow = updates
        val vm = viewModel()
        vm.onQuickConnect()
        runBlocking {
            updates.emit(QuickConnectState.CodeIssued("123456"))
            updates.emit(QuickConnectState.Waiting)
        }

        assertEquals(SignInPhase.QuickConnect(code = "123456"), vm.state.value.phase)
    }

    @Test
    fun quickConnectApprovedSavesAndHandsOverTheSession() =
        runTest {
            val signedIn = session(admin = true)
            server.fake.quickConnectStates =
                listOf(
                    QuickConnectState.CodeIssued("123456"),
                    QuickConnectState.Waiting,
                    QuickConnectState.SignedIn(signedIn),
                )
            val vm = viewModel()
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)

            vm.onQuickConnect()

            assertEquals(listOf(SessionRepository.SESSION_KEY), store.writtenKeys)
            assertSame(signedIn, handed.single().session)
            assertTrue(handed.single().persisted)
            assertEquals(SignInPhase.SignedIn, vm.state.value.phase)
            assertNull(handOff.peek())
            job.cancel()
        }

    @Test
    fun quickConnectDisabledSaysSoAndLeavesThePasswordFormToUse() {
        server.fake.quickConnectStates = listOf(QuickConnectState.Failed(MediaServerError.QuickConnectDisabled()))
        val vm = viewModel()
        vm.fill("sam", "")

        vm.onQuickConnect()

        assertEquals(SignInPhase.QuickConnectDisabled, vm.state.value.phase)
        assertEquals("sam", vm.state.value.username)
    }

    @Test
    fun anExpiredCodeReturnsToTheFormWithAnExplanation() {
        server.fake.quickConnectStates = listOf(QuickConnectState.CodeIssued("123456"), QuickConnectState.Expired)
        val vm = viewModel()

        vm.onQuickConnect()

        assertEquals(SignInPhase.QuickConnectEnded(QuickConnectEnd.Expired), vm.state.value.phase)
    }

    @Test
    fun aDeniedCodeReturnsToTheFormWithAnExplanation() {
        server.fake.quickConnectStates = listOf(QuickConnectState.CodeIssued("123456"), QuickConnectState.Denied)
        val vm = viewModel()

        vm.onQuickConnect()

        assertEquals(SignInPhase.QuickConnectEnded(QuickConnectEnd.Denied), vm.state.value.phase)
    }

    @Test
    fun anotherQuickConnectFailureShowsItsPanelWithoutAskingForThePassword() {
        server.fake.quickConnectStates = listOf(QuickConnectState.Failed(MediaServerError.Timeout()))
        val vm = viewModel()

        vm.onQuickConnect()

        val failed = vm.state.value.phase as SignInPhase.Failed
        assertEquals(R.string.error_timeout_message, failed.copy.message)
        assertFalse(failed.retypePassword)
    }

    @Test
    fun cancellingQuickConnectStopsTheFlowAndReturnsToTheForm() {
        var stopped = false
        server.quickConnectFlow =
            flow {
                emit(QuickConnectState.CodeIssued("123456"))
                awaitCancellation()
            }.onCompletion { stopped = true }
        val vm = viewModel()
        vm.fill("sam", "")
        vm.onQuickConnect()

        vm.onCancelQuickConnect()

        assertTrue("the Quick Connect flow should have been cancelled", stopped)
        assertEquals(SignInPhase.Idle, vm.state.value.phase)
        assertEquals("sam", vm.state.value.username)
    }

    @Test
    fun anApprovalThatArrivesAfterCancelIsIgnored() {
        val updates = MutableSharedFlow<QuickConnectState>()
        server.quickConnectFlow = updates
        val vm = viewModel()
        vm.onQuickConnect()
        vm.onCancelQuickConnect()

        runBlocking { updates.emit(QuickConnectState.SignedIn(session(admin = false))) }

        assertEquals(SignInPhase.Idle, vm.state.value.phase)
        assertTrue(store.writtenKeys.isEmpty())
    }

    @Test
    fun leavingTheScreenWhileQuickConnectRunsStopsIt() {
        var stopped = false
        server.quickConnectFlow = flow<QuickConnectState> { awaitCancellation() }.onCompletion { stopped = true }
        val vm = viewModel()
        vm.onQuickConnect()

        vm.onLeave()

        assertTrue(stopped)
    }
}
