// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(SecretAccess::class)
class SessionRepositoryTest {
    private val store = InMemorySecretStore()
    private val server = FakeMediaServer()
    private val repository = SessionRepository(store, server)

    private val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
    private val info = ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0))
    private val session = Session(UserId("user1"), AccessToken("fake-token-0123"), info, address, null, true)

    private fun blob(): String = (store.snapshot()[SessionRepository.SESSION_KEY])!!

    @Test
    fun persistsOneVersionedJsonStringUnderOneKeyWithoutTheDeviceId() =
        runBlocking {
            assertEquals(PersistOutcome.Saved, repository.save(session))
            assertEquals(setOf(SessionRepository.SESSION_KEY), store.snapshot().keys)
            val json = Json.parseToJsonElement(blob()).jsonObject
            assertEquals(SessionRepository.FORMAT_VERSION, json.getValue("version").jsonPrimitive.int)
            assertFalse(json.keys.any { it.contains("device", ignoreCase = true) })
            assertTrue("fake-token-0123" in blob())
        }

    @Test
    fun restoresWhatItSavedAfterTheServerConfirmsIt() =
        runBlocking {
            val acknowledgement = CleartextAcknowledgement(address)
            repository.save(session.copy(acknowledgement = acknowledgement))
            server.resumeResult = ServerResult.Success(session.copy(acknowledgement = acknowledgement))
            val outcome = repository.restore(address)
            assertEquals(RestoreOutcome.Restored(session.copy(acknowledgement = acknowledgement)), outcome)
            val stored = server.resumeCalls.single()
            assertEquals(session.userId, stored.userId)
            assertEquals(address, stored.address)
            assertEquals(acknowledgement, stored.acknowledgement)
            assertEquals(info.id, stored.serverId)
        }

    @Test
    fun nothingStoredIsSignedOutAndAsksNoServer() =
        runBlocking {
            assertEquals(RestoreOutcome.SignedOut, repository.restore(address))
            assertEquals(emptyList(), server.resumeCalls)
        }

    @Test
    fun clearsOnDeserialisationFailure() =
        runBlocking {
            store.save(SessionRepository.SESSION_KEY, "{ not json")
            assertEquals(RestoreOutcome.SignedOut, repository.restore(address))
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
        }

    @Test
    fun clearsOnAnUnknownFormatVersion() =
        runBlocking {
            repository.save(session)
            val current = "\"version\":${SessionRepository.FORMAT_VERSION}"
            store.save(SessionRepository.SESSION_KEY, blob().replace(current, "\"version\":99"))
            assertEquals(RestoreOutcome.SignedOut, repository.restore(address))
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
        }

    @Test
    fun clearsOnServerMismatch() =
        runBlocking {
            repository.save(session)
            server.resumeResult = ServerResult.Failure(MediaServerError.ServerMismatch())
            assertEquals(RestoreOutcome.Rejected(MediaServerError.ServerMismatch()), repository.restore(address))
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
        }

    @Test
    fun clearsOnSessionExpired() =
        runBlocking {
            repository.save(session)
            server.resumeResult = ServerResult.Failure(MediaServerError.SessionExpired())
            assertEquals(RestoreOutcome.Rejected(MediaServerError.SessionExpired()), repository.restore(address))
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
        }

    @Test
    fun aTokenCallThatReportsSessionExpiredClearsToo() =
        runBlocking {
            repository.save(session)
            repository.onServerError(MediaServerError.Timeout())
            assertTrue(SessionRepository.SESSION_KEY in store.snapshot())
            repository.onServerError(MediaServerError.SessionExpired())
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
        }

    @Test
    fun aFailedLoadDoesNotClear() =
        runBlocking {
            repository.save(session)
            store.failLoad = true
            assertEquals(RestoreOutcome.SignedOut, repository.restore(address))
            store.failLoad = false
            assertTrue(SessionRepository.SESSION_KEY in store.snapshot())
            assertEquals(emptyList(), store.clearedKeys)
        }

    @Test
    fun anUnreachableServerDoesNotClear() =
        runBlocking {
            repository.save(session)
            server.resumeResult = ServerResult.Failure(MediaServerError.Timeout())
            assertEquals(RestoreOutcome.Unavailable(MediaServerError.Timeout()), repository.restore(address))
            assertTrue(SessionRepository.SESSION_KEY in store.snapshot())
        }

    @Test
    fun aFailedSaveIsReportedAndWritesNowhereElse() =
        runBlocking {
            store.failSave = true
            assertEquals(PersistOutcome.NotSaved, repository.save(session))
            assertEquals(emptyList(), store.writtenKeys)
            assertEquals(emptyMap(), store.snapshot())
        }

    @Test
    fun changingTheConfiguredAddressClearsTheSessionWithoutAskingTheServer() =
        runBlocking {
            repository.save(session)
            val other = ServerAddress(Scheme.Https, "other.example", 443)
            assertEquals(RestoreOutcome.Rejected(MediaServerError.ServerMismatch()), repository.restore(other))
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
            assertEquals(emptyList(), server.resumeCalls)
        }

    @Test
    fun anAdoptedHttpsRedirectRewritesTheStoredAddressAndKeepsTheSession() =
        runBlocking {
            val http = ServerAddress(Scheme.Http, "jellyfin.example", 8096)
            val httpSession = session.copy(address = http)
            repository.save(httpSession)
            val upgraded = httpSession.copy(address = ServerAddress(Scheme.Https, "jellyfin.example", 443))
            server.resumeResult = ServerResult.Success(upgraded)
            assertEquals(RestoreOutcome.Restored(upgraded), repository.restore(http))
            assertTrue("\"https\"" in blob() || "Https" in blob())
            assertFalse("8096" in blob())
        }

    @Test
    fun signOutClearsFirstThenRevokes() =
        runBlocking {
            repository.save(session)
            server.onRevoke = { assertNull(store.snapshot()[SessionRepository.SESSION_KEY], "cleared before the call") }
            assertEquals(RevokeOutcome.Revoked, repository.signOut(session))
            assertEquals(listOf(session), server.revokeCalls)
        }

    @Test
    fun signOutStillClearsAndReportsNotRevokedWhenTheServerFails() =
        runBlocking {
            repository.save(session)
            server.revokeResult = ServerResult.Failure(MediaServerError.Timeout())
            assertEquals(RevokeOutcome.NotRevoked, repository.signOut(session))
            assertNull(store.snapshot()[SessionRepository.SESSION_KEY])
        }

    @Test
    fun signOutKeepsTheDeviceId() =
        runBlocking {
            val deviceId = StoredDeviceIdProvider(store) { "device-1" }.deviceId()
            repository.save(session)
            repository.signOut(session)
            assertEquals(SecretStoreResult.Success(deviceId), store.load(StoredDeviceIdProvider.KEY))
        }

    @Test
    fun aRestoreOutcomeIsTheKindTheCallerCanSwitchOn() {
        assertIs<RestoreOutcome>(RestoreOutcome.SignedOut)
    }
}
