// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.ClientIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthHeaderTest {
    private val builder = AuthHeaderBuilder(ClientIdentity("StationBreak", "Living room TV", "0.0.1"))

    @Test
    fun theExactFormWithoutAToken() {
        assertEquals(
            "MediaBrowser Client=\"StationBreak\", Device=\"Living%20room%20TV\", DeviceId=\"dev-1\", " +
                "Version=\"0.0.1\"",
            builder.build("dev-1", null),
        )
    }

    @Test
    fun theTokenComesLast() {
        assertEquals(
            "MediaBrowser Client=\"StationBreak\", Device=\"Living%20room%20TV\", DeviceId=\"dev-1\", " +
                "Version=\"0.0.1\", Token=\"abc123\"",
            builder.build("dev-1", AccessToken("abc123")),
        )
    }
}

/** Angua (Security) R10: nothing in a value can add a field, break out of its quotes or smuggle a control character. */
class HostileHeaderValuesTest {
    private val hostile =
        listOf(
            "TV\r\nX-Injected: 1",
            "TV\nX-Injected: 1",
            "say \"hi\"",
            "a, Token=\"stolen\"",
            "k=v",
            "café テレビ 📺",
            "\u0000\u001f\u007f",
            "back\\slash",
        )

    @Test
    fun everyHostileValueYieldsOneWellFormedPercentEncodedHeader() {
        for (value in hostile) {
            val header = AuthHeaderBuilder(ClientIdentity(value, value, value)).build(value, null)
            assertTrue(header.all { it.code in 0x20..0x7e }, "printable ASCII only: $value")
            // Exactly four quoted fields, no extra field, and no quote inside a value.
            assertEquals(8, header.count { it == '"' }, value)
            assertEquals(4, Regex("""(\w+)="[A-Za-z0-9%._~-]*"""").findAll(header).count(), value)
            assertEquals(3, header.count { it == ',' }, value)
            assertFalse(", Token=" in header, value)
        }
    }

    @Test
    fun theEncodingIsUtf8PercentEncoding() {
        val header = AuthHeaderBuilder(ClientIdentity("c", "café", "v")).build("d", null)
        assertTrue("Device=\"caf%C3%A9\"" in header, header)
    }

    @Test
    fun aHostileTokenCannotBreakOutEither() {
        val header =
            AuthHeaderBuilder(ClientIdentity("c", "d", "v")).build(
                "id",
                tv.stationbreak.core.model
                    .AccessToken("a\"b, X=\r\n"),
            )
        assertTrue(header.endsWith("Token=\"a%22b%2C%20X%3D%0D%0A\""), header)
    }
}
