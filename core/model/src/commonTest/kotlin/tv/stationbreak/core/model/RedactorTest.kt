// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** F0.4 / S0.4.1: no secret survives [Redactor.redact], in any of the four forms, in any case. Dummy values only. */
class RedactorTest {
    private val dummy = "dummy-token-0001"

    private val names =
        listOf(
            "api_key",
            "ApiKey",
            "Token",
            "X-Emby-Token",
            "X-Emby-Authorization",
            "Authorization",
            "AccessToken",
            "Secret",
            "Pw",
            "Password",
        )

    private fun mixed(name: String): List<String> = listOf(name, name.lowercase(), name.uppercase())

    @Test
    fun queryParametersAreMaskedInEveryCase() {
        for (name in names.flatMap(::mixed)) {
            assertEquals(
                "GET /Items?$name=<redacted>&limit=5",
                Redactor.redact("GET /Items?$name=$dummy&limit=5"),
                "query param $name",
            )
            assertEquals("/x?a=1&$name=<redacted>", Redactor.redact("/x?a=1&$name=$dummy"), "trailing $name")
        }
    }

    @Test
    fun headerLinesAreMaskedInEveryCase() {
        for (name in names.flatMap(::mixed)) {
            assertEquals("$name: <redacted>", Redactor.redact("$name: $dummy"), "header $name")
            assertEquals("$name : <redacted>", Redactor.redact("$name : $dummy"), "spaced header $name")
        }
        assertEquals(
            "Host: example.test\nAuthorization: <redacted>\nAccept: */*",
            Redactor.redact("Host: example.test\nAuthorization: Bearer $dummy\nAccept: */*"),
        )
    }

    @Test
    fun theWholeOfAnAuthorizationHeaderValueIsMasked() {
        val line = "X-Emby-Authorization: MediaBrowser Client=\"StationBreak\", Token=\"$dummy\", Version=\"1\""
        assertEquals("X-Emby-Authorization: <redacted>", Redactor.redact(line))
    }

    @Test
    fun keyValuePairsInsideAHeaderValueAreMasked() {
        for (name in names.flatMap(::mixed)) {
            assertEquals(
                "MediaBrowser Client=\"StationBreak\", $name=\"<redacted>\", Version=\"1\"",
                Redactor.redact("MediaBrowser Client=\"StationBreak\", $name=\"$dummy\", Version=\"1\""),
                "pair $name",
            )
        }
    }

    @Test
    fun jsonPairsAreMaskedWithOrWithoutWhitespace() {
        for (name in names.flatMap(::mixed)) {
            assertEquals("{\"$name\":\"<redacted>\"}", Redactor.redact("{\"$name\":\"$dummy\"}"), "compact $name")
            assertEquals(
                "{\"a\": 1, \"$name\" :  \"<redacted>\" , \"b\": 2}",
                Redactor.redact("{\"a\": 1, \"$name\" :  \"$dummy\" , \"b\": 2}"),
                "spaced $name",
            )
        }
        assertEquals("{\"Pw\":\"<redacted>\"}", Redactor.redact("{\"Pw\":\"a\\\"b-$dummy\"}"), "escaped quote")
    }

    @Test
    fun jsonValuesThatAreNotStringsAreMasked() {
        assertEquals("{\"Pw\": <redacted>, \"ok\": true}", Redactor.redact("{\"Pw\": 123456, \"ok\": true}"))
    }

    @Test
    fun anExceptionMessageCarryingAUrlIsMasked() {
        val message = "Unable to resolve http://jf.example.test:8096/Videos/abc/stream?static=true&ApiKey=$dummy"
        val out = Redactor.redact(message)
        assertFalse(dummy in out)
        val expected = "Unable to resolve http://jf.example.test:8096/Videos/abc/stream?static=true&ApiKey=<redacted>"
        assertEquals(expected, out)
    }

    @Test
    fun severalSecretsInOneStringAreAllMasked() {
        val out = Redactor.redact("?api_key=$dummy&Token=$dummy {\"Password\":\"$dummy\"} Authorization: $dummy")
        assertFalse(dummy in out, out)
    }

    @Test
    fun redactionIsIdempotent() {
        val once = Redactor.redact("?api_key=$dummy {\"Pw\":\"$dummy\"} Token=\"$dummy\" Authorization: $dummy")
        assertEquals(once, Redactor.redact(once))
    }

