// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.SecretStoreResult
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ItemId
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

/**
 * A [MediaServer] that records full sign-in requests, can hold one open, and can script the Quick Connect flow. Its
 * gate ignores cancellation, as a real response does when it arrives after the caller has gone: a race test is only
 * worth having if the late answer really arrives.
 */
private class RecordingServer(
    val fake: FakeMediaServer = FakeMediaServer(),
) : MediaServer by fake {
    val sent = mutableListOf<Sent>()

    /** The very arrays the view model passed, so a test can see them wiped afterwards. */
    val passwordArrays = mutableListOf<CharArray>()
    var gate: CompletableDeferred<ServerResult<Session>>? = null
    var quickConnectFlow: Flow<QuickConnectState>? = null

    /** One flow per Quick Connect call, in order, before [quickConnectFlow]. */
    val quickConnectQueue = ArrayDeque<Flow<QuickConnectState>>()

    override suspend fun signIn(request: SignInRequest): ServerResult<Session> {
        sent += Sent(request.address, request.username, String(request.password), request.acknowledgement)
        passwordArrays += request.password
        val held = gate ?: return fake.signIn(request)
        return withContext(NonCancellable) { held.await() }
    }

    override fun quickConnect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): Flow<QuickConnectState> {
        val scripted = quickConnectQueue.removeFirstOrNull() ?: quickConnectFlow
        return scripted ?: fake.quickConnect(address, acknowledgement)
    }
}

/**
 * A hand-written flow that ignores cancellation until released, then emits [update] and finishes. `flow { }` would
 * throw at its next `emit` once cancelled, hiding a missing guard in the view model; this does not.
 */
private class StubbornFlow(
    private val update: QuickConnectState,
) : Flow<QuickConnectState> {
    val gate = CompletableDeferred<Unit>()

    override suspend fun collect(collector: FlowCollector<QuickConnectState>) {
        withContext(NonCancellable) { gate.await() }
        collector.emit(update)
    }
}

/** A [SecretStore] whose save can be held open, ignoring cancellation, to cancel or leave in the middle of it. */
private class GatedStore(
    private val inner: SecretStore,
) : SecretStore {
    var saveGate: CompletableDeferred<Unit>? = null

    override suspend fun save(
        key: String,
        value: String,
    ): SecretStoreResult<Unit> {
        saveGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
        return inner.save(key, value)
    }

    override suspend fun load(key: String) = inner.load(key)

    override suspend fun clear(key: String) = inner.clear(key)
}

/** Builds [viewModel] inside a [ViewModelStore], so clearing the store clears it the way the platform does. */
private fun <T : ViewModel> ViewModelStore.host(
    viewModel: T,
    type: Class<T>,
): T {
    val factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = viewModel as V
        }
    return ViewModelProvider.create(this, factory)[type]
}

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions") // One function per behaviour, as in ServerViewModelTest.
class SignInViewModelTest {
    private val server = RecordingServer()
    private val store = InMemorySecretStore()
    private val gatedStore = GatedStore(store)
    private val repository = SessionRepository(gatedStore, server)
    private val handOff = ServerHandOff()
    private val signedIn = SignedInHandOff()
    private val settings =
        InMemoryServerSettings(
            ServerRecord(ServerAddress(Scheme.Https, "jellyfin.example.test", 443)),
            setOf(ItemId("movies")),
        )

    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val http = ServerAddress(Scheme.Http, "jellyfin.example.test", 8096)
    private val connected = ConnectedServer(ResolvedServer(https, info), acknowledgement = null)

