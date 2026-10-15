// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.feature.onboarding.R

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

@OptIn(ExperimentalCoroutinesApi::class)
class ServerViewModelTest {
    private val server = GatedServer()
    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private val handOff = ServerHandOff()

    private fun viewModel() = ServerViewModel(server, handOff)

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
    fun aTlsErrorShowsItsPlaceholderCopyAndNoTrustOption() {
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

    @Test
    fun continuingHandsTheServerAndItsAcknowledgementToSignInInMemory() {
        val vm = viewModel()
        val ack = CleartextAcknowledgement(https)
        val connected = ConnectedServer(ResolvedServer(https, info), ack)

        vm.onContinue(connected)

        assertSame(connected, handOff.peek())
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
