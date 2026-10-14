// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.StoredSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(SecretAccess::class)
class SignInTest {
    private val jellyfin = FakeJellyfin()
    private val adapter = testAdapter()
    private val password = fake("password")
    private val token = fake("token")

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun request(
        pw: CharArray = password.toCharArray(),
        acknowledgement: CleartextAcknowledgement? = null,
        fallback: Boolean = false,
    ) = SignInRequest(if (fallback) jellyfin.fallbackAddress else jellyfin.address, "alice", pw, acknowledgement)

    private fun signIn(request: SignInRequest) = runBlocking { adapter.signIn(request) }

    @Test
    fun successReturnsASessionWithTheUserTheTokenTheServerAndTheAdminFlag() {
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token, admin = true)) }
        val session = signIn(request()).getOrNull()!!
        assertEquals("user1", session.userId.value)
        assertEquals(token, session.token.reveal)
        assertEquals(SERVER_ID, session.server.id.value)
        assertEquals("Home", session.server.name)
        assertTrue(session.isAdministrator)
        assertEquals(jellyfin.address, session.address)
    }

    @Test
    fun aNonAdministratorIsNotOne() {
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token, admin = false)) }
        assertFalse(signIn(request()).getOrNull()!!.isAdministrator)
    }

    @Test
    fun theBodyHasTheUsernameAndPasswordAndTheResultNeverEchoesThePassword() {
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token)) }
        val result = signIn(request(pw = "pa\"ss\\wé".toCharArray()))
        val body =
            jellyfin
                .requests("/Users/AuthenticateByName")
                .single()
                .body!!
                .utf8()
        assertEquals("{\"Username\":\"alice\",\"Pw\":\"pa\\\"ss\\\\wé\"}", body)
        assertFalse(result.toString().contains("pa\"ss"))
    }

    @Test
    fun aBadPasswordIsHttpUnauthorized() {
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.status(401) }
        assertIs<MediaServerError.HttpUnauthorized>(signIn(request()).errorOrNull())
    }

    @Test
    fun aLockedUserIsHttpForbidden() {
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.status(403) }
        assertIs<MediaServerError.HttpForbidden>(signIn(request()).errorOrNull())
    }

    @Test
    fun aLaterRejectedTokenIsSessionExpiredNotAWrongPassword() {
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token)) }
        jellyfin.on("POST", "/Sessions/Logout") { jellyfin.status(401) }
        val session = signIn(request()).getOrNull()!!
        assertIs<MediaServerError.SessionExpired>(runBlocking { adapter.revoke(session) }.errorOrNull())
    }

    @Test
    fun cleartextNotConfirmedSendsNothingAndStillWipesThePassword() {
        val pw = password.toCharArray()
        val result = signIn(request(pw = pw, fallback = true))
        assertIs<MediaServerError.CleartextNotConfirmed>(result.errorOrNull())
        assertEquals(0, jellyfin.log.size)
        assertContentEquals(CharArray(password.length), pw)
    }

    @Test
    fun theRightAcknowledgementLetsItThrough() {
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token)) }
        val ack = CleartextAcknowledgement(jellyfin.fallbackAddress)
        val session = signIn(request(acknowledgement = ack, fallback = true)).getOrNull()!!
        assertEquals(ack, session.acknowledgement)
    }

    @Test
    fun theCallersPasswordIsZeroFilledOnSuccessAndOnFailure() {
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token)) }
        val ok = password.toCharArray()
        signIn(request(pw = ok))
        assertContentEquals(CharArray(password.length), ok)

        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.status(401) }
        val bad = password.toCharArray()
        signIn(request(pw = bad))
        assertContentEquals(CharArray(password.length), bad)
    }

    @Test
    fun anAnswerFromAnotherServerIdIsAMismatch() {
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token, serverId = "someone-else")) }
        assertIs<MediaServerError.ServerMismatch>(signIn(request()).errorOrNull())
    }

    @Test
    fun aNonJellyfinAnswerIsNotJellyfinAndNoCredentialIsSent() {
        jellyfin.on("GET", "/System/Info/Public") { jellyfin.json("{}", 200) }
        assertIs<MediaServerError.NotJellyfin>(signIn(request()).errorOrNull())
        assertTrue(jellyfin.requests("/Users/AuthenticateByName").isEmpty())
    }

    @Test
    fun noPublicApiTakesAPreMadeTokenOrAnAdminKeyExceptRestoringAStoredSession() {
        for (method in MediaServer::class.java.methods.filterNot { it.isSynthetic }) {
            val name = method.name.lowercase()
            assertFalse("apikey" in name || "token" in name, method.name)
            assertFalse(method.parameterTypes.any { it == AccessToken::class.java }, method.name)
        }
        // Restoring a stored session is the one door, and it checks the server first (TokenBindingTest).
        val resume = MediaServer::class.java.methods.single { it.name == "resume" }
        assertEquals(StoredSession::class.java, resume.parameterTypes.first())
    }
}
