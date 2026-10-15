// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.core.model.StoredSession
import tv.stationbreak.core.model.UserId

/** Every route the app can open on (spec 05, Unit 4), against fakes of the ports. */
class LaunchRouterTest {
    private val events = mutableListOf<String>()

    private val fake = FakeMediaServer()
    private val server = RecordingServer(fake, events)
    private val store = InMemorySecretStore()
    private val sessions = SessionRepository(store, server)

    private val info = ServerInfo("Home Server", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val https = ServerAddress(Scheme.Https, "jellyfin.example.test", 443)
    private val http = ServerAddress(Scheme.Http, "jellyfin.example.test", 8096)
    private val session = Session(UserId("user1"), AccessToken("dummy-token-0001"), info, https, null, false)

    private fun router(settings: ServerSettings) = LaunchRouter(RecordingSettings(settings, events), sessions, server)

    private fun route(settings: ServerSettings): LaunchRoute = runBlocking { router(settings).route() }

    private fun storeSession(session: Session = this.session) = runBlocking { sessions.save(session) }

    // Restored

    @Test
    fun aRestoredSessionWithALibraryChoiceGoesToSetupComplete() {
        storeSession()
        fake.resumeResult = ServerResult.Success(session)

        val route = route(InMemoryServerSettings(ServerRecord(https), setOf(ItemId("movies"))))

        assertEquals(LaunchRoute.SetupComplete, route)
    }

    @Test
    fun aRestoredSessionWithNoChoiceYetGoesToThePicker() {
        storeSession()
        fake.resumeResult = ServerResult.Success(session)

        val route = route(InMemoryServerSettings(ServerRecord(https)))

        assertSame(session, (route as LaunchRoute.Libraries).session)
    }

    // Signed out and rejected

    @Test
    fun noStoredSessionGoesToSignInForTheStoredServerAfterACredentialFreeConnect() {
        fake.connectResult = ServerResult.Success(ResolvedServer(https, info))

        val route = route(InMemoryServerSettings(ServerRecord(https)))

        val signIn = route as LaunchRoute.SignIn
        assertEquals(ResolvedServer(https, info), signIn.server.server)
        assertNull(signIn.server.acknowledgement)
        assertNull(signIn.server.signedOutBecause)
        assertEquals(listOf(https to null), fake.connectCalls)
        assertTrue(fake.resumeCalls.isEmpty())
    }

    @Test
    fun aSessionTheServerRefusedGoesToSignInWithOneLineSayingWhy() {
        storeSession()
        fake.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())
        fake.connectResult = ServerResult.Success(ResolvedServer(https, info))

        val route = route(InMemoryServerSettings(ServerRecord(https)))

        val signIn = route as LaunchRoute.SignIn
        assertTrue(signIn.server.signedOutBecause is MediaServerError.SessionExpired)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun aSessionFromAnotherServerGoesToSignInAndSaysTheServerChanged() {
        storeSession(session.copy(address = ServerAddress(Scheme.Https, "other.example.test", 443)))
        fake.connectResult = ServerResult.Success(ResolvedServer(https, info))

        val route = route(InMemoryServerSettings(ServerRecord(https)))

        assertTrue((route as LaunchRoute.SignIn).server.signedOutBecause is MediaServerError.ServerMismatch)
        assertTrue(fake.resumeCalls.isEmpty())
    }

    @Test
    fun signInForAServerThatCannotBeReachedIsTheUnavailableRouteNotAnEmptyForm() {
        fake.connectResult = ServerResult.Failure(MediaServerError.Timeout())

        val route = route(InMemoryServerSettings(ServerRecord(https)))

        val unavailable = route as LaunchRoute.Unavailable
        assertTrue(unavailable.error is MediaServerError.Timeout)
        assertEquals(https, unavailable.address)
    }

    @Test
    fun aStoredHttpServerThatNeedsConfirmationGoesToTheServerScreenWithoutAnyRequest() {
        val route = route(InMemoryServerSettings(ServerRecord(ServerAddress(Scheme.Http, "nas.example.test", 8096))))

        assertEquals(LaunchRoute.NoServer, route)
        assertTrue(fake.connectCalls.isEmpty())
    }

    @Test
    fun aStoredPrivateHttpServerNeedsNoConfirmationSoItGoesToSignIn() {
        val lan = ServerAddress(Scheme.Http, "192.168.1.20", 8096)
        fake.connectResult = ServerResult.Success(ResolvedServer(lan, info))

        val route = route(InMemoryServerSettings(ServerRecord(lan)))

        assertTrue(route is LaunchRoute.SignIn)
        assertEquals(listOf<Pair<ServerAddress, CleartextAcknowledgement?>>(lan to null), fake.connectCalls)
    }

    // Unavailable

    @Test
    fun aServerThatCannotBeAskedIsUnavailableAndTheStoredSessionIsKept() {
        storeSession()
        fake.resumeResult = ServerResult.Failure(MediaServerError.NetworkUnreachable())

        val route = route(InMemoryServerSettings(ServerRecord(https)))

        val unavailable = route as LaunchRoute.Unavailable
        assertTrue(unavailable.error is MediaServerError.NetworkUnreachable)
        assertEquals(setOf(SessionRepository.SESSION_KEY), store.snapshot().keys)
        assertTrue(fake.connectCalls.isEmpty())
    }

    @Test
    fun aChangedCertificateIsReportedAsUnavailableWithTheStoredAddressForTheTrustQuestion() {
        storeSession()
        fake.resumeResult = ServerResult.Failure(MediaServerError.TlsCertificateChanged())

        val route = route(InMemoryServerSettings(ServerRecord(https, SpkiPin(ByteArray(SpkiPin.SIZE) { 5 }))))

        val unavailable = route as LaunchRoute.Unavailable
        assertTrue(unavailable.error is MediaServerError.TlsCertificateChanged)
        assertEquals(https, unavailable.address)
    }

    // No server

    @Test
    fun nothingStoredGoesToTheServerScreenWithoutAnyRequest() {
        val route = route(InMemoryServerSettings())

        assertEquals(LaunchRoute.NoServer, route)
        assertTrue(fake.connectCalls.isEmpty())
        assertTrue(fake.resumeCalls.isEmpty())
    }

    // Order

    @Test
    fun theSettingsAreReadBeforeAnyRequestSoAStoredPinIsInForce() {
        storeSession()
        fake.resumeResult = ServerResult.Success(session)

        route(InMemoryServerSettings(ServerRecord(https)))

        assertEquals("load", events.first())
        assertEquals(events.indexOf("load") < events.indexOf("resume"), true)
    }

    @Test
    fun theSettingsAreReadBeforeASignedOutConnectToo() {
        fake.connectResult = ServerResult.Success(ResolvedServer(https, info))

        route(InMemoryServerSettings(ServerRecord(https)))

        assertEquals("load", events.first())
        assertTrue(events.indexOf("load") < events.indexOf("connect"))
    }
}

/** Notes the order of calls that matter, then passes them on. */
private class RecordingSettings(
    private val inner: ServerSettings,
    private val events: MutableList<String>,
) : ServerSettings by inner {
    override suspend fun load(): ServerRecord? {
        events += "load"
        return inner.load()
    }
}

private class RecordingServer(
    private val fake: FakeMediaServer,
    private val events: MutableList<String>,
) : MediaServer by fake {
    override suspend fun resume(stored: StoredSession): ServerResult<Session> {
        events += "resume"
        return fake.resume(stored)
    }

    override suspend fun connect(
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement?,
    ): ServerResult<ResolvedServer> {
        events += "connect"
        return fake.connect(address, acknowledgement)
    }
}
