// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What leaves the TV in a crash report (#96): tokens, URLs, server host names and addresses never do. */
class CrashReportRedactorTest {
    private fun redact(text: String) = CrashReportRedactor.redact(text)

    private fun assertGone(
        text: String,
        vararg secrets: String,
    ) {
        val out = redact(text)
        secrets.forEach { assertFalse("'$it' survived in: $out", out.contains(it)) }
    }

    @Test
    fun urlsGoWhole_withTheirQueryAndCredentials() {
        assertGone(
            "HTTP 401 for https://media.example.org:8920/Items?api_key=0123abcd&x=1",
            "media.example.org",
            "8920",
            "0123abcd",
            "/Items",
        )
        assertGone("redirect to http://user:hunter2@192.168.1.20:8096/web/", "hunter2", "192.168.1.20", "user")
        assertEquals("GET [url] failed", redact("GET https://a.b/c failed"))
    }

    @Test
    fun tokensAndKeysGo_inEveryShapeAnErrorPrintsThem() {
        assertGone("api_key=deadbeefdeadbeefdeadbeefdeadbeef", "deadbeef")
        assertGone("""{"AccessToken":"abc123secretvalue"}""", "abc123secretvalue")
        assertGone("""Token="s3cr3tT0ken", Device="TV"""", "s3cr3tT0ken")
        assertGone("password: hunter2 was rejected", "hunter2")
        // Built in pieces: a secret scanner reads a whole literal like this as a real key.
        val key = listOf("9f8e7d6c", "5b4a3928", "1706f5e4", "d3c2b1a0").joinToString("")
        assertGone("X-Emby-Token: $key", key)
        assertGone(
            """Authorization: MediaBrowser Client="StationBreak", DeviceId="d-1", Token="zzz999"""",
            "zzz999",
            "StationBreak",
            "d-1",
        )
        assertGone("Authorization: Bearer abc.def.ghi", "abc.def.ghi")
    }

    @Test
    fun serverIdsAndOpaqueTokensGo() {
        assertGone("no item 0123456789abcdef0123456789abcdef in library", "0123456789abcdef")
        assertGone("user 01234567-89ab-cdef-0123-456789abcdef missing", "01234567-89ab")
        assertGone("bad token Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MA", "Zm9vYmFy")
    }

    @Test
    fun hostNamesAndAddressesGo_withAndWithoutADomain() {
        assertGone("""Unable to resolve host "jellyfin.home.example.com": No address""", "jellyfin", "example.com")
        assertGone("""Unable to resolve host "nas": No address associated with hostname""", "nas\"")
        assertGone("Failed to connect to nas.local/192.168.1.5:8096", "nas.local", "192.168.1.5", "8096")
        assertGone("failed to connect to /10.0.0.7 (port 8096) after 10000ms", "10.0.0.7")
        assertGone("connect to [fe80::1ff:fe23:4567:890a]:8096 failed", "fe80", "4567")
        assertGone("handshake with media.example.org:8920 failed", "media.example.org")
        assertGone("host=tv-box.lan", "tv-box")
        assertGone("write to owner@example.org failed", "owner@")
    }

    @Test
    fun aStackTraceKeepsItsCode() {
        val trace =
            """
            java.net.UnknownHostException: Unable to resolve host "media.example.org": No address associated with hostname
            	at java.net.InetAddress.lookupHostByName(InetAddress.java:521)
            	at tv.stationbreak.data.jellyfin.JellyfinClient${'$'}Call.execute(JellyfinClient.kt:88)
            	at okhttp3.internal.connection.RealCall.execute(RealCall.kt:154)
            	at android.os.Looper.loop(Looper.java:193)
            	at com.android.internal.os.ZygoteInit.main(Native Method)
            Caused by: kotlin.IllegalStateException: no result for tv.stationbreak.core.domain.Channel
            	... 12 more
            """.trimIndent()
        val out = redact(trace)
        assertFalse(out.contains("media.example.org"))
        val kept =
            listOf(
                "java.net.UnknownHostException",
                "at java.net.InetAddress.lookupHostByName(InetAddress.java:521)",
                "at tv.stationbreak.data.jellyfin.JellyfinClient\$Call.execute(JellyfinClient.kt:88)",
                "at okhttp3.internal.connection.RealCall.execute(RealCall.kt:154)",
                "at com.android.internal.os.ZygoteInit.main(Native Method)",
                "Caused by: kotlin.IllegalStateException: no result for tv.stationbreak.core.domain.Channel",
                "... 12 more",
            )
        kept.forEach { assertTrue("'$it' was lost in:\n$out", out.contains(it)) }
    }

    @Test
    fun aHostNamedLikeACodePackageStillGoes() {
        assertGone("failed to reach java.example.com", "java.example.com")
        assertGone("failed to reach android.local", "android.local")
        assertGone("failed to reach kotlin.COM", "kotlin.COM")
    }

    @Test
    fun aFrameLineCannotCarryAMessage() {
        // Looks like a frame but the parentheses hold a URL: not a frame, so it is redacted.
        assertGone("at x.Y.z(see https://secret.example.org/p)", "secret.example.org")
    }

    @Test
    fun plainTextAndVersionsAreLeftAlone() {
        assertEquals("Channel 5 was empty for 3 s at 1.2.3", redact("Channel 5 was empty for 3 s at 1.2.3"))
        assertEquals("", redact(""))
    }

    @Test
    fun whatAParseErrorQuotesOfTheServersJsonGoes() {
        assertGone(
            "JsonDecodingException: Unexpected token at offset 4 JSON input: " +
                """{"Name":"My Show","Path":"/media/tv/My Show/s1.mkv","UserName":"alice"}""",
            "My Show",
            "alice",
            "/media",
        )
    }

    @Test
    fun pathsQuotedNamesAndCertificateNamesGo() {
        assertGone("cannot read /data/user/0/tv.stationbreak.app/files/lineup.json", "lineup.json", "/data/user")
        assertGone("no channel named \"Cartoons After School\"", "Cartoons")
        assertGone("untrusted: DN: CN=Home Lab Root, O=Smith Family, C=US", "Home Lab", "Smith")
    }

    @Test
    fun singleLabelHostsInPlatformMessagesGo() {
        assertGone("CLEARTEXT communication to nas not permitted by network security policy", "to nas")
        assertGone("Hostname nas not verified", "nas not")
        assertGone("failed at 192.168.1.5.", "192.168.1.5")
    }

    @Test
    fun aRealUuidReportIdIsNotRewritten() {
        val id = "0b9e1c1e-2f6a-4a58-8f0f-5a1f0f0e7d11"
        val body = checkNotNull(CrashReportBody.encode(mapOf("REPORT_ID" to id, "STACK_TRACE" to "x.Y: id $id")))
        assertTrue(body.contains(""""REPORT_ID":"$id""""))
        assertFalse(body.contains("x.Y: id $id"))
    }
}
