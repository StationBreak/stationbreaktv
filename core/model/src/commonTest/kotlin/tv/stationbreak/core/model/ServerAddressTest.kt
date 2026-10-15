// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerAddressTest {
    private fun address(
        scheme: Scheme = Scheme.Https,
        host: String = "media.example.com",
        port: Int = scheme.defaultPort,
        subpath: String = "",
        viaFallback: Boolean = false,
    ) = ServerAddress(scheme, host, port, subpath, viaFallback)

    @Test
    fun keepsItsParts() {
        val address = address(Scheme.Http, "192.168.1.10", 8096, "/jellyfin")
        assertEquals(Scheme.Http, address.scheme)
        assertEquals("192.168.1.10", address.host)
        assertEquals(8096, address.port)
        assertEquals("/jellyfin", address.subpath)
    }

    // Defence in depth for the stored record, whose text is `key=value` lines (#232 L7, #234 L6): a subpath of
    // "/x\npin=<hex>" would inject a pin, and none of these can come from a real URL.
    @Test
    fun aSubpathWithAControlCharacterOrAnEqualsSignIsRefused() {
        val bad = listOf("/a\nb", "/a\rb", "/a\u0000b", "/a\tb", "/a\u007fb", "/a\u0085b", "/x\npin=00", "/a=b", "/=")
        for (subpath in bad) {
            assertFailsWith<IllegalArgumentException>(subpath) { address(subpath = subpath) }
        }
    }

    @Test
    fun aHostWithAControlCharacterOrAnEqualsSignIsRefused() {
        val bad = listOf("nas\nhost", "nas\rhost", "nas\u0000", "nas\thost", "nas=host", "=", "nas\u007f")
        for (host in bad) {
            assertFailsWith<IllegalArgumentException>(host) { address(host = host) }
        }
    }

    @Test
    fun anOrdinaryPercentEncodedSubpathStillPasses() {
        assertEquals("/media%20server/jelly-fin_1.0~x", address(subpath = "/media%20server/jelly-fin_1.0~x").subpath)
    }

    @Test
    fun defaultPortsComeFromTheScheme() {
        assertEquals(80, Scheme.Http.defaultPort)
        assertEquals(443, Scheme.Https.defaultPort)
    }

    @Test
    fun baseUrlOmitsADefaultPortAndKeepsTheSubpath() {
        assertEquals("https://media.example.com", address().baseUrl)
        assertEquals("http://nas", address(Scheme.Http, "nas").baseUrl)
        assertEquals("http://192.168.1.10:8096", address(Scheme.Http, "192.168.1.10", 8096).baseUrl)
        assertEquals("https://media.example.com:8920/jf", address(port = 8920, subpath = "/jf").baseUrl)
        // A non-default port for the other scheme is not a default.
        assertEquals("https://media.example.com:80", address(port = 80).baseUrl)
    }

    @Test
    fun baseUrlBracketsAnIpv6Literal() {
        assertEquals("http://[fd00::1]:8096", address(Scheme.Http, "fd00::1", 8096).baseUrl)
    }

    @Test
    fun cleartextNeedsConfirmationOnlyForHttpToANonPrivateHost() {
        assertTrue(address(Scheme.Http, "media.example.com").cleartextNeedsConfirmation)
        assertTrue(address(Scheme.Http, "100.64.0.1").cleartextNeedsConfirmation)
        assertTrue(address(Scheme.Http, "8.8.8.8", 8096).cleartextNeedsConfirmation)
        assertFalse(address(Scheme.Http, "192.168.1.10", 8096).cleartextNeedsConfirmation)
        assertFalse(address(Scheme.Http, "nas.local").cleartextNeedsConfirmation)
        assertFalse(address(Scheme.Http, "fd00::1", 8096).cleartextNeedsConfirmation)
        assertFalse(address(Scheme.Https, "media.example.com").cleartextNeedsConfirmation)
        assertFalse(address(Scheme.Https, "8.8.8.8").cleartextNeedsConfirmation)
    }

    @Test
    fun toStringRedactsTheHostAndTheSubpath() {
        val text = address(Scheme.Https, "media.example.com", 8920, "/secret-path").toString()
        assertFalse("media.example.com" in text, text)
        assertFalse("secret-path" in text, text)
        assertTrue("8920" in text, text)
        val ipText = address(Scheme.Http, "192.168.1.10", 8096).toString()
        assertFalse("192.168.1.10" in ipText, ipText)
    }

    @Test
    fun equalityIgnoresNothing() {
        val base = address(Scheme.Http, "192.168.1.10", 8096, "/jf")
        assertEquals(base, address(Scheme.Http, "192.168.1.10", 8096, "/jf"))
        assertNotEquals(base, base.copy(scheme = Scheme.Https))
        assertNotEquals(base, base.copy(host = "192.168.1.11"))
        assertNotEquals(base, base.copy(port = 8097))
        assertNotEquals(base, base.copy(subpath = ""))
        assertNotEquals(base, base.copy(subpath = "/jf2"))
    }

    @Test
    fun rejectsAnInvalidPortOrSubpath() {
        assertFailsWith<IllegalArgumentException> { address(port = 0) }
        assertFailsWith<IllegalArgumentException> { address(port = 65536) }
        assertFailsWith<IllegalArgumentException> { address(subpath = "jellyfin") }
        assertFailsWith<IllegalArgumentException> { address(subpath = "/jellyfin/") }
        assertFailsWith<IllegalArgumentException> { address(host = "") }
    }

    @Test
    fun rejectsAHostTheClassifierCallsInvalidOrNotLowerCase() {
        // An address not built by the parser (restored from storage, or a fake) is held to the same rules.
        for (host in listOf("0.0.0.0", "127.1", "2130706433", "224.0.0.1", "255.255.255.255", "::", "ff02::1", "a b")) {
            assertFailsWith<IllegalArgumentException>(host) { address(host = host) }
        }
        assertFailsWith<IllegalArgumentException> { address(host = "Media.Example.com") }
        assertFailsWith<IllegalArgumentException> { address(Scheme.Http, host = "FD00::1") }
    }

    @Test
    fun theErrorOfAnInvalidHostRepeatsNoHost() {
        val error = assertFailsWith<IllegalArgumentException> { address(host = "0x7f.1") }
        assertFalse("0x7f" in error.message.orEmpty(), error.message)
    }

    @Test
    fun httpReachedByFallbackAlwaysNeedsConfirmationEvenForAPrivateHost() {
        // Owner decision (ADR-0014): http chosen after https failed is confirmed once, even on the LAN.
        val typed = address(Scheme.Http, "192.168.1.10", 8096)
        val fallback = typed.copy(viaFallback = true)
        assertFalse(typed.cleartextNeedsConfirmation)
        assertTrue(fallback.cleartextNeedsConfirmation)
        assertTrue(address(Scheme.Http, "nas.local", viaFallback = true).cleartextNeedsConfirmation)
        assertTrue(address(Scheme.Http, "nas", viaFallback = true).cleartextNeedsConfirmation)
        assertFalse(address(Scheme.Https, "media.example.com", viaFallback = true).cleartextNeedsConfirmation)
        assertNotEquals(typed, fallback)
        assertFalse(CleartextAcknowledgement(typed).matches(fallback))
        assertTrue(CleartextAcknowledgement(fallback).matches(fallback.copy()))
    }

    @Test
    fun theFallbackFlagSurvivesJsonAndDefaultsToFalse() {
        val fallback = address(Scheme.Http, "192.168.1.10", 8096).copy(viaFallback = true)
        val json = Json.encodeToString(ServerAddress.serializer(), fallback)
        assertEquals(fallback, Json.decodeFromString(ServerAddress.serializer(), json))
        val old = """{"scheme":"Http","host":"nas","port":80}"""
        assertFalse(Json.decodeFromString(ServerAddress.serializer(), old).viaFallback)
    }

    @Test
    fun roundTripsThroughJson() {
        val original = address(Scheme.Http, "192.168.1.10", 8096, "/jf")
        val json = Json.encodeToString(ServerAddress.serializer(), original)
        assertEquals(original, Json.decodeFromString(ServerAddress.serializer(), json))
    }

    @Test
    fun anInvalidStoredValueFailsToDecode() {
        val json = """{"scheme":"Https","host":"h","port":0,"subpath":""}"""
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(ServerAddress.serializer(), json) }
    }

    // The cleartext acknowledgement is bound to exactly one address.

    @Test
    fun anAcknowledgementMatchesOnlyItsOwnAddress() {
        val address = address(Scheme.Http, "media.example.com", 8096, "/jf")
        val acknowledgement = CleartextAcknowledgement(address)

        assertTrue(acknowledgement.matches(address.copy()))
        assertFalse(acknowledgement.matches(address.copy(host = "other.example.com")), "host")
        assertFalse(acknowledgement.matches(address.copy(port = 8097)), "port")
        assertFalse(acknowledgement.matches(address.copy(scheme = Scheme.Https)), "scheme")
        assertFalse(acknowledgement.matches(address.copy(subpath = "")), "subpath")
    }

    @Test
    fun anAcknowledgementDoesNotRevealTheHost() {
        val text = CleartextAcknowledgement(address(Scheme.Http, "media.example.com")).toString()
        assertFalse("media.example.com" in text, text)
    }

    // Identifiers and the server's own description.

    @Test
    fun idsAreNeverBlank() {
        assertEquals("abc", ServerId("abc").value)
        assertEquals("u1", UserId("u1").value)
        assertFailsWith<IllegalArgumentException> { ServerId(" ") }
        assertFailsWith<IllegalArgumentException> { UserId("") }
        assertNotEquals<Any>(ServerId("abc"), UserId("abc"))
    }

    @Test
    fun parsesAServerVersionAndOrdersIt() {
        assertEquals(ServerVersion(12, 2, 1), ServerVersion.parse("12.2.1"))
        assertEquals(ServerVersion(12, 0, 0), ServerVersion.parse("12.0"))
        assertEquals(ServerVersion(10, 11, 0), ServerVersion.parse("10.11.0-rc.1"))
        assertEquals(ServerVersion(12, 0, 0), ServerVersion.parse("12.0.0.0"))
        assertNull(ServerVersion.parse(""))
        assertNull(ServerVersion.parse("twelve"))
        assertNull(ServerVersion.parse("-1.0.0"))
        assertTrue(ServerVersion(12, 0, 0) < ServerVersion(12, 0, 1))
        assertTrue(ServerVersion(11, 99, 99) < ServerVersion(12, 0, 0))
        assertTrue(ServerVersion(13, 0, 0) > ServerVersion(12, 2, 9))
        assertEquals("12.2.1", ServerVersion(12, 2, 1).toString())
    }

    @Test
    fun serverInfoCarriesNameIdAndVersion() {
        val info = ServerInfo("Home", ServerId("abc"), ServerVersion(12, 2, 0))
        assertEquals("Home", info.name)
        assertEquals(ServerId("abc"), info.id)
        assertEquals(ServerVersion(12, 2, 0), info.version)
    }
}
