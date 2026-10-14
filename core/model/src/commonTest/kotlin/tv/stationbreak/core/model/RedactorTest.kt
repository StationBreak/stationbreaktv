// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
