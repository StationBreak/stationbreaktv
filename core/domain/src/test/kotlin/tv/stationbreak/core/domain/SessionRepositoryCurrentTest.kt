// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.StoredSession
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [SessionRepository.current] is the one place the signed-in session lives in memory (PR #332, A1). */
@OptIn(SecretAccess::class)
class SessionRepositoryCurrentTest {
    private val store = InMemorySecretStore()
    private val server = FakeMediaServer()
    private val repository = SessionRepository(store, server)

    private val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
    private val info = ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val session = Session(UserId("user1"), AccessToken("fake-token-0123"), info, address, null, true)

    @Test
    fun startsEmpty() {
        assertNull(repository.current.value)
    }

    @Test
    fun saveSetsItEvenWhenTheTokenDidNotReachStorage() =
        runBlocking {
            store.failSave = true
            assertEquals(PersistOutcome.NotSaved, repository.save(session))
            assertEquals(session, repository.current.value)
            store.failSave = false
            repository.save(session)
            assertEquals(session, repository.current.value)
        }

    @Test
    fun aRestoredSessionSetsIt() =
        runBlocking {
            store.save(SessionRepository.SESSION_KEY, storedBlob())
            server.resumeResult = ServerResult.Success(session)
            assertEquals(RestoreOutcome.Restored(session), repository.restore(address))
            assertEquals(session, repository.current.value)
        }

    @Test
    fun aRejectedOrEmptyRestoreLeavesNothing() =
        runBlocking {
            assertEquals(RestoreOutcome.SignedOut, repository.restore(address))
            assertNull(repository.current.value)
            repository.save(session)
            server.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())
            repository.restore(address)
            assertNull(repository.current.value)
        }

    @Test
    fun anUnavailableServerKeepsWhatWasHeld() =
        runBlocking {
            repository.save(session)
            server.resumeResult = ServerResult.Failure(MediaServerError.Timeout())
            assertEquals(RestoreOutcome.Unavailable(MediaServerError.Timeout()), repository.restore(address))
            assertEquals(session, repository.current.value)
        }

    @Test
    fun verifySetsItOnlyWhenRestored() =
        runBlocking {
            assertEquals(RestoreOutcome.SignedOut, repository.verify(address))
            assertNull(repository.current.value)
            store.save(SessionRepository.SESSION_KEY, storedBlob())
            server.resumeResult = ServerResult.Success(session)
            assertEquals(RestoreOutcome.Restored(session), repository.verify(address))
            assertEquals(session, repository.current.value)
        }

    @Test
    fun verifyThatIsRejectedDoesNotSetIt() =
        runBlocking {
            store.save(SessionRepository.SESSION_KEY, storedBlob())
            server.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())
            repository.verify(address)
            assertNull(repository.current.value)
        }

    @Test
    fun clearNullsIt() =
        runBlocking {
            repository.save(session)
            repository.clear()
            assertNull(repository.current.value)
        }

    @Test
    fun signOutNullsItWhetherOrNotTheRevokeSucceeded() =
        runBlocking {
            repository.save(session)
            assertEquals(RevokeOutcome.Revoked, repository.signOut(session))
            assertNull(repository.current.value)
            repository.save(session)
            server.revokeResult = ServerResult.Failure(MediaServerError.Timeout())
            assertEquals(RevokeOutcome.NotRevoked, repository.signOut(session))
            assertNull(repository.current.value)
        }

    @Test
    fun aFatalServerErrorNullsItAndATransientOneDoesNot() =
        runBlocking {
            repository.save(session)
            repository.onServerError(MediaServerError.Timeout())
            assertEquals(session, repository.current.value)
            repository.onServerError(MediaServerError.SessionExpired())
            assertNull(repository.current.value)
            repository.save(session)
            repository.onServerError(MediaServerError.ServerMismatch())
            assertNull(repository.current.value)
        }

    // A server whose resume waits for [answer], so a test can sign out while a verify is in flight (#333).
    private class HeldResume(
        private val inner: FakeMediaServer,
    ) : MediaServer by inner {
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<ServerResult<Session>>()

        override suspend fun resume(stored: StoredSession): ServerResult<Session> {
            asked.complete(Unit)
            return answer.await()
        }
    }

    @Test
    fun aVerifyInFlightWhenTheUserSignsOutDoesNotBringTheSessionBack() =
        runBlocking {
            val held = HeldResume(server)
            val repo = SessionRepository(store, held)
            repo.save(session)
            val verifying = async { repo.verify(address) }
            held.asked.await()

            repo.signOut(session)
            held.answer.complete(ServerResult.Success(session))

            assertEquals(RestoreOutcome.Superseded, verifying.await())
            assertNull(repo.current.value)
        }

    @Test
    fun aVerifyInFlightWhenAnotherUserSignsInDoesNotReplaceThem() =
        runBlocking {
            val held = HeldResume(server)
            val repo = SessionRepository(store, held)
            repo.save(session)
            val verifying = async { repo.verify(address) }
            held.asked.await()

            val other = session.copy(userId = UserId("user2"))
            repo.save(other)
            held.answer.complete(ServerResult.Success(session))

            assertEquals(RestoreOutcome.Superseded, verifying.await())
            assertEquals(other, repo.current.value)
        }

    // A store whose clear waits for [release], so a check can start while a sign-out is still clearing it.
    private class HeldClear(
        private val inner: InMemorySecretStore,
    ) : SecretStore by inner {
        val clearing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun clear(key: String): SecretStoreResult<Unit> {
            clearing.complete(Unit)
            release.await()
            return inner.clear(key)
        }
    }

    @Test
    fun aVerifyStartedWhileTheSignOutIsStillClearingTheStoreFindsNothing() =
        runBlocking {
            val slowStore = HeldClear(store)
            val repo = SessionRepository(slowStore, server)
            repo.save(session)
            server.resumeResult = ServerResult.Success(session)
            val signingOut = async { repo.signOut(session) }
            slowStore.clearing.await()

            val verifying = async { repo.verify(address) }
            slowStore.release.complete(Unit)
            signingOut.await()

            assertEquals(RestoreOutcome.SignedOut, verifying.await())
            assertNull(repo.current.value)
            assertTrue(store.snapshot().isEmpty())
        }

    @Test
    fun aRestoreInFlightWhenTheUserSignsOutPublishesAndWritesNothing() =
        runBlocking {
            val held = HeldResume(server)
            val repo = SessionRepository(store, held)
            repo.save(session)
            val restoring = async { repo.restore(address) }
            held.asked.await()

            repo.signOut(session)
            // An http to https upgrade would be written back: it must not be, after the sign-out.
            held.answer.complete(ServerResult.Success(session.copy(address = address.copy(port = 8920))))

            assertEquals(RestoreOutcome.Superseded, restoring.await())
            assertNull(repo.current.value)
            assertTrue(store.snapshot().isEmpty())
        }

    @Test
    fun aRefusalOfASessionReplacedMeanwhileDoesNotSignOutTheNewOne() =
        runBlocking {
            val held = HeldResume(server)
            val repo = SessionRepository(store, held)
            repo.save(session)
            val restoring = async { repo.restore(address) }
            held.asked.await()

            val other = session.copy(userId = UserId("user2"))
            repo.save(other)
            held.answer.complete(ServerResult.Failure(MediaServerError.SessionExpired()))

            assertEquals(RestoreOutcome.Superseded, restoring.await())
            assertEquals(other, repo.current.value)
            assertTrue(store.snapshot().isNotEmpty())
        }

    @Test
    fun aVerifyWithNothingInBetweenStillPublishes() =
        runBlocking {
            val held = HeldResume(server)
            val repo = SessionRepository(store, held)
            repo.save(session)
            held.answer.complete(ServerResult.Success(session))

            assertEquals(RestoreOutcome.Restored(session), repo.verify(address))
            assertEquals(session, repo.current.value)
        }

    private suspend fun storedBlob(): String {
        repository.save(session)
        val blob = store.snapshot().getValue(SessionRepository.SESSION_KEY)
        repository.clear()
        return blob
    }
}
