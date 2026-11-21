// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
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

    private fun approvedBy(session: Session) {
        server.quickConnectStates =
            listOf(
                QuickConnectState.CodeIssued("123456"),
                QuickConnectState.Waiting,
                QuickConnectState.SignedIn(session),
            )
    }

    private fun recover(): List<PinRecovery> = runBlocking { recovery.recover().toList() }

    private fun lockState() = runBlocking { lock.state.first() }

    private fun withAPinAndKidsModeOn() =
        runBlocking {
            sessions.save(here)
            lock.setPin("1234")
            lock.setCap(KidsCap.Family, null)
            lock.enable()
        }

    @Test
    fun anAdministratorsApprovalClearsThePinAndTurnsKidsModeOffKeepingTheCap() {
        withAPinAndKidsModeOn()
        approvedBy(admin)
        assertEquals(listOf(PinRecovery.Code("123456"), PinRecovery.Done), recover())
        assertEquals(KidsLockState(enabled = false, cap = KidsCap.Family, pinSet = false), lockState())
        assertNull(store.current.value.pin)
    }

    @Test
    fun aNewPinCanBeSetAfterwards() {
        withAPinAndKidsModeOn()
        approvedBy(admin)
        recover()
        assertEquals(PinResult.Ok, runBlocking { lock.setPin("4321") })
        assertTrue(runBlocking { lock.enable() })
    }

    @Test
    fun theRequestGoesToTheAddressTheTvIsSignedInAtAndTheApproversSessionIsRevoked() {
        withAPinAndKidsModeOn()
        approvedBy(admin)
        recover()
        assertEquals(listOf(address), server.quickConnectCalls.map { it.first })
        assertEquals(listOf(admin), server.revokeCalls)
    }

    @Test
    fun whenTheServerAnswersWithTheTvsOwnTokenItIsNotRevoked() {
        withAPinAndKidsModeOn()
        approvedBy(here.copy(isAdministrator = true))
        assertEquals(PinRecovery.Done, recover().last())
        assertTrue(server.revokeCalls.isEmpty())
    }

    @Test
    fun anApprovalByAUserWhoIsNotAnAdministratorChangesNothing() {
        withAPinAndKidsModeOn()
        approvedBy(admin.copy(isAdministrator = false))
        assertEquals(PinRecovery.NotAdministrator, recover().last())
        assertTrue(lockState().enabled)
        assertNotNull(store.current.value.pin)
        assertEquals(1, server.revokeCalls.size)
    }

    @Test
    fun aRequestThatEndsWithoutApprovalChangesNothing() {
        withAPinAndKidsModeOn()
        val cases =
            mapOf(
                QuickConnectState.Expired to PinRecovery.Expired,
                QuickConnectState.Denied to PinRecovery.Denied,
                QuickConnectState.Failed(MediaServerError.QuickConnectDisabled()) to PinRecovery.Disabled,
                QuickConnectState.Failed(MediaServerError.Timeout()) to PinRecovery.Unreachable,
            )
        for ((end, expected) in cases) {
            server.quickConnectStates = listOf(QuickConnectState.CodeIssued("123456"), end)
            assertEquals(listOf(PinRecovery.Code("123456"), expected), recover())
        }
        assertNotNull(store.current.value.pin)
        assertTrue(lockState().enabled)
        assertTrue(server.revokeCalls.isEmpty())
    }

    @Test
    fun withNoServerSignedInThereIsNoOneToAsk() {
        assertEquals(listOf<PinRecovery>(PinRecovery.NoServer), recover())
        assertTrue(server.quickConnectCalls.isEmpty())
    }

    @Test
    fun aLockFileThatCannotBeWrittenIsUnavailableAndChangesNothing() {
        withAPinAndKidsModeOn()
        approvedBy(admin)
        store.failWrites = true
        assertEquals(PinRecovery.Unavailable, recover().last())
        store.failWrites = false
        assertNotNull(store.current.value.pin)
        assertTrue(lockState().pinSet)
    }
}