    @Test
    fun anOrdinaryMessagePassesThroughUnchanged() {
        val messages =
            listOf(
                "",
                "revoke ok",
                "revoke failed: Timeout",
                "sign-in failed: InvalidCredentials",
                "GET /Items?limit=5&startIndex=10 returned 200",
                "{\"name\":\"Movies\",\"count\":3}",
                "Content-Type: application/json",
            )
        for (message in messages) assertEquals(message, Redactor.redact(message))
    }

    @Test
    fun jsonKeysThatContainANameAnywhereAreMasked() {
        for (key in listOf("NewPw", "access_token", "CurrentPassword", "my-api_key-v2", "REFRESHTOKEN")) {
            val out = Redactor.redact("{\"id\":1,\"$key\":\"$dummy\",\"ok\":true}")
            assertFalse(dummy in out, "key $key: $out")
            assertEquals("{\"id\":1,\"$key\":\"<redacted>\",\"ok\":true}", out)
        }
    }

    @Test
    fun escapedJsonIsMasked() {
        val out = Redactor.redact("body={\\\"AccessToken\\\":\\\"$dummy\\\",\\\"n\\\":1}")
        assertFalse(dummy in out, out)
        assertEquals("body={\\\"AccessToken\\\":\\\"<redacted>\\\",\\\"n\\\":1}", out)
    }

    @Test
    fun aJsonStringWithNoClosingQuoteIsMaskedToTheEnd() {
        val out = Redactor.redact("{\"AccessToken\":\"$dummy")
        assertFalse(dummy in out, out)
    }

    @Test
    fun authorizationValuesWithSpacesAreMasked() {
        val out = Redactor.redact("{Authorization=Bearer $dummy more, Other=1}")
        assertFalse(dummy in out, out)
        assertFalse("more" in out, out)
        assertEquals("{Authorization=<redacted>, Other=1}", out)
        val encoded = Redactor.redact("{X-Emby-Authorization=MediaBrowser Token $dummy}")
        assertEquals("{X-Emby-Authorization=<redacted>}", encoded)
        assertEquals("sent Bearer <redacted> ok", Redactor.redact("sent bearer $dummy ok"))
    }

    @Test
    fun quotedValuesAreMaskedThroughTheMatchingQuote() {
        assertEquals("Token='<redacted>' x=1", Redactor.redact("Token='$dummy a b' x=1"))
        assertEquals("Token=\"<redacted>\" x=1", Redactor.redact("Token=\"$dummy a b\" x=1"))
        assertEquals("Token=\\\"<redacted>\\\" x=1", Redactor.redact("Token=\\\"$dummy a b\\\" x=1"))
    }

    @Test
    fun urlEncodedNamesAndSeparatorsAreMasked() {
        for (text in listOf("api%5Fkey=$dummy", "API%5fKEY=$dummy", "ApiKey%3D$dummy", "?Token%3d$dummy&a=1")) {
            val out = Redactor.redact(text)
            assertFalse(dummy in out, "$text -> $out")
        }
    }

    @Test
    fun nearMissesAreKeptAndOverMaskingIsTheSafeFailure() {
        assertEquals("secretary: x", Redactor.redact("secretary: x"))
        assertEquals("MaxTokenCount: 5", Redactor.redact("MaxTokenCount: 5"))
        assertEquals("Upw=<redacted>", Redactor.redact("Upw=1"))
    }

    @Test
    fun aHugeInputWithNoClosingQuoteFinishesQuickly() {
        val filler = "a\"b ".repeat(25_000)
        val inputs =
            listOf(
                "{\"AccessToken\":\"" + "x".repeat(100_000),
                "{\"" + "k".repeat(100_000),
                "Token=\"" + "y".repeat(100_000),
                filler,
                "\\\"" + "z".repeat(100_000),
            )
        val start = TimeSource.Monotonic.markNow()
        for (input in inputs) Redactor.redact(input)
        // Linear, but about 1 s per 100 KB on Kotlin/Native; a backtracking blow-up would take minutes.
        assertTrue(start.elapsedNow() < 30.seconds, "took ${start.elapsedNow()}")
    }
}
