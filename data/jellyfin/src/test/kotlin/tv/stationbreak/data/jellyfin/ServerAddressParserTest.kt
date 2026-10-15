// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S0.3.1: any reasonable way of typing a server becomes one canonical address, or a typed error. */
class ServerAddressParserTest {
    /** The address with a typed scheme, or https (the first one `discover` tries) when there is none. */
    private fun parsed(input: String): ServerAddress? {
        val candidate = AddressParser.parse(input).getOrNull()
        return candidate?.let { it.explicit ?: it.at(Scheme.Https) }
    }

    private fun assertParses(
        expected: ServerAddress,
        vararg inputs: String,
    ) {
        for (input in inputs) assertEquals(expected, parsed(input), "input '$input'")
    }

    private fun assertInvalid(vararg inputs: String) {
        for (input in inputs) {
            val result = AddressParser.parse(input)
            assertEquals(ServerResult.Failure(MediaServerError.InvalidAddress()), result, "input '$input'")
        }
    }

    // Accepted forms.

    @Test
    fun hostAndPort() {
        assertParses(ServerAddress(Scheme.Https, "media.example.com", 8096), "media.example.com:8096")
    }

    @Test
    fun anIpv4AndPort() {
        assertParses(ServerAddress(Scheme.Https, "192.168.1.10", 8096), "192.168.1.10:8096")
    }

    @Test
    fun anExplicitHttpOrHttpsScheme() {
        assertParses(ServerAddress(Scheme.Http, "nas.local", 8096), "http://nas.local:8096")
        assertParses(ServerAddress(Scheme.Https, "media.example.com", 443), "https://media.example.com")
    }

    @Test
    fun theSchemeIsCaseInsensitive() {
        assertParses(ServerAddress(Scheme.Http, "nas.local", 80), "HTTP://nas.local", "Http://NAS.LOCAL")
    }

    @Test
    fun aSubpathIsKept() {
        val expected = ServerAddress(Scheme.Https, "media.example.com", 443, "/jellyfin")
        assertParses(expected, "https://media.example.com/jellyfin", "https://media.example.com/jellyfin/")
        assertParses(expected.copy(subpath = "/a/b"), "https://media.example.com/a/b/")
    }

    @Test
    fun anEqualsSignInThePathIsKeptNotACrash() {
        val expected = ServerAddress(Scheme.Https, "nas.lan", 443, "/jelly=fin")
        assertParses(expected, "https://nas.lan/jelly=fin")
    }

    @Test
    fun anIpv6LiteralWithAPort() {
        assertParses(ServerAddress(Scheme.Https, "fd00::1", 8096), "[fd00::1]:8096")
        assertParses(ServerAddress(Scheme.Http, "fd00::1", 8096), "http://[FD00::1]:8096/")
    }

    @Test
    fun trailingWebClientPathsAreStripped() {
        val root = ServerAddress(Scheme.Https, "media.example.com", 443)
        assertParses(
            root,
            "https://media.example.com/web/index.html",
            "https://media.example.com/web/",
            "https://media.example.com/web",
            "https://media.example.com/WEB/Index.html",
        )
        assertParses(
            root.copy(subpath = "/jellyfin"),
            "https://media.example.com/jellyfin/web/index.html",
            "https://media.example.com/jellyfin/web/",
        )
    }

    @Test
    fun aWebClientHashRouteIsDroppedWithTheFragment() {
        assertParses(
            ServerAddress(Scheme.Https, "media.example.com", 443),
            "https://media.example.com/web/index.html#!/home",
        )
    }

    // Normalisation.

    @Test
    fun trimsWhitespaceAndLowerCasesTheHost() {
        val expected = ServerAddress(Scheme.Https, "media.example.com", 443)
        assertParses(expected, "  Media.Example.COM \n", "\thttps://MEDIA.example.com ")
    }

    @Test
    fun dropsADefaultPortButTheAddressStillCarriesIt() {
        val http = ServerAddress(Scheme.Http, "nas.local", 80)
        assertParses(http, "http://nas.local:80", "http://nas.local")
        assertEquals("http://nas.local", http.baseUrl)
        val https = ServerAddress(Scheme.Https, "media.example.com", 443)
        assertParses(https, "https://media.example.com:443", "https://media.example.com")
        assertEquals("https://media.example.com", https.baseUrl)
    }

    @Test
    fun dropsTheQueryAndFragment() {
        assertParses(
            ServerAddress(Scheme.Https, "media.example.com", 443, "/jf"),
            "https://media.example.com/jf?x=1#frag",
            "https://media.example.com/jf/?x=1",
        )
    }

    @Test
    fun doesNotGuessAPort() {
        // 192.168.1.10 means port 80 or 443, never 8096; the setup screen can hint at the port.
        val candidate = AddressParser.parse("192.168.1.10").getOrNull()!!
        assertNull(candidate.port)
        assertNull(candidate.scheme)
        assertEquals(80, candidate.at(Scheme.Http).port)
        assertEquals(443, candidate.at(Scheme.Https).port)
    }

    @Test
    fun aTypedPortIsKeptForBothSchemesOfADiscovery() {
        val candidate = AddressParser.parse("nas:8096").getOrNull()!!
        assertEquals(8096, candidate.at(Scheme.Https).port)
        assertEquals(8096, candidate.at(Scheme.Http).port)
        // Typing the default port of the other scheme is a typed port, not a missing one.
        assertEquals(
            80,
            AddressParser
                .parse("nas:80")
                .getOrNull()!!
                .at(Scheme.Https)
                .port,
        )
    }

