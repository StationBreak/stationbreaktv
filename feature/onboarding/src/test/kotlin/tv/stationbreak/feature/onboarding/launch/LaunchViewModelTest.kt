// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CertificateEvidence
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
import tv.stationbreak.feature.onboarding.server.ServerHandOff
import tv.stationbreak.feature.onboarding.signin.SignedInHandOff
import tv.stationbreak.feature.onboarding.trust.CertificateTrust

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchViewModelTest {
    private val fake = FakeMediaServer()
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, fake)
    private val serverHandOff = ServerHandOff()
    private val signedIn = SignedInHandOff()

    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)
    private val spki = SpkiPin(ByteArray(SpkiPin.SIZE) { 9 })
    private val selfSigned =
        CertificateEvidence(spki = spki, selfSigned = true, notBeforeMillis = 0L, notAfterMillis = 1_000L)

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun viewModel(
        settings: InMemoryServerSettings,
        server: MediaServer = fake,
    ) = LaunchViewModel(
        LaunchRouter(settings, SessionRepository(store, server), server),
        serverHandOff,
        signedIn,
        CertificateTrust(server, settings, SessionRepository(store, server)),
    )

    private fun CoroutineScope.collect(
        vm: LaunchViewModel,
        into: MutableList<LaunchTarget>,
    ): Job = launch(UnconfinedTestDispatcher()) { vm.targets.collect { into += it } }

    private fun targetsOf(vm: LaunchViewModel): List<LaunchTarget> {
        val seen = mutableListOf<LaunchTarget>()
        runBlocking {
            val job = launch(UnconfinedTestDispatcher()) { vm.targets.collect { seen += it } }
            job.cancel()
        }
        return seen
    }

    @Test
    fun startsOnTheProgressScreenAndStaysThereWhileTheRestoreRuns() {
        val gate = CompletableDeferred<ServerResult<Session>>()
        runBlocking { sessions.save(session) }

        val vm = viewModel(InMemoryServerSettings(ServerRecord(https)), SlowResume(fake, gate))

        assertEquals(LaunchPhase.Routing, vm.state.value)
        gate.complete(ServerResult.Success(session))
    }

    @Test
    fun noServerGoesToTheServerScreen() =
        runTest {
            val targets = mutableListOf<LaunchTarget>()
            val vm = viewModel(InMemoryServerSettings())
            val job = backgroundScope.collect(vm, targets)

            assertEquals(listOf(LaunchTarget.NoServer), targets)
            job.cancel()
        }

    @Test
    fun aRestoredSessionWithAChoiceGoesToSetupComplete() =
        runTest {
            runBlocking { sessions.save(session) }
            fake.resumeResult = ServerResult.Success(session)
            val targets = mutableListOf<LaunchTarget>()
            val vm = viewModel(InMemoryServerSettings(ServerRecord(https), setOf(ItemId("movies"))))
            val job = backgroundScope.collect(vm, targets)

            assertEquals(listOf(LaunchTarget.SetupComplete), targets)
            job.cancel()
        }

    @Test
    fun aRestoredSessionWithNoChoiceGoesToThePickerWithTheSessionHeldInMemory() =
        runTest {
            runBlocking { sessions.save(session) }
            fake.resumeResult = ServerResult.Success(session)
            val targets = mutableListOf<LaunchTarget>()
            val vm = viewModel(InMemoryServerSettings(ServerRecord(https)))
            val job = backgroundScope.collect(vm, targets)

            assertEquals(listOf(LaunchTarget.Libraries), targets)
            assertSame(session, signedIn.peek()?.session)
            assertTrue(signedIn.peek()?.persisted == true)
            assertNull(signedIn.peek()?.username)
            job.cancel()
        }

    @Test
    fun signedOutGoesToSignInWithTheStoredServerHandedOver() =
        runTest {
            fake.connectResult = ServerResult.Success(ResolvedServer(https, info))
            val targets = mutableListOf<LaunchTarget>()
            val vm = viewModel(InMemoryServerSettings(ServerRecord(https)))
            val job = backgroundScope.collect(vm, targets)

            assertEquals(listOf(LaunchTarget.SignIn), targets)
            assertEquals(ResolvedServer(https, info), serverHandOff.peek()?.server)
            assertNull(serverHandOff.peek()?.signedOutBecause)
            job.cancel()
        }

    @Test
    fun rejectedGoesToSignInCarryingWhy() =
        runTest {
            runBlocking { sessions.save(session) }
            fake.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())
            fake.connectResult = ServerResult.Success(ResolvedServer(https, info))
            val targets = mutableListOf<LaunchTarget>()
            val vm = viewModel(InMemoryServerSettings(ServerRecord(https)))
            val job = backgroundScope.collect(vm, targets)

            assertEquals(listOf(LaunchTarget.SignIn), targets)
            assertTrue(serverHandOff.peek()?.signedOutBecause is MediaServerError.SessionExpired)
            job.cancel()
        }

    @Test
    fun unavailableShowsTheErrorWithRetryAndMovesNowhere() =
        runTest {
            runBlocking { sessions.save(session) }
            fake.resumeResult = ServerResult.Failure(MediaServerError.Timeout())
            val targets = mutableListOf<LaunchTarget>()
            val vm = viewModel(InMemoryServerSettings(ServerRecord(https)))
            val job = backgroundScope.collect(vm, targets)

            val phase = vm.state.value as LaunchPhase.Unavailable
            assertEquals(R.string.error_timeout_message, phase.copy.message)
            assertTrue(targets.isEmpty())
            assertEquals(setOf(SessionRepository.SESSION_KEY), store.snapshot().keys)
            job.cancel()
        }

    @Test
    fun retryChecksAgainAndMovesOnWhenTheServerIsBack() =
        runTest {
            runBlocking { sessions.save(session) }
            fake.resumeResult = ServerResult.Failure(MediaServerError.Timeout())
            val targets = mutableListOf<LaunchTarget>()
            val vm = viewModel(InMemoryServerSettings(ServerRecord(https), setOf(ItemId("movies"))))
            val job = backgroundScope.collect(vm, targets)
            assertTrue(vm.state.value is LaunchPhase.Unavailable)

            fake.resumeResult = ServerResult.Success(session)
            vm.onRetry()

            assertEquals(listOf(LaunchTarget.SetupComplete), targets)
            assertEquals(2, fake.resumeCalls.size)
            job.cancel()
        }

    // Certificate changed

    private fun changedCertificate() {
        runBlocking { sessions.save(session) }
        fake.resumeResult = ServerResult.Failure(MediaServerError.TlsCertificateChanged(certificate = selfSigned))
    }

    @Test
    fun aChangedSelfSignedCertificateRoutesToTheTrustScreenForTheStoredAddress() {
        changedCertificate()

        val vm = viewModel(InMemoryServerSettings(ServerRecord(https, spki)))

        val phase = vm.state.value as LaunchPhase.Trust
        assertEquals(https.baseUrl, phase.input)
        assertEquals(selfSigned, phase.prompt.certificate)
        assertEquals(tv.stationbreak.feature.onboarding.trust.TrustReason.Changed, phase.prompt.reason)
        assertFalse(spki.hex in phase.toString())
        assertFalse("jellyfin.example.test" in phase.toString())
    }

    @Test
    fun aChangedCertificateThatIsNotSelfSignedIsADeadEndWithRetryNotATrustOption() {
        runBlocking { sessions.save(session) }
        val chained = selfSigned.copy(selfSigned = false)
        fake.resumeResult = ServerResult.Failure(MediaServerError.TlsCertificateChanged(certificate = chained))

        val vm = viewModel(InMemoryServerSettings(ServerRecord(https)))

        assertTrue(vm.state.value is LaunchPhase.Unavailable)
    }

    @Test
    fun trustingTheNewCertificateDropsTheSessionPinsAndContinuesAtSignIn() =
        runTest {
            changedCertificate()
            fake.trustResult = ServerResult.Success(ResolvedServer(https, info))
            val settings = InMemoryServerSettings(ServerRecord(https, SpkiPin(ByteArray(SpkiPin.SIZE) { 1 })))
            val targets = mutableListOf<LaunchTarget>()
            val vm = viewModel(settings)
            val job = backgroundScope.collect(vm, targets)

            vm.onTrust()

            assertEquals(listOf(https.baseUrl to spki), fake.trustCalls)
            assertTrue(store.snapshot().isEmpty())
            assertEquals(ServerRecord(https, spki), settings.current)
            assertEquals(listOf(LaunchTarget.SignIn), targets)
            assertEquals(ResolvedServer(https, info), serverHandOff.peek()?.server)
            assertNull(serverHandOff.peek()?.signedOutBecause)
            job.cancel()
        }

    @Test
    fun trustingKeepsTheLibraryChoiceOfTheSameServer() =
        runTest {
            changedCertificate()
            fake.trustResult = ServerResult.Success(ResolvedServer(https, info))
            val settings = InMemoryServerSettings(ServerRecord(https, spki), setOf(ItemId("movies")))
            val vm = viewModel(settings)

            vm.onTrust()

            assertEquals(setOf(ItemId("movies")), settings.currentLibraries)
        }

    @Test
    fun aRefusedConfirmationShowsWhyWithRetry() {
        changedCertificate()
        fake.trustResult = ServerResult.Failure(MediaServerError.Timeout())
        val vm = viewModel(InMemoryServerSettings(ServerRecord(https, spki)))

        vm.onTrust()

        assertTrue(vm.state.value is LaunchPhase.Unavailable)
        assertTrue(targetsOf(vm).isEmpty())
    }

    @Test
    fun aConfirmationThatCouldNotBeStoredSaysSoWithRetry() {
        changedCertificate()
        fake.trustResult = ServerResult.Success(ResolvedServer(https, info))
        val settings = InMemoryServerSettings(ServerRecord(https, spki))
        val vm = viewModel(settings)
        settings.failSave = true

        vm.onTrust()

        val phase = vm.state.value as LaunchPhase.Unavailable
        assertEquals(R.string.error_trust_not_saved_message, phase.copy.message)
    }

    @Test
    fun dontTrustAndBackPinNothingAndLeaveTheErrorWithRetry() {
        changedCertificate()
        val settings = InMemoryServerSettings(ServerRecord(https, spki))
        val vm = viewModel(settings)

        vm.onDontTrust()

        val phase = vm.state.value as LaunchPhase.Unavailable
        assertEquals(R.string.error_tls_changed_message, phase.copy.message)
        assertTrue(fake.trustCalls.isEmpty())
        assertEquals(setOf(SessionRepository.SESSION_KEY), store.snapshot().keys)
    }

    // #234 L1: a second route cancels the first, so a late answer from the first can't move the app on.
    @Test
    fun aNewRouteCancelsTheOneStillRunning() =
        runTest {
            runBlocking { sessions.save(session) }
            val gate = CompletableDeferred<ServerResult<Session>>()
            val server = FirstResumeGated(fake, gate)
            val targets = mutableListOf<LaunchTarget>()
            val vm = viewModel(InMemoryServerSettings(ServerRecord(https), setOf(ItemId("movies"))), server)
            val job = backgroundScope.collect(vm, targets)
            fake.resumeResult = ServerResult.Failure(MediaServerError.Timeout())

            vm.onRetry()
            gate.complete(ServerResult.Success(session))

            assertTrue(vm.state.value is LaunchPhase.Unavailable)
            assertTrue("the first route's late answer moved the app on: $targets", targets.isEmpty())
            job.cancel()
        }

    // #234 L2
    @Test
    fun aConfirmationThatThrowsLeavesTrustingWithRetry() {
        changedCertificate()
        val vm = viewModel(InMemoryServerSettings(ServerRecord(https, spki)), ThrowingTrust(fake))

        vm.onTrust()

        assertTrue(vm.state.value is LaunchPhase.Unavailable)
        assertTrue(targetsOf(vm).isEmpty())
    }

    @Test
    fun aTrustThatIsInFlightWhenTheViewModelIsClearedStillEndsWithThePinStored() {
        changedCertificate()
        val gate = CompletableDeferred<ServerResult<ResolvedServer>>()
        val settings = InMemoryServerSettings(ServerRecord(https, SpkiPin(ByteArray(SpkiPin.SIZE) { 1 })))
        val owner = ViewModelStore()
        val vm = owner.host(viewModel(settings, GatedTrust(fake, gate)), LaunchViewModel::class.java)

        vm.onTrust()
        owner.clear()
        gate.complete(ServerResult.Success(ResolvedServer(https, info)))

        assertEquals(ServerRecord(https, spki), settings.current)
    }

    @Test
    fun onTrustOrDontTrustWithNothingAskedDoesNothing() {
        val vm = viewModel(InMemoryServerSettings())

        vm.onTrust()
        vm.onDontTrust()

        assertTrue(fake.trustCalls.isEmpty())
    }
}

