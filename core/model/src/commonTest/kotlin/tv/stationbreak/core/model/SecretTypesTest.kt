// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(SecretAccess::class)
class AccessTokenTest {
    private val raw = "fake-token-for-tests-0123456789"

    @Test
    fun toStringIsRedacted() {
        assertEquals("AccessToken(***)", AccessToken(raw).toString())
    }

    @Test
    fun theRawValueIsReachableThroughTheOptInAccessor() {
        assertEquals(raw, AccessToken(raw).reveal)
    }

    @Test
    fun comparesByValue() {
        assertEquals(AccessToken(raw), AccessToken(raw))
        assertEquals(AccessToken(raw).hashCode(), AccessToken(raw).hashCode())
        assertNotEquals(AccessToken(raw), AccessToken("another-fake-token"))
    }

    @Test
    fun neverBlank() {
        assertFailsWith<IllegalArgumentException> { AccessToken(" ") }
    }

    @Test
    fun theQuickConnectSecretIsRedactedToo() {
        val secret = QuickConnectSecret("fake-quick-connect-secret")
        assertEquals("QuickConnectSecret(***)", secret.toString())
        assertEquals("fake-quick-connect-secret", secret.reveal)
    }
}

@OptIn(SecretAccess::class)
class SessionTypesTest {
    private val token = "fake-token-for-tests-0123456789"
    private val password = "fake-password-for-tests"
    private val address = ServerAddress(Scheme.Https, "jellyfin.example", 443)
    private val server = ServerInfo("Home", ServerId("srv1"), ServerVersion(12, 2, 0))

    @Test
    fun sessionToStringHasNoTokenAndNoHost() {
        val session = Session(UserId("user1"), AccessToken(token), server, address, null, isAdministrator = true)
        val text = session.toString()
        assertFalse(token in text)
        assertFalse("jellyfin.example" in text)
        assertTrue("***" in text)
    }

    @Test
    fun storedSessionToStringHasNoTokenAndNoHost() {
        val stored = StoredSession(UserId("user1"), AccessToken(token), server.id, address, null)
        val text = stored.toString()
        assertFalse(token in text)
        assertFalse("jellyfin.example" in text)
    }

    @Test
    fun aSessionCarriesTheAddressAndAcknowledgementItWasIssuedFor() {
        val http = ServerAddress(Scheme.Http, "jellyfin.example", 8096, viaFallback = true)
        val acknowledgement = CleartextAcknowledgement(http)
        val session = Session(UserId("user1"), AccessToken(token), server, http, acknowledgement, false)
        assertEquals(http, session.address)
        assertEquals(acknowledgement, session.acknowledgement)
        assertEquals(http, session.toStored().address)
        assertEquals(acknowledgement, session.toStored().acknowledgement)
        assertEquals(server.id, session.toStored().serverId)
    }

    @Test
    fun signInRequestToStringHasNeitherPasswordNorHost() {
        val request = SignInRequest(address, "alice", password.toCharArray(), null)
        val text = request.toString()
        assertFalse(password in text)
        assertFalse("jellyfin.example" in text)
        assertFalse("alice" in text)
    }

    @Test
    fun anAddressBindsToItsOwnHostPortAndSubpathAndToItsHttpsUpgrade() {
        val http = ServerAddress(Scheme.Http, "jellyfin.example", 8096, "/jf")
        assertTrue(http.bindsTo(http))
        assertTrue(ServerAddress(Scheme.Https, "jellyfin.example", 443, "/jf").bindsTo(http))
        assertTrue(ServerAddress(Scheme.Https, "jellyfin.example", 8096, "/jf").bindsTo(http))
        assertFalse(ServerAddress(Scheme.Https, "other.example", 443, "/jf").bindsTo(http))
        assertFalse(ServerAddress(Scheme.Https, "jellyfin.example", 9443, "/jf").bindsTo(http))
        assertFalse(ServerAddress(Scheme.Https, "jellyfin.example", 443, "/other").bindsTo(http))
        // Never a downgrade.
        assertFalse(http.bindsTo(ServerAddress(Scheme.Https, "jellyfin.example", 443, "/jf")))
    }
}