    // Rejections.

    @Test
    fun rejectsUserInfoWithoutRepeatingIt() {
        assertInvalid("user:pass@media.example.com", "https://user:pass@media.example.com", "http://user@host/", "x@y")
        val error = AddressParser.parse("https://alice:hunter2@media.example.com").errorOrNull()!!
        assertFalse("hunter2" in error.toString() || "alice" in error.toString(), error.toString())
        assertEquals(emptyList(), error.causes)
    }

    @Test
    fun rejectsEmptyInput() {
        assertInvalid("", "   ", "\n", "https://", "http:///jf")
    }

    @Test
    fun rejectsAnySchemeButHttpAndHttps() {
        assertInvalid("ftp://host", "ws://host", "file:///etc/passwd", "javascript://host")
        assertInvalid("tcp://host:8096", "://host")
    }

    @Test
    fun rejectsInvalidPorts() {
        assertInvalid("host:0", "host:65536", "host:-1", "host:abc", "host:99999999999", "http://host:80x")
    }

    @Test
    fun rejectsMalformedHosts() {
        assertInvalid("ho st", "host name:8096", "http://[fd00::1", "http://[::g]:80", "a b/c", "<script>")
    }

    @Test
    fun aSingleLabelNameWithATrailingDotKeepsItAsAnAbsolutePublicName() {
        assertParses(ServerAddress(Scheme.Http, "ai.", 80), "http://ai.", "http://AI./")
        assertEquals(true, parsed("http://ai.")?.cleartextNeedsConfirmation)
        // With more labels the dot is only the root label and is dropped.
        assertParses(ServerAddress(Scheme.Https, "media.example.com", 443), "media.example.com.")
    }

    @Test
    fun rejectsAnIpv6ZoneIdBecauseItNamesALocalInterfaceNotAServer() {
        assertInvalid("[fe80::1%25eth0]:8096", "[fe80::1%eth0]", "http://[fe80::1%25en0]/")
    }

    @Test
    fun rejectsTheOtherCharactersAUrlParserWouldQuietlyAccept() {
        assertInvalid("host\\@evil.example", "http://host:8096@evil.example")
    }

    // Property: parsing is total and idempotent on its own output.

    private val random = Random(SEED)

    private fun pick(vararg options: String) = options[random.nextInt(options.size)]

    private fun randomInput(): String {
        val scheme = pick("", "", "http://", "https://", "HTTPS://", "ftp://")
        val userInfo = if (random.nextInt(USERINFO_ODDS) == 0) "u:p@" else ""
        val host =
            pick(
                "media.example.com",
                "Media.Example.COM",
                "192.168.1.10",
                "10.0.0.1",
                "[fd00::1]",
                "[::ffff:10.0.0.1]",
                "nas",
                "nas.local",
                "127.1",
                "2130706433",
                "0x7f.1",
                "host.",
                "host..",
                "xn--bcher-kva.example",
                "bücher.example",
                "a".repeat(random.nextInt(MAX_LABEL)) + ".example",
            )
        val port = pick("", "", ":8096", ":80", ":443", ":0", ":65536", ":abc")
        val path = pick("", "/", "/jellyfin", "/jellyfin/", "/web/index.html", "/a/b/c/", "/a%20b", "//x", "/..")
        val tail = pick("", "", "?q=1", "#f", "?q=1#f")
        val noise =
            if (random.nextInt(NOISE_ODDS) ==
                0
            ) {
                String(CharArray(random.nextInt(NOISE_LENGTH)) { random.nextInt(CHAR_RANGE).toChar() })
            } else {
                ""
            }
        return scheme + userInfo + host + port + path + tail + noise
    }

    @Test
    fun parsingNeverThrowsAndIsIdempotentOnItsOwnOutput() {
        var accepted = 0
        repeat(ITERATIONS) {
            val input = randomInput()
            val result = AddressParser.parse(input)
            when (result) {
                is ServerResult.Failure -> {
                    assertIs<MediaServerError.InvalidAddress>(result.error, "input '$input'")
                }

                is ServerResult.Success -> {
                    accepted++
                    val candidate = result.value
                    val scheme = candidate.scheme ?: Scheme.Https
                    val address = candidate.at(scheme)
                    val again = AddressParser.parse(address.baseUrl)
                    val reparsed = again.getOrNull()
                    val again2 = reparsed?.at(reparsed.scheme ?: Scheme.Https)
                    assertEquals(address, again2, "input '$input' -> ${address.baseUrl}")
                    assertEquals(scheme, reparsed?.scheme, "input '$input'")
                    assertTrue(address.subpath.isEmpty() || address.subpath.startsWith("/"), "input '$input'")
                }
            }
        }
        assertTrue(accepted > ITERATIONS / MIN_ACCEPTED_DIVISOR, "only $accepted of $ITERATIONS inputs parsed")
    }

    private companion object {
        const val SEED = 0x5EED
        const val ITERATIONS = 5_000
        const val MIN_ACCEPTED_DIVISOR = 10
        const val USERINFO_ODDS = 15
        const val NOISE_ODDS = 8
        const val NOISE_LENGTH = 6
        const val CHAR_RANGE = 0x2FF
        const val MAX_LABEL = 70
    }
}