/** A server whose resume waits for [gate], to see the screen while the restore is still running. */
private class SlowResume(
    private val fake: FakeMediaServer,
    private val gate: CompletableDeferred<ServerResult<Session>>,
) : MediaServer by fake {
    override suspend fun resume(stored: tv.stationbreak.core.model.StoredSession): ServerResult<Session> = gate.await()
}

/** Waits for [gate] on the first resume only; later ones answer from [fake]. Both ignore cancellation. */
private class FirstResumeGated(
    private val fake: FakeMediaServer,
    private val gate: CompletableDeferred<ServerResult<Session>>,
) : MediaServer by fake {
    private var first = true

    override suspend fun resume(stored: tv.stationbreak.core.model.StoredSession): ServerResult<Session> {
        if (!first) return fake.resume(stored)
        first = false
        return gate.await()
    }
}

/** A server whose `trust` breaks, as a bug or a failing store would. */
private class ThrowingTrust(
    private val fake: FakeMediaServer,
) : MediaServer by fake {
    override suspend fun trust(
        input: String,
        pin: SpkiPin,
    ): ServerResult<ResolvedServer> = error("boom")
}

/** A server whose `trust` waits for [gate] and ignores cancellation, to clear the view model in the middle of it. */
private class GatedTrust(
    private val fake: FakeMediaServer,
    private val gate: CompletableDeferred<ServerResult<ResolvedServer>>,
) : MediaServer by fake {
    override suspend fun trust(
        input: String,
        pin: SpkiPin,
    ): ServerResult<ResolvedServer> {
        fake.trustCalls += input to pin
        return withContext(NonCancellable) { gate.await() }
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
