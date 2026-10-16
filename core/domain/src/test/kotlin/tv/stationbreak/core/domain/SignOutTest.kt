// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignOutTest {
    private val store = InMemorySecretStore()
    private val server = FakeMediaServer()
    private val repository = SessionRepository(store, server)
    private val info = ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
    private val session =
        Session(UserId("u"), AccessToken("fake-token"), info, address, null, false)

    @Test
    fun signingOutLeavesTheServerSettingsAndItsPinAlone() =
        runBlocking {
            val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 4 })
            val settings = InMemoryServerSettings(ServerRecord(address, pin))
            repository.save(session)

            repository.signOut(session)

            // The address is kept, so its pin is: signing out is about the account, not the certificate.
            assertEquals(ServerRecord(address, pin), settings.current)
            assertEquals(0, settings.clearPinCalls)
            assertTrue(settings.saved.isEmpty())
        }

    @Test
    fun aCancelledCallerStillClearsAndStillFinishesTheRevoke() =
        runBlocking {
            repository.save(session)
            val gate = CompletableDeferred<Unit>()
            server.revokeGate = gate
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repository.signOut(session) }
            job.cancel()
            // Cancellation doesn't reach the revoke: the caller is still waiting on it, and the session is gone.
            assertTrue(!job.isCompleted)
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
            gate.complete(Unit)
            server.revokeFinished.await()
            job.join()
            assertEquals(1, server.revokeCalls.size)
        }

    @Test
    fun aCancelledCallerFinishesAfterTheCancelRequest() =
        runBlocking {
            repository.save(session)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { repository.signOut(session) }
            job.cancelAndJoin()
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
            assertEquals(listOf(session), server.revokeCalls)
        }

    // F0.6 (spec 06, task 2.12): the saved library follows the session. The real database is tested end to end in
    // data:library (SignOutClearsCacheTest); these pin the hook itself.

    private val account = LibraryAccount(ServerId("srv1"), UserId("u"))

    @Test
    fun signingOutForgetsTheSessionsSavedLibrary() =
        runBlocking {
            val cache = InMemoryLibraryRepository()
            val sessions = SessionRepository(store, server, cache)
            sessions.save(session)

            sessions.signOut(session)

            assertEquals(listOf(account), cache.forgotten)
        }

    @Test
    fun signingOutStillFinishesWhenTheCacheCannotBeCleared() =
        runBlocking {
            val cache = InMemoryLibraryRepository().apply { failForget = true }
            val sessions = SessionRepository(store, server, cache)
            sessions.save(session)

            val outcome = sessions.signOut(session)

            // The token is gone and revoked whatever the cache did.
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
            assertEquals(RevokeOutcome.Revoked, outcome)
        }

    @Test
    fun savingASessionMakesItsAccountTheActiveOne() =
        runBlocking {
            val cache = InMemoryLibraryRepository()
            val sessions = SessionRepository(store, server, cache)

            sessions.save(session)

            assertEquals(listOf(account), cache.activated)
        }

    @Test
    fun restoringASessionMakesItsAccountTheActiveOne() =
        runBlocking {
            val cache = InMemoryLibraryRepository()
            val sessions = SessionRepository(store, server, cache)
            sessions.save(session)
            cache.activated.clear()
            server.resumeResult = ServerResult.Success(session)

            val outcome = sessions.restore(address)

            assertTrue(outcome is RestoreOutcome.Restored)
            assertEquals(listOf(account), cache.activated)
        }

    @Test
    fun aSessionThatIsRejectedOnRestoreActivatesNothing() =
        runBlocking {
            val cache = InMemoryLibraryRepository()
            val sessions = SessionRepository(store, server, cache)
            sessions.save(session)
            cache.activated.clear()
            server.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())

            sessions.restore(address)

            assertEquals(emptyList(), cache.activated)
        }

    // Angua LOW 2: rows must not outlive a rejected session.

    @Test
    fun aSessionTheServerRefusesOnRestoreForgetsItsAccount() =
        runBlocking {
            val cache = InMemoryLibraryRepository()
            val sessions = SessionRepository(store, server, cache)
            sessions.save(session)
            cache.activated.clear()
            server.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())

            sessions.restore(address)

            assertEquals(listOf(account), cache.forgotten)
        }

    @Test
    fun aServerMismatchAnsweredByTheServerForgetsItsAccount() =
        runBlocking {
            val cache = InMemoryLibraryRepository()
            val sessions = SessionRepository(store, server, cache)
            sessions.save(session)
            server.resumeResult = ServerResult.Failure(MediaServerError.ServerMismatch())

            sessions.restore(address)

            assertEquals(listOf(account), cache.forgotten)
        }

    @Test
    fun aStoredSessionForAnotherConfiguredServerForgetsItsAccount() =
        runBlocking {
            val cache = InMemoryLibraryRepository()
            val sessions = SessionRepository(store, server, cache)
            sessions.save(session)

            sessions.restore(ServerAddress(Scheme.Https, "other.example", 443))

            assertEquals(listOf(account), cache.forgotten)
        }

    @Test
    fun anUnreadableStoredSessionForgetsNothingBecauseNoAccountIsKnown() =
        runBlocking {
            val cache = InMemoryLibraryRepository()
            store.save(SessionRepository.SESSION_KEY, "not a session")

            val outcome = SessionRepository(store, server, cache).restore(address)

            assertEquals(RestoreOutcome.SignedOut, outcome)
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
            assertEquals(emptyList(), cache.forgotten)
        }

    @Test
    fun aRefusedSessionReportedByACallerForgetsItsAccount() =
        runBlocking {
            val cache = InMemoryLibraryRepository()
            val sessions = SessionRepository(store, server, cache)

            assertEquals(false, sessions.onServerError(MediaServerError.Timeout(), session))
            assertEquals(emptyList(), cache.forgotten)
            assertEquals(true, sessions.onServerError(MediaServerError.SessionExpired(), session))
            assertEquals(listOf(account), cache.forgotten)
        }

    @Test
    fun aSessionThatLivesInMemoryOnlyStillActivatesItsAccount() =
        runBlocking {
            val cache = InMemoryLibraryRepository()
            store.failSave = true

            // The user is signed in for this run even though the token wasn't stored, so the other accounts go now.
            SessionRepository(store, server, cache).save(session)

            assertEquals(listOf(account), cache.activated)
        }
}