    private fun session(
        admin: Boolean,
        address: ServerAddress = https,
    ) = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, address, null, admin)

    @Before
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        handOff.put(connected)
    }

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel() = SignInViewModel(server, repository, handOff, signedIn, settings)

    /** A view model hosted in [owner], so `owner.clear()` is the platform clearing it. */
    private fun hosted(owner: ViewModelStore) = owner.host(viewModel(), SignInViewModel::class.java)

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
    fun aPasswordSignInKeepsTheTypedNameForSignedInAsAndHoldsTheSessionInMemory() =
        runTest {
            val signed = session(admin = false)
            server.fake.signInResult = ServerResult.Success(signed)
            val vm = viewModel()
            vm.fill("  sam  ")
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)

            vm.onSignIn()

            assertEquals("sam", handed.single().username)
            assertSame(handed.single(), signedIn.peek())
            job.cancel()
        }

    @Test
    fun quickConnectKnowsNoNameSoSignedInAsHasNoneToShow() =
        runTest {
            val signed = session(admin = false)
            server.fake.quickConnectStates = listOf(QuickConnectState.SignedIn(signed))
            val vm = viewModel()
            vm.onUsernameChange("left over from an earlier try")
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)

            vm.onQuickConnect()

            assertNull(handed.single().username)
            assertSame(signed, signedIn.peek()?.session)
            job.cancel()
        }

    @Test
    fun aSessionThatWasCancelledAwayIsNeverHeldForTheLibraryPicker() =
        runTest {
            val gate = CompletableDeferred<ServerResult<Session>>()
            server.gate = gate
            val vm = viewModel()
            vm.fill()

            vm.onSignIn()
            vm.onLeave()
            gate.complete(ServerResult.Success(session(admin = false)))

            assertNull(signedIn.peek())
        }

    @Test
    fun aStoredSessionTheServerRefusedStartsTheFormWithOneLineSayingWhy() {
        handOff.put(ConnectedServer(ResolvedServer(https, info), null, MediaServerError.SessionExpired()))
        val vm = viewModel()

        val phase = vm.state.value.phase
        assertTrue(phase is SignInPhase.SessionEnded)
        assertEquals(R.string.error_session_expired_message, (phase as SignInPhase.SessionEnded).copy.message)
        vm.onUsernameChange("s")
        assertEquals(SignInPhase.Idle, vm.state.value.phase)
    }

    @Test
    fun aServerThatChangedUnderTheStoredSessionSaysSoToo() {
        handOff.put(ConnectedServer(ResolvedServer(https, info), null, MediaServerError.ServerMismatch()))

        val phase = viewModel().state.value.phase as SignInPhase.SessionEnded

        assertEquals(R.string.error_server_mismatch_message, phase.copy.message)
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
        handOff.clear(connected)
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
    fun anApprovalThatArrivesAfterCancelIsIgnoredAndTheTokenItIssuedIsRevoked() {
        // The flow does not stop when cancelled (a response already in flight), so only the view model's guard keeps
        // the late approval off the screen.
        val late = StubbornFlow(QuickConnectState.SignedIn(session(admin = false)))
        server.quickConnectFlow = late
        val vm = viewModel()
        vm.onQuickConnect()
        vm.onCancelQuickConnect()

        late.gate.complete(Unit)

        assertEquals(SignInPhase.Idle, vm.state.value.phase)
        assertTrue(store.writtenKeys.isEmpty())
        assertEquals(1, server.fake.revokeCalls.size)
        assertNotNull(handOff.peek())
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

    // A session is saved only if the user is still waiting (Angua M1, Nobby L1)

    @Test
    fun quickConnectCancelledDuringTheSaveIsRevokedNotKept() =
        runTest {
            gatedStore.saveGate = CompletableDeferred()
            server.quickConnectFlow =
                flow {
                    emit(QuickConnectState.CodeIssued("123456"))
                    emit(QuickConnectState.SignedIn(session(admin = false)))
                    awaitCancellation()
                }
            val vm = viewModel()
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)
            vm.onQuickConnect()

            vm.onCancelQuickConnect()
            gatedStore.saveGate!!.complete(Unit)

            assertEquals(SignInPhase.Idle, vm.state.value.phase)
            assertTrue(handed.isEmpty())
            assertTrue("the stored session should be gone", store.snapshot().isEmpty())
            assertEquals(listOf(SessionRepository.SESSION_KEY), store.clearedKeys)
            assertEquals(1, server.fake.revokeCalls.size)
            assertNotNull("the user is back on the form, so the server stays handed over", handOff.peek())
            job.cancel()
        }

    @Test
    fun aPasswordSignInLeftDuringTheSaveIsRevokedNotKept() =
        runTest {
            gatedStore.saveGate = CompletableDeferred()
            server.fake.signInResult = ServerResult.Success(session(admin = false))
            val owner = ViewModelStore()
            val vm = hosted(owner)
            vm.fill()
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)
            vm.onSignIn()

            vm.onLeave()
            owner.clear()
            gatedStore.saveGate!!.complete(Unit)

            assertTrue(handed.isEmpty())
            assertNotEquals(SignInPhase.SignedIn, vm.state.value.phase)
            assertTrue("the stored session should be gone", store.snapshot().isEmpty())
            assertEquals(1, server.fake.revokeCalls.size)
            job.cancel()
        }

    @Test
    fun aViewModelClearedDuringTheSaveRevokesWhatItWasAboutToKeep() =
        runTest {
            gatedStore.saveGate = CompletableDeferred()
            server.fake.signInResult = ServerResult.Success(session(admin = false))
            val owner = ViewModelStore()
            val vm = hosted(owner)
            vm.fill()
            vm.onSignIn()

            owner.clear()
            gatedStore.saveGate!!.complete(Unit)

            assertTrue(store.snapshot().isEmpty())
            assertEquals(1, server.fake.revokeCalls.size)
        }

    @Test
    fun aSignInThatIsStillWaitedForIsKeptAndNotRevoked() =
        runTest {
            gatedStore.saveGate = CompletableDeferred()
            server.fake.signInResult = ServerResult.Success(session(admin = false))
            val vm = viewModel()
            vm.fill()
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)
            vm.onSignIn()

            gatedStore.saveGate!!.complete(Unit)

            assertEquals(1, handed.size)
            assertEquals(listOf(SessionRepository.SESSION_KEY), store.writtenKeys)
            assertTrue(server.fake.revokeCalls.isEmpty())
            job.cancel()
        }

    @Test
    fun aSessionThatCouldNotBeSavedAlsoClearsAnOlderStoredOne() =
        runTest {
            runBlocking { store.save(SessionRepository.SESSION_KEY, "older-stored-session") }
            store.failSave = true
            server.fake.signInResult = ServerResult.Success(session(admin = false))
            val vm = viewModel()
            vm.fill()
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)

            vm.onSignIn()

            assertFalse(handed.single().persisted)
            assertTrue("an older session must not be restorable later", store.snapshot().isEmpty())
            job.cancel()
        }

    // The hand-off

    @Test
    fun anOldViewModelClearedAfterANewServerWasHandedOverLeavesTheNewOneAlone() {
        val owner = ViewModelStore()
        hosted(owner)
        val newer = ConnectedServer(ResolvedServer(http, info), CleartextAcknowledgement(http))
        handOff.put(newer)

        owner.clear()

        assertSame(newer, handOff.peek())
    }

    @Test
    fun aViewModelThatIsClearedDropsItsOwnHandedOverServer() {
        val owner = ViewModelStore()
        hosted(owner)
        assertSame(connected, handOff.peek())

        owner.clear()

        assertNull(handOff.peek())
    }

    // Quick Connect attempts (Nobby M1)

    @Test
    fun anOldQuickConnectThatAnswersAfterARestartChangesNothing() {
        val old = StubbornFlow(QuickConnectState.CodeIssued("111111"))
        val fresh = MutableSharedFlow<QuickConnectState>()
        server.quickConnectQueue.addLast(old)
        server.quickConnectQueue.addLast(fresh)
        val vm = viewModel()
        vm.onQuickConnect()
        vm.onCancelQuickConnect()
        vm.onQuickConnect()
        runBlocking { fresh.emit(QuickConnectState.CodeIssued("222222")) }

        old.gate.complete(Unit)

        assertEquals(SignInPhase.QuickConnect(code = "222222"), vm.state.value.phase)
    }

    @Test
    fun anOldApprovalThatArrivesAfterARestartIsRevokedAndTheNewAttemptCarriesOn() =
        runTest {
            val old = StubbornFlow(QuickConnectState.SignedIn(session(admin = false)))
            val fresh = MutableSharedFlow<QuickConnectState>()
            server.quickConnectQueue.addLast(old)
            server.quickConnectQueue.addLast(fresh)
            val vm = viewModel()
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)
            vm.onQuickConnect()
            vm.onCancelQuickConnect()
            vm.onQuickConnect()
            runBlocking { fresh.emit(QuickConnectState.CodeIssued("222222")) }

            old.gate.complete(Unit)

            assertEquals(SignInPhase.QuickConnect(code = "222222"), vm.state.value.phase)
            assertTrue(handed.isEmpty())
            assertTrue(store.writtenKeys.isEmpty())
            assertEquals(1, server.fake.revokeCalls.size)
            job.cancel()
        }

    // Password races (Nobby M3)

    @Test
    fun leavingWhileSigningInEndsTheWaitAndKeepsNoPassword() {
        server.gate = CompletableDeferred()
        val vm = viewModel()
        vm.fill()
        vm.onSignIn()

        vm.onLeave()

        assertEquals(SignInPhase.Idle, vm.state.value.phase)
        assertEquals("", vm.state.value.password)
        server.gate!!.complete(ServerResult.Failure(MediaServerError.HttpUnauthorized()))
    }

    @Test
    fun aViewModelClearedWhileSigningInKeepsNoPassword() {
        server.gate = CompletableDeferred()
        val owner = ViewModelStore()
        val vm = hosted(owner)
        vm.fill()
        vm.onSignIn()

        owner.clear()

        assertEquals("", vm.state.value.password)
        server.gate!!.complete(ServerResult.Failure(MediaServerError.HttpUnauthorized()))
    }

    @Test
    fun aSuccessThatArrivesAfterLeavingIsRevokedNotSaved() =
        runTest {
            server.gate = CompletableDeferred()
            val vm = viewModel()
            vm.fill()
            val handed = mutableListOf<SignInResult>()
            val job = backgroundScope.collectHandOver(vm, handed)
            vm.onSignIn()
            vm.onLeave()

            server.gate!!.complete(ServerResult.Success(session(admin = false)))

            assertTrue(handed.isEmpty())
            assertTrue(store.writtenKeys.isEmpty())
            assertEquals(1, server.fake.revokeCalls.size)
            assertEquals(SignInPhase.Idle, vm.state.value.phase)
            job.cancel()
        }

    @Test
    fun aFailureThatArrivesAfterLeavingDoesNotBringBackAPanel() {
        server.gate = CompletableDeferred()
        val vm = viewModel()
        vm.fill()
        vm.onSignIn()
        vm.onLeave()

        server.gate!!.complete(ServerResult.Failure(MediaServerError.HttpUnauthorized()))

        assertEquals(SignInPhase.Idle, vm.state.value.phase)
    }

    @Test
    fun aFailureThatArrivesDuringANewQuickConnectDoesNotReplaceIt() {
        server.gate = CompletableDeferred()
        server.quickConnectFlow = MutableSharedFlow()
        val vm = viewModel()
        vm.fill()
        vm.onSignIn()
        vm.onLeave()
        vm.onQuickConnect()

        server.gate!!.complete(ServerResult.Failure(MediaServerError.Timeout()))

        assertEquals(SignInPhase.QuickConnect(code = null), vm.state.value.phase)
    }

    @Test
    fun aFailureThatArrivesAfterTheUserTypedStillShowsAndKeepsWhatWasTyped() {
        server.gate = CompletableDeferred()
        val vm = viewModel()
        vm.fill()
        vm.onSignIn()
        vm.onPasswordChange("dummy-pass-0002")

        server.gate!!.complete(ServerResult.Failure(MediaServerError.HttpUnauthorized()))

        assertTrue(vm.state.value.phase is SignInPhase.Failed)
        assertEquals("dummy-pass-0002", vm.state.value.password)
    }

    @Test
    fun theSubmittedPasswordArrayIsWipedOnceTheRequestEnds() {
        server.gate = CompletableDeferred()
        val vm = viewModel()
        vm.fill()
        vm.onSignIn()
        val array = server.passwordArrays.single()
        assertEquals("dummy-pass-0001", String(array))

        server.gate!!.complete(ServerResult.Failure(MediaServerError.HttpUnauthorized()))

        assertTrue("the array should be all zero characters", array.all { it == '\u0000' })
    }

    @Test
    fun theSubmittedPasswordArrayIsWipedWhenTheViewModelIsClearedFirst() {
        server.gate = CompletableDeferred()
        val owner = ViewModelStore()
        val vm = hosted(owner)
        vm.fill()
        vm.onSignIn()
        val array = server.passwordArrays.single()

        owner.clear()
        server.gate!!.complete(ServerResult.Success(session(admin = false)))

        assertTrue("the array should be all zero characters", array.all { it == '\u0000' })
    }

    @Test
    fun aNewSignInStartsTheLibraryChoiceAgain() =
        runTest {
            server.fake.signInResult = ServerResult.Success(session(admin = false))
            val vm = viewModel()
            vm.fill()

            vm.onSignIn()

            assertNull(settings.currentLibraries)
            assertEquals(ServerRecord(https), settings.current)
        }

    @Test
    fun aSessionThatWasCancelledAwayLeavesTheLibraryChoiceAlone() =
        runTest {
            val gate = CompletableDeferred<ServerResult<Session>>()
            server.gate = gate
            val vm = viewModel()
            vm.fill()

            vm.onSignIn()
            vm.onLeave()
            gate.complete(ServerResult.Success(session(admin = false)))

            assertEquals(setOf(ItemId("movies")), settings.currentLibraries)
        }

    @Test
    fun theHandOverShowsNeitherTheNameNorTheToken() {
        val text = SignInResult(session(admin = false), persisted = true, username = "sam").toString()

        assertFalse(text, "sam" in text)
        assertFalse(text, "dummy-token" in text)
    }
}
