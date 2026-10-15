// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.core.model.UserId
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.signin.SignInResult
import tv.stationbreak.feature.onboarding.signin.SignedInHandOff
import tv.stationbreak.feature.onboarding.trust.CertificateTrust
import tv.stationbreak.feature.onboarding.trust.TrustPrompt
import tv.stationbreak.feature.onboarding.trust.TrustReason

/**
 * A [MediaServer] whose `discover` can be held open, to test the in-progress state and Cancel. It does NOT cooperate
 * with cancellation (the wait is non-cancellable), so a test passes only if the ViewModel itself drops a stale result.
 */
private class GatedServer(
    val fake: FakeMediaServer = FakeMediaServer(),
) : MediaServer by fake {
    var gate: CompletableDeferred<ServerResult<ResolvedServer>>? = null

    override suspend fun discover(input: String): ServerResult<ResolvedServer> {
        val held = gate ?: return fake.discover(input)
        fake.discoverCalls += input
        return withContext(NonCancellable) { held.await() }
    }
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

/** A [MediaServer] whose `trust` can be held open, ignoring cancellation, as [GatedServer] does for `discover`. */
private class GatedTrustServer(
    val fake: FakeMediaServer,
) : MediaServer by fake {
    var gate: CompletableDeferred<ServerResult<ResolvedServer>>? = null

    override suspend fun trust(
        input: String,
        pin: SpkiPin,
    ): ServerResult<ResolvedServer> {
        val held = gate ?: return fake.trust(input, pin)
        fake.trustCalls += input to pin
        return withContext(NonCancellable) { held.await() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // One function per behaviour of the screen's view model.
class ServerViewModelTest {
    private val server = GatedServer()
    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private val handOff = ServerHandOff()
    private val signedIn = SignedInHandOff()
    private val settings = InMemoryServerSettings()
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, server)

    private fun viewModel() = viewModelWith(server)

    private val gatedTrust = GatedTrustServer(server.fake)

    private fun viewModelWith(mediaServer: MediaServer) =
        ServerViewModel(
            mediaServer,
            handOff,
            signedIn,
            settings,
            CertificateTrust(mediaServer, settings, sessions),
            sessions,
        )

    private fun found(address: ServerAddress) = ServerResult.Success(ResolvedServer(address, info))

    private fun fails(error: MediaServerError) = ServerResult.Failure(error)

    private fun ServerViewModel.phase() = state.value.phase

    private fun ServerViewModel.test(input: String) {
        onInputChange(input)
        onTest()
    }

    @Test
    fun startsIdleWithAnEmptyAddress() {
        val vm = viewModel()
        assertEquals("", vm.state.value.input)
        assertEquals(ServerPhase.Idle, vm.phase())
        assertTrue(server.fake.discoverCalls.isEmpty())
    }

    @Test
    fun successShowsTheServerNameAndVersionWithoutAnAcknowledgement() {
        server.fake.discoverResult = found(https)
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        val phase = vm.phase() as ServerPhase.Connected
        assertEquals("Home Server", phase.server.server.name)
        assertEquals(ServerVersion(12, 2, 0), phase.server.server.version)
        assertNull(phase.acknowledgement)
        assertEquals(listOf("jellyfin.example.test"), server.fake.discoverCalls)
    }

    @Test
    fun theTypedTextIsTrimmedButOtherwiseSentAsIs() {
        server.fake.discoverResult = found(https)
        val vm = viewModel()
        vm.test("  jellyfin.example.test:8096/jf \n")
        assertEquals(listOf("jellyfin.example.test:8096/jf"), server.fake.discoverCalls)
    }

    @Test
    fun theTestingStateShowsAtOnceAndCancelEndsIt() {
        server.gate = CompletableDeferred()
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        assertEquals(ServerPhase.Testing, vm.phase())
        vm.onCancel()
        assertEquals(ServerPhase.Idle, vm.phase())
        assertEquals("jellyfin.example.test", vm.state.value.input)
    }

    @Test
    fun aResultThatArrivesAfterCancelIsIgnored() {
        val gate = CompletableDeferred<ServerResult<ResolvedServer>>()
        server.gate = gate
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        vm.onCancel()
        gate.complete(found(https))
        assertEquals(ServerPhase.Idle, vm.phase())
    }

    @Test
    fun cancelThenTestAgainIgnoresTheOldResult() {
        val first = CompletableDeferred<ServerResult<ResolvedServer>>()
        val second = CompletableDeferred<ServerResult<ResolvedServer>>()
        server.gate = first
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        vm.onCancel()
        server.gate = second
        vm.onTest()
        first.complete(found(https))
        assertEquals(ServerPhase.Testing, vm.phase())
        second.complete(fails(MediaServerError.Timeout()))
        assertTrue(vm.phase() is ServerPhase.Failed)
    }

    @Test
    fun typingDuringTestingGoesBackToIdleAndTheOldResultIsIgnored() {
        val gate = CompletableDeferred<ServerResult<ResolvedServer>>()
        server.gate = gate
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        vm.onInputChange("jellyfin.example.tes")
        gate.complete(found(https))
        assertEquals(ServerPhase.Idle, vm.phase())
        assertEquals("jellyfin.example.tes", vm.state.value.input)
    }

    @Test
    fun cancelOnlyActsWhileTesting() {
        server.fake.discoverResult = found(https)
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        vm.onCancel()
        assertTrue(vm.phase() is ServerPhase.Connected)
        server.fake.discoverResult = fails(MediaServerError.Timeout())
        vm.onInputChange("jellyfin.example.test")
        vm.onTest()
        vm.onCancel()
        assertTrue(vm.phase() is ServerPhase.Failed)
    }

    @Test
    fun editAddressCancelsARunningTest() {
        val first = CompletableDeferred<ServerResult<ResolvedServer>>()
        val second = CompletableDeferred<ServerResult<ResolvedServer>>()
        server.gate = first
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        vm.onEditAddress()
        assertEquals(ServerPhase.Idle, vm.phase())
        server.gate = second
        vm.onTest()
        first.complete(found(https))
        assertEquals(ServerPhase.Testing, vm.phase())
    }

    @Test
    fun aSecondTestWhileTestingDoesNotSendASecondRequest() {
        server.gate = CompletableDeferred()
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        vm.onTest()
        assertEquals(1, server.fake.discoverCalls.size)
    }

    @Test
    fun aBlankAddressIsInvalidBeforeAnyRequest() {
        val vm = viewModel()
        vm.test("   ")
        val phase = vm.phase() as ServerPhase.InvalidInput
        assertEquals(ErrorGroup.Address, phase.copy.group)
        assertEquals(R.string.server_blank_message, phase.copy.message)
        assertEquals(R.string.server_blank_details, phase.copy.details)
        assertTrue(server.fake.discoverCalls.isEmpty())
    }

    @Test
    fun anInvalidAddressFromDiscoverShowsUnderTheFieldNotInThePanel() {
        server.fake.discoverResult = fails(MediaServerError.InvalidAddress())
        val vm = viewModel()
        vm.test("http://")
        val phase = vm.phase() as ServerPhase.InvalidInput
        assertEquals(R.string.error_invalid_address_message, phase.copy.message)
    }

    @Test
    fun aNetworkErrorOffersRetryAndEditAddress() {
        server.fake.discoverResult = fails(MediaServerError.DnsFailure(listOf("UnknownHostException")))
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        val phase = vm.phase() as ServerPhase.Failed
        assertEquals(MediaServerError.DnsFailure(listOf("UnknownHostException")), phase.error)
        assertEquals(ErrorGroup.Network, phase.copy.group)
        assertEquals(ErrorAction.Retry, phase.copy.primary)
        assertEquals(ErrorAction.EditAddress, phase.copy.secondary)
    }

    @Test
    fun aNotUsableErrorExplainsWhy() {
        server.fake.discoverResult = fails(MediaServerError.UnsupportedVersion(ServerVersion(10, 11, 8)))
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        val phase = vm.phase() as ServerPhase.Failed
        assertEquals(ErrorGroup.NotUsable, phase.copy.group)
        assertEquals(listOf("10.11.8", "12.0"), ErrorCopyTable.messageArgs(phase.error))
    }

    @Test
    fun aTlsErrorWithNoCertificateOffersNoTrustAndOnlyEditAddress() {
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted())
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        val phase = vm.phase() as ServerPhase.Failed
        assertEquals(ErrorGroup.Tls, phase.copy.group)
        assertEquals(ErrorAction.EditAddress, phase.copy.primary)
    }

    @Test
    fun everyErrorDiscoverCanReturnEndsInAPanelOrUnderTheField() {
        for (type in MediaServerError::class.java.permittedSubclasses!!) {
            val error = ErrorSamples.of(type)
            server.fake.discoverResult = fails(error)
            val vm = viewModel()
            vm.test("jellyfin.example.test")
            val phase = vm.phase()
            assertTrue("${type.simpleName} -> $phase", phase is ServerPhase.Failed || phase is ServerPhase.InvalidInput)
        }
    }

    @Test
    fun retryRunsTheTestAgain() {
        server.fake.discoverResult = fails(MediaServerError.Timeout())
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        server.fake.discoverResult = found(https)
        vm.onTest()
        assertTrue(vm.phase() is ServerPhase.Connected)
        assertEquals(2, server.fake.discoverCalls.size)
    }

    @Test
    fun editingTheAddressClearsAnErrorAndAConnection() {
        server.fake.discoverResult = found(https)
        val vm = viewModel()
        vm.test("jellyfin.example.test")
        vm.onInputChange("jellyfin.example.tes")
        assertEquals(ServerPhase.Idle, vm.phase())
        server.fake.discoverResult = fails(MediaServerError.Timeout())
        vm.onTest()
        vm.onEditAddress()
        assertEquals(ServerPhase.Idle, vm.phase())
        assertEquals("jellyfin.example.tes", vm.state.value.input)
    }

    @Test
    fun httpToAPublicHostAsksBeforeContinuing() {
        val http = ServerAddress(Scheme.Http, "jellyfin.example.test", 80)
        server.fake.discoverResult = found(http)
        val vm = viewModel()
        vm.test("http://jellyfin.example.test")
        val phase = vm.phase() as ServerPhase.ConfirmCleartext
        assertEquals(http, phase.server.address)
        assertEquals(false, phase.server.resolvedByFallback)
    }

    @Test
    fun anHttpFallbackAsksEvenOnAPrivateHostAndSaysSo() {
        val fallback = ServerAddress(Scheme.Http, "192.0.2.10", 8096, viaFallback = true)
        server.fake.discoverResult = found(fallback)
        val vm = viewModel()
        vm.test("192.0.2.10:8096")
        val phase = vm.phase() as ServerPhase.ConfirmCleartext
        assertEquals(true, phase.server.resolvedByFallback)
    }

    @Test
    fun aTypedHttpToAPrivateHostDoesNotAsk() {
        val typed = ServerAddress(Scheme.Http, "10.0.0.5", 8096)
        server.fake.discoverResult = found(typed)
        val vm = viewModel()
        vm.test("http://10.0.0.5:8096")
        val phase = vm.phase() as ServerPhase.Connected
        assertNull(phase.acknowledgement)
    }

    @Test
    fun confirmingCleartextConnectsWithAnAcknowledgementForExactlyThatAddress() {
        val http = ServerAddress(Scheme.Http, "jellyfin.example.test", 80)
        server.fake.discoverResult = found(http)
        val vm = viewModel()
        vm.test("http://jellyfin.example.test")
        vm.onConfirmCleartext()
        val phase = vm.phase() as ServerPhase.Connected
        assertEquals(CleartextAcknowledgement(http), phase.acknowledgement)
        assertNotNull(phase.acknowledgement)
        assertTrue(phase.acknowledgement!!.matches(http))
    }

    @Test
    fun cancellingCleartextGoesBackToIdleWithTheAddressKept() {
        val http = ServerAddress(Scheme.Http, "jellyfin.example.test", 80)
        server.fake.discoverResult = found(http)
        val vm = viewModel()
        vm.test("http://jellyfin.example.test")
        vm.onDismissCleartext()
        assertEquals(ServerPhase.Idle, vm.phase())
        assertEquals("http://jellyfin.example.test", vm.state.value.input)
    }

    @Test
    fun confirmingWithNothingToConfirmDoesNothing() {
        val vm = viewModel()
        vm.onConfirmCleartext()
        assertEquals(ServerPhase.Idle, vm.phase())
    }

    @Test
    fun theStateNeverHoldsAPasswordOrToken() {
        // Unit 1 has no credentials at all; this pins that no field of the state could carry one.
        val fields =
            ServerUiState::class.java.declaredFields
                .filterNot {
                    java.lang.reflect.Modifier
                        .isStatic(it.modifiers)
                }.map { it.name }
                .toSet()
        assertEquals(setOf("input", "phase"), fields)
    }

    // --- trusting a self-signed certificate (spec 05, Unit 3) ---

    private val spki = SpkiPin(ByteArray(SpkiPin.SIZE) { 8 })
    private val selfSigned = CertificateEvidence(spki, selfSigned = true, notBeforeMillis = 1L, notAfterMillis = 2L)
    private val nas = ServerAddress(Scheme.Https, "nas.example.test", 8920)

    @Test
    fun aSelfSignedCertificateAsksTheUserAndSendsNothingMore() {
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted(certificate = selfSigned))
        val vm = viewModel()
        vm.test("nas.example.test:8920")
        val phase = vm.phase() as ServerPhase.TrustRequired
        assertEquals(TrustPrompt(TrustReason.Untrusted, selfSigned), phase.prompt)
        assertEquals("nas.example.test:8920", phase.input)
        assertTrue(server.fake.trustCalls.isEmpty())
        assertTrue(server.fake.signInCalls.isEmpty())
        assertTrue(settings.saved.isEmpty())
    }

    @Test
    fun everyTrustableErrorAsksTheUserAndACaChainedOneDoesNot() {
        val trustable =
            listOf(
                MediaServerError.TlsHostnameMismatch(certificate = selfSigned),
                MediaServerError.TlsCertificateExpired(certificate = selfSigned),
                MediaServerError.TlsCertificateNotYetValid(certificate = selfSigned),
                MediaServerError.TlsCertificateChanged(certificate = selfSigned),
            )
        for (error in trustable) {
            server.fake.discoverResult = fails(error)
            val vm = viewModel()
            vm.test("nas.example.test:8920")
            assertTrue("$error -> ${vm.phase()}", vm.phase() is ServerPhase.TrustRequired)
        }
        val deadEnd = selfSigned.copy(selfSigned = false)
        val deadEnds =
            listOf(
                MediaServerError.TlsUntrusted(certificate = deadEnd),
                MediaServerError.TlsCertificateExpired(certificate = deadEnd),
            )
        for (error in deadEnds) {
            server.fake.discoverResult = fails(error)
            val vm = viewModel()
            vm.test("nas.example.test:8920")
            assertTrue("$error -> ${vm.phase()}", vm.phase() is ServerPhase.Failed)
        }
    }

    @Test
    fun aHandshakeFailureNeverAsksTheUser() {
        server.fake.discoverResult = fails(MediaServerError.TlsHandshakeFailed())
        val vm = viewModel()
        vm.test("nas.example.test:8920")
        assertTrue(vm.phase() is ServerPhase.Failed)
    }

    @Test
    fun trustingPinsTheCapturedSpkiAndConnectsWithNoAcknowledgement() {
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted(certificate = selfSigned))
        val vm = viewModel()
        vm.test("nas.example.test:8920")
        server.fake.trustResult = found(nas)

        vm.onTrustCertificate()

        assertEquals(listOf("nas.example.test:8920" to spki), server.fake.trustCalls)
        val connected = vm.phase() as ServerPhase.Connected
        assertEquals(nas, connected.server.address)
        assertNull(connected.acknowledgement)
        assertEquals(ServerRecord(nas, spki), settings.current)
    }

    @Test
    fun theTrustAttemptShowsTheProgressStateAndCancelTakesItBack() {
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted(certificate = selfSigned))
        val held = CompletableDeferred<ServerResult<ResolvedServer>>()
        gatedTrust.gate = held
        val gated = viewModelWith(gatedTrust)
        gated.test("nas.example.test:8920")

        gated.onTrustCertificate()
        assertEquals(ServerPhase.Testing, gated.phase())
        gated.onCancel()
        assertEquals(ServerPhase.Idle, gated.phase())
        held.complete(found(nas))
        assertEquals(ServerPhase.Idle, gated.phase())
        assertTrue(settings.saved.isEmpty())
    }

    @Test
    fun aServerThatShowsAnotherCertificateAtConfirmTimeIsAskedAboutAgain() {
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted(certificate = selfSigned))
        val vm = viewModel()
        vm.test("nas.example.test:8920")
        val newer = SpkiPin(ByteArray(SpkiPin.SIZE) { 9 })
        server.fake.trustResult =
            fails(MediaServerError.TlsCertificateChanged(certificate = selfSigned.copy(spki = newer)))

        vm.onTrustCertificate()

        val phase = vm.phase() as ServerPhase.TrustRequired
        assertEquals(TrustReason.Changed, phase.prompt.reason)
        assertEquals(newer, phase.prompt.certificate.spki)
        assertTrue(settings.saved.isEmpty())
    }

    @Test
    fun aTrustAttemptThatFailsForAnotherReasonShowsTheErrorPanel() {
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted(certificate = selfSigned))
        val vm = viewModel()
        vm.test("nas.example.test:8920")
        server.fake.trustResult = fails(MediaServerError.Timeout())

        vm.onTrustCertificate()

        assertTrue(vm.phase() is ServerPhase.Failed)
        assertTrue(settings.saved.isEmpty())
    }

    @Test
    fun notTrustingShowsTheNotConnectedNoticeWithTheAddressKeptAndStoresNothing() {
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted(certificate = selfSigned))
        val vm = viewModel()
        vm.test("nas.example.test:8920")

        vm.onDontTrust()

        assertEquals(ServerPhase.NotTrusted, vm.phase())
        assertEquals("nas.example.test:8920", vm.state.value.input)
        assertTrue(server.fake.trustCalls.isEmpty())
        assertTrue(settings.saved.isEmpty())
    }

    @Test
    fun theNotConnectedNoticeEndsWithTheNextTestOrTheNextEdit() {
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted(certificate = selfSigned))
        val vm = viewModel()
        vm.test("nas.example.test:8920")
        vm.onDontTrust()
        vm.onInputChange("nas.example.test:8921")
        assertEquals(ServerPhase.Idle, vm.phase())

        vm.test("nas.example.test:8920")
        vm.onDontTrust()
        vm.onTest()
        assertTrue(vm.phase() is ServerPhase.TrustRequired)

        vm.onDontTrust()
        vm.onEditAddress()
        assertEquals(ServerPhase.Idle, vm.phase())
    }

    @Test
    fun aRecordThatCouldNotBeSavedShowsAPlainErrorAndIsNotConnected() {
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted(certificate = selfSigned))
        val vm = viewModel()
        vm.test("nas.example.test:8920")
        server.fake.trustResult = found(nas)
        settings.failSave = true

        vm.onTrustCertificate()

        val phase = vm.phase() as ServerPhase.Failed
        assertEquals(ErrorCopyTable.trustNotSaved, phase.copy)
        assertTrue(settings.saved.isEmpty())
    }

    // A pin that could not be dropped from disk would come back at the next load, so Test must not go on to connect
    // as if the certificate were being asked about afresh (#234 L4).
    @Test
    fun aTestStopsWhenTheStoredPinCannotBeCleared() {
        runBlocking { settings.save(ServerRecord(nas, spki)) }
        settings.failSave = true
        server.fake.discoverResult = found(nas)
        val vm = viewModel()

        vm.test("nas.example.test:8920")

        assertTrue(server.fake.discoverCalls.isEmpty())
        val phase = vm.phase() as ServerPhase.Failed
        assertTrue(phase.error is MediaServerError.Unknown)
    }

    /** Waits for [gate] before it clears a pin, to hold a clear open. */
    private class SlowClear(
        private val inner: InMemoryServerSettings,
        val gate: CompletableDeferred<Unit>,
    ) : ServerSettings by inner {
        override suspend fun clearPin(): Boolean {
            gate.await()
            return inner.clearPin()
        }
    }

    private fun slowClearViewModel(gate: CompletableDeferred<Unit>): ServerViewModel {
        val slow = SlowClear(settings, gate)
        return ServerViewModel(server, handOff, signedIn, slow, CertificateTrust(server, slow, sessions), sessions)
    }

    /** Refuses the first [failures] clears, as a write that fails. */
    private class FailingClear(
        private val inner: InMemoryServerSettings,
        private var failures: Int,
    ) : ServerSettings by inner {
        var calls = 0

        override suspend fun clearPin(): Boolean {
            calls++
            if (failures > 0) {
                failures--
                return false
            }
            return inner.clearPin()
        }
    }

    @Test
    fun aFailedClearKeepsTheFlagUpSoTheNextKeystrokeClearsAgain() {
        runBlocking { settings.save(ServerRecord(nas, spki)) }
        val failing = FailingClear(settings, failures = 1)
        val trust = CertificateTrust(server, failing, sessions)
        val vm = ServerViewModel(server, handOff, signedIn, failing, trust, sessions)

        vm.onInputChange("a")
        assertEquals(1, failing.calls)
        assertEquals(spki, settings.current?.pin)

        vm.onInputChange("ab")
        assertEquals(2, failing.calls)
        assertEquals(null, settings.current?.pin)

        vm.onInputChange("abc")
        assertEquals("cleared, so no further clear", 2, failing.calls)
    }

    @Test
    fun aPinClearStartedByTheViewModelFinishesEvenIfTheViewModelIsCleared() {
        runBlocking { settings.save(ServerRecord(nas, spki)) }
        val gate = CompletableDeferred<Unit>()
        val owner = ViewModelStore()
        val vm = owner.host(slowClearViewModel(gate), ServerViewModel::class.java)

        vm.onEditAddress()
        owner.clear()
        gate.complete(Unit)

        assertEquals(ServerRecord(nas, null), settings.current)
    }

    @Test
    fun theNextTestWaitsForAPinClearThatIsStillRunning() {
        runBlocking { settings.save(ServerRecord(nas, spki)) }
        server.fake.discoverResult = found(nas)
        val gate = CompletableDeferred<Unit>()
        val vm = slowClearViewModel(gate)

        vm.onEditAddress()
        vm.test("nas.example.test:8920")
        assertTrue(server.fake.discoverCalls.isEmpty())

        gate.complete(Unit)
        assertEquals(1, server.fake.discoverCalls.size)
        assertEquals(ServerRecord(nas, null), settings.current)
    }

    @Test
    fun cancellingATrustAttemptClearsThePinOnlyAfterTheAttemptHasEnded() {
        runBlocking { settings.save(ServerRecord(nas, spki)) }
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted(certificate = selfSigned))
        val held = CompletableDeferred<ServerResult<ResolvedServer>>()
        gatedTrust.gate = held
        val vm = viewModelWith(gatedTrust)
        vm.test("nas.example.test:8920")
        val clearsBefore = settings.clearPinCalls
        vm.onTrustCertificate()

        vm.onCancel()
        assertEquals("the clear must wait for the attempt", clearsBefore, settings.clearPinCalls)

        held.complete(found(nas))
        assertTrue(settings.clearPinCalls > clearsBefore)
        assertEquals(null, settings.current?.pin)
    }

    @Test
    fun trustingWithNothingToTrustDoesNothing() {
        val vm = viewModel()
        vm.onTrustCertificate()
        vm.onDontTrust()
        assertEquals(ServerPhase.Idle, vm.phase())
        assertTrue(server.fake.trustCalls.isEmpty())
    }

    @Test
    fun aNewTestClearsThePinBeforeItAsks() {
        runBlocking { settings.save(ServerRecord(nas, spki)) }
        server.fake.discoverResult = found(nas)
        val vm = viewModel()
        vm.test("nas.example.test:8920")
        assertEquals(ServerRecord(nas, null), settings.current)
        assertEquals(1, settings.pinsCleared)
    }

    @Test
    fun editingTheAddressClearsThePin() {
        runBlocking { settings.save(ServerRecord(nas, spki)) }
        val vm = viewModel()
        vm.onInputChange("nas.example.test:8921")
        assertEquals(ServerRecord(nas, null), settings.current)
    }

    @Test
    fun typingClearsThePinOnceNotOnEveryKeystroke() {
        runBlocking { settings.save(ServerRecord(nas, spki)) }
        val vm = viewModel()

        "nas.example.test:8921".forEachIndexed { i, _ -> vm.onInputChange("nas.example.test:8921".take(i + 1)) }

        assertEquals(ServerRecord(nas, null), settings.current)
        assertEquals("one clear for the whole edit", 1, settings.clearPinCalls)
    }

    @Test
    fun aPinStoredAfterTheLastClearIsClearedByTheNextEdit() {
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted(certificate = selfSigned))
        val vm = viewModel()
        vm.onInputChange("nas.example.test:8920")
        vm.onInputChange("nas.example.test:892")
        vm.test("nas.example.test:8920")
        vm.onTrustCertificate()
        runBlocking { settings.save(ServerRecord(nas, spki)) }
        val before = settings.clearPinCalls

        vm.onInputChange("nas.example.test:8921")

        assertEquals(before + 1, settings.clearPinCalls)
        assertEquals(ServerRecord(nas, null), settings.current)
    }

    @Test
    fun editAddressClearsThePin() {
        runBlocking { settings.save(ServerRecord(nas, spki)) }
        val vm = viewModel()
        vm.onEditAddress()
        assertEquals(ServerRecord(nas, null), settings.current)
    }

    @Test
    fun theTrustStateHoldsNoPinOrAnythingAboutTheCertificateInText() {
        server.fake.discoverResult = fails(MediaServerError.TlsUntrusted(certificate = selfSigned))
        val vm = viewModel()
        vm.test("nas.example.test:8920")
        val text = vm.state.value.toString()
        assertFalse(spki.hex in text)
        assertFalse("nas.example.test" in text.replace("input=nas.example.test:8920", ""))
    }

    @Test
    fun continuingHandsTheServerAndItsAcknowledgementToSignInInMemory() {
        val vm = viewModel()
        val ack = CleartextAcknowledgement(https)
        val connected = ConnectedServer(ResolvedServer(https, info), ack)

        vm.onContinue(connected)

        assertSame(connected, handOff.peek())
    }

    @Test
    fun continuingStoresTheServerAsTheConfiguredOneWithoutAPin() {
        val vm = viewModel()

        vm.onContinue(ConnectedServer(ResolvedServer(https, info), acknowledgement = null))

        assertEquals(ServerRecord(https), settings.current)
    }

    @Test
    fun continuingKeepsThePinTheTrustScreenStoredForTheSameAddress() =
        runBlocking {
            val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 7 })
            settings.save(ServerRecord(https, pin))
            val vm = viewModel()

            vm.onContinue(ConnectedServer(ResolvedServer(https, info), acknowledgement = null))

            assertEquals(ServerRecord(https, pin), settings.current)
            assertEquals(1, settings.saved.size)
        }

    @Test
    fun continuingToAnotherServerReplacesTheRecordAndItsLibrarySelection() =
        runBlocking {
            settings.save(ServerRecord(https))
            settings.saveLibraries(setOf(ItemId("movies")))
            val other = ServerAddress(Scheme.Https, "other.example.test", 443)
            val vm = viewModel()

            vm.onContinue(ConnectedServer(ResolvedServer(other, info), acknowledgement = null))

            assertEquals(ServerRecord(other), settings.current)
            assertNull(settings.currentLibraries)
        }

    @Test
    fun continuingStoresTheServerEvenIfTheViewModelIsClearedAtOnce() {
        runBlocking { settings.save(ServerRecord(https)) }
        val other = ServerAddress(Scheme.Https, "other.example.test", 443)
        val owner = ViewModelStore()
        val vm = owner.host(viewModel(), ServerViewModel::class.java)

        vm.onContinue(ConnectedServer(ResolvedServer(other, info), acknowledgement = null))
        owner.clear()

        assertEquals(ServerRecord(other), settings.current)
    }

    @Test
    fun continuingDropsASignInHeldFromAnEarlierPass() {
        val session = Session(UserId("u"), AccessToken("dummy-token-0001"), info, https, null, false)
        signedIn.put(SignInResult(session, persisted = true))
        val vm = viewModel()

        vm.onContinue(ConnectedServer(ResolvedServer(https, info), acknowledgement = null))

        assertNull(signedIn.peek())
    }

    @Test
    fun aStoredServerIsPrefilledSoChangeServerAndBackFromSignInNeverShowABlankForm() {
        runBlocking { settings.save(ServerRecord(https)) }

        val vm = viewModel()

        assertEquals(https.baseUrl, vm.state.value.input)
        assertEquals(ServerPhase.Idle, vm.state.value.phase)
    }

    @Test
    fun withNothingStoredTheFieldStaysBlank() {
        assertEquals("", viewModel().state.value.input)
    }

    @Test
    fun continuingToAnotherServerClearsTheOldServersSession() {
        val old = Session(UserId("u"), AccessToken("dummy-token-0001"), info, https, null, false)
        runBlocking {
            settings.save(ServerRecord(https))
            sessions.save(old)
        }
        val other = ServerAddress(Scheme.Https, "other.example.test", 443)

        viewModel().onContinue(ConnectedServer(ResolvedServer(other, info), acknowledgement = null))

        assertEquals(ServerRecord(other), settings.current)
        assertEquals(emptySet<String>(), store.snapshot().keys)
    }

    @Test
    fun continuingOnTheSameServerKeepsItsSession() {
        val old = Session(UserId("u"), AccessToken("dummy-token-0001"), info, https, null, false)
        runBlocking {
            settings.save(ServerRecord(https))
            sessions.save(old)
        }

        viewModel().onContinue(ConnectedServer(ResolvedServer(https, info), acknowledgement = null))

        assertEquals(setOf(SessionRepository.SESSION_KEY), store.snapshot().keys)
    }
}

/** One instance of each error type, for the tests that loop over all of them. */
internal object ErrorSamples {
    fun of(type: Class<*>): MediaServerError =
        if (type == MediaServerError.UnsupportedVersion::class.java) {
            MediaServerError.UnsupportedVersion(ServerVersion(11, 0, 0))
        } else {
            type.getDeclaredConstructor().newInstance() as MediaServerError
        }
}
