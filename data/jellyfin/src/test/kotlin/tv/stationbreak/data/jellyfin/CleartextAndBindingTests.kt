// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.StoredSession
import tv.stationbreak.core.model.UserId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val INFO = ServerInfo("Home", ServerId(SERVER_ID), ServerVersion(12, 2, 1))

private fun sessionFor(
    address: ServerAddress,
    token: String,
    acknowledgement: CleartextAcknowledgement? = null,
    serverId: String = SERVER_ID,
) = Session(UserId("user1"), AccessToken(token), INFO.copy(id = ServerId(serverId)), address, acknowledgement, false)

@OptIn(SecretAccess::class)
class CleartextGateTest {
    private val jellyfin = FakeJellyfin()
    private val adapter = testAdapter()
    private val token = fake("token")

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun requestsSent() = jellyfin.log.size

    @Test
    fun passwordSignInQuickConnectAndEveryTokenCallSendNothingWithoutTheAcknowledgement() =
        runBlocking {
            val address = jellyfin.fallbackAddress
            val signIn = adapter.signIn(SignInRequest(address, "alice", "pw".toCharArray(), null))
            val quick = adapter.quickConnect(address, null).toList()
            val revoke = adapter.revoke(sessionFor(address, token))
            val stored = StoredSession(UserId("u"), AccessToken(token), ServerId(SERVER_ID), address, null)
            val resume = adapter.resume(stored)
            assertIs<MediaServerError.CleartextNotConfirmed>(signIn.errorOrNull())
            assertEquals(listOf(QuickConnectState.Failed(MediaServerError.CleartextNotConfirmed())), quick)
            assertIs<MediaServerError.CleartextNotConfirmed>(revoke.errorOrNull())
            assertIs<MediaServerError.CleartextNotConfirmed>(resume.errorOrNull())
            assertEquals(0, requestsSent())
        }

    @Test
    fun anAcknowledgementBuiltFromAnotherAddressDoesNotCount() =
        runBlocking {
            val address = jellyfin.fallbackAddress
            val other = CleartextAcknowledgement(address.copy(port = address.port + 1))
            assertIs<MediaServerError.CleartextNotConfirmed>(
                adapter.signIn(SignInRequest(address, "alice", "pw".toCharArray(), other)).errorOrNull(),
            )
            val revoke = adapter.revoke(sessionFor(address, token, other))
            assertIs<MediaServerError.CleartextNotConfirmed>(revoke.errorOrNull())
            assertEquals(0, requestsSent())
        }

    @Test
    fun theRightAcknowledgementProceedsAndPrivateOrHttpsNeedsNone() =
        runBlocking {
            jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token)) }
            jellyfin.on("POST", "/Sessions/Logout") { jellyfin.status(204) }
            val address = jellyfin.fallbackAddress
            val ack = CleartextAcknowledgement(address)
            val session = adapter.signIn(SignInRequest(address, "alice", "pw".toCharArray(), ack)).getOrNull()!!
            assertTrue(adapter.revoke(session) is ServerResult.Success)
            // Typed http to a private host: no acknowledgement.
            val typed = adapter.signIn(SignInRequest(jellyfin.address, "alice", "pw".toCharArray(), null))
            assertTrue(typed is ServerResult.Success)
        }
}

@OptIn(SecretAccess::class)
class TokenBindingTest {
    private val jellyfin = FakeJellyfin()
    private val token = fake("token")

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun stored(
        serverId: String = SERVER_ID,
        address: ServerAddress = jellyfin.address,
    ) = StoredSession(UserId("user1"), AccessToken(token), ServerId(serverId), address, null)

    private fun meOk() {
        jellyfin.on("GET", "/Users/Me") { jellyfin.json("{\"Id\":\"user1\",\"Policy\":{\"IsAdministrator\":true}}") }
    }

    private fun carriesToken(request: mockwebserver3.RecordedRequest): Boolean {
        val header = request.headers["Authorization"]
        return header?.contains("Token=") == true
    }

