// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(SecretAccess::class)
class KidsPinRecoveryTest {
    private val server = FakeMediaServer()
    private val sessions = SessionRepository(InMemorySecretStore(), server)
    private val store = InMemoryKidsLockStore()
    private val lock =
        PinKidsLock(
            store,
            Clock.System,
            FakeMonotonicClock(),
            hasher = PinHasher(iterations = 10),
            work = EmptyCoroutineContext,
        )
    private val recovery = KidsPinRecovery(sessions, server, lock)

    private val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
    private val info = ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val here = Session(UserId("viewer"), AccessToken("tv-token-0123"), info, address, null, false)
    private val admin = Session(UserId("admin"), AccessToken("admin-token-0123"), info, address, null, true)

    private fun recover(name: String = "admin") = runBlocking { recovery.recover(name, "secret".toCharArray()) }

    private fun withAPinAndKidsModeOn(cap: KidsCap = KidsCap.Family) =
        runBlocking {
            sessions.save(here)
            lock.setPin("1234")
            lock.setCap(cap, null)
            lock.enable()
        }

    @Test
    fun anAdministratorsSignInClearsThePinAndTurnsKidsModeOffKeepingTheCap() {
        withAPinAndKidsModeOn()
        server.signInResult = ServerResult.Success(admin)
        assertEquals(PinRecovery.Done, recover())
        val state = runBlocking { lock.state.first() }
        assertEquals(KidsLockState(enabled = false, cap = KidsCap.Family, pinSet = false), state)
        assertNull(store.current.value.pin)
    }

    @Test
    fun aNewPinCanBeSetAfterwards() {
        withAPinAndKidsModeOn()
        server.signInResult = ServerResult.Success(admin)
        recover()
        assertEquals(PinResult.Ok, runBlocking { lock.setPin("4321") })
        assertTrue(runBlocking { lock.enable() })
    }

    @Test
    fun theSignInGoesToTheAddressTheTvIsSignedInAtAndItsSessionIsRevoked() {
        withAPinAndKidsModeOn()
        server.signInResult = ServerResult.Success(admin)
        recover("boss")
        assertEquals(listOf(address to "boss"), server.signInCalls)
        assertEquals(listOf(admin), server.revokeCalls)
    }

    @Test
    fun whenTheAdministratorIsTheViewerSignedInHereTheTvsOwnTokenIsNotRevoked() {
        withAPinAndKidsModeOn()
        server.signInResult = ServerResult.Success(here.copy(isAdministrator = true))
        assertEquals(PinRecovery.Done, recover())
        assertTrue(server.revokeCalls.isEmpty())
    }

    @Test
    fun aUserWhoIsNotAnAdministratorChangesNothing() {
        withAPinAndKidsModeOn()
        server.signInResult = ServerResult.Success(admin.copy(isAdministrator = false))
        assertEquals(PinRecovery.NotAdministrator, recover())
        assertTrue(runBlocking { lock.state.first() }.enabled)
        assertNotNull(store.current.value.pin)
        assertEquals(1, server.revokeCalls.size)
    }

    @Test
    fun theServersRefusalsChangeNothing() {
        withAPinAndKidsModeOn()
        val cases =
            mapOf(
                MediaServerError.HttpUnauthorized() to PinRecovery.WrongCredentials,
                MediaServerError.HttpForbidden() to PinRecovery.Refused,
                MediaServerError.Timeout() to PinRecovery.Unreachable,
            )
        for ((error, expected) in cases) {
            server.signInResult = ServerResult.Failure(error)
            assertEquals(expected, recover())
        }
        assertNotNull(store.current.value.pin)
        assertTrue(runBlocking { lock.state.first() }.enabled)
    }

    @Test
    fun withNoServerSignedInThereIsNoOneToAskAndThePasswordIsWiped() {
        val password = "secret".toCharArray()
        assertEquals(PinRecovery.NoServer, runBlocking { recovery.recover("admin", password) })
        assertContentEquals(CharArray(6), password)
        assertTrue(server.signInCalls.isEmpty())
    }

    @Test
    fun aLockFileThatCannotBeWrittenIsUnavailableAndChangesNothing() {
        withAPinAndKidsModeOn()
        server.signInResult = ServerResult.Success(admin)
        store.failWrites = true
        assertEquals(PinRecovery.Unavailable, recover())
        store.failWrites = false
        assertNotNull(store.current.value.pin)
        assertTrue(runBlocking { lock.state.first() }.pinSet)
    }
}