    @Test
    fun theFirstTokenBearingRequestAfterARestoreIsPrecededByAProbeOfTheStoredAddress() =
        runBlocking {
            meOk()
            val session = testAdapter().resume(stored()).getOrNull()!!
            assertEquals(listOf("/System/Info/Public", "/Users/Me"), jellyfin.log.map { it.url.encodedPath })
            assertFalse(carriesToken(jellyfin.log.first()))
            assertTrue(carriesToken(jellyfin.log.last()))
            assertTrue(session.isAdministrator)
            assertEquals("Home", session.server.name)
        }

    @Test
    fun aDifferentServerIdIsAMismatchAndNoTokenReachedTheOtherServer() =
        runBlocking {
            meOk()
            val result = testAdapter().resume(stored(serverId = "the-server-that-issued-it"))
            assertIs<MediaServerError.ServerMismatch>(result.errorOrNull())
            assertTrue(jellyfin.log.none(::carriesToken))
        }

    @Test
    fun aMatchingProbeProceedsOnceAndIsNotRepeatedPerCall() =
        runBlocking {
            meOk()
            jellyfin.on("POST", "/Sessions/Logout") { jellyfin.status(204) }
            val adapter = testAdapter()
            val session = adapter.resume(stored()).getOrNull()!!
            // Not revoke twice: a revoke forgets the binding (issue 322), so only other calls share the one probe.
            adapter.libraries(session)
            adapter.libraries(session)
            adapter.revoke(session)
            assertEquals(1, jellyfin.requests("/System/Info/Public").size)
        }

    @Test
    fun aSessionTheAdapterDidNotIssueIsProbedBeforeItsFirstUseAndMismatchRefusesIt() =
        runBlocking {
            jellyfin.on("POST", "/Sessions/Logout") { jellyfin.status(204) }
            val foreign = sessionFor(jellyfin.address, token, serverId = "another-server")
            val result = testAdapter().revoke(foreign)
            assertIs<MediaServerError.ServerMismatch>(result.errorOrNull())
            assertTrue(jellyfin.log.none(::carriesToken))
            // A fresh adapter (a new run) probes again for a session it never saw.
            val matching = sessionFor(jellyfin.address, token)
            testAdapter().revoke(matching)
            assertEquals(2, jellyfin.requests("/System/Info/Public").size)
        }

    @Test
    fun aTokenIsNeverReBoundToAnotherHost() {
        runBlocking {
            FakeJellyfin().use { second ->
                second.on("POST", "/Sessions/Logout") { second.status(204) }
                jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token)) }
                jellyfin.on("POST", "/Sessions/Logout") { jellyfin.status(204) }
                val adapter = testAdapter()
                val session =
                    adapter.signIn(SignInRequest(jellyfin.address, "alice", "pw".toCharArray(), null)).getOrNull()!!
                val moved = session.copy(address = second.address)
                assertIs<MediaServerError.ServerMismatch>(adapter.revoke(moved).errorOrNull())
                assertEquals(0, second.log.size)
            }
        }
    }

    @Test
    fun aRevokedTokenOnResumeIsSessionExpired() {
        runBlocking {
            jellyfin.on("GET", "/Users/Me") { jellyfin.status(401) }
            assertIs<MediaServerError.SessionExpired>(testAdapter().resume(stored()).errorOrNull())
        }
    }

    @Test
    fun anUpgradedAddressStillBindsToTheStoredHttpOne() {
        val http = ServerAddress(tv.stationbreak.core.model.Scheme.Http, "jellyfin.example", 8096)
        val https = ServerAddress(tv.stationbreak.core.model.Scheme.Https, "jellyfin.example", 443)
        assertTrue(https.bindsTo(http))
        assertFalse(ServerAddress(tv.stationbreak.core.model.Scheme.Https, "other.example", 443).bindsTo(http))
        assertNull(null)
    }
}
