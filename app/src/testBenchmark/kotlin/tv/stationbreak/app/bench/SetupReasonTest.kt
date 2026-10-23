// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError

class SetupReasonTest : SetupFixture() {
    @Test
    fun reasonCodesAreShortFixedWords() {
        val errors =
            listOf(
                MediaServerError.DnsFailure(),
                MediaServerError.TlsUntrusted(),
                MediaServerError.NotJellyfin(),
                MediaServerError.CleartextNotConfirmed(),
                MediaServerError.QuickConnectDisabled(),
                MediaServerError.HttpUnauthorized(),
                MediaServerError.ServerError(),
            )
        assertEquals(
            listOf("unreachable", "tls", "unsupported-server", "cleartext", "qc-disabled", "refused", "server-error"),
            errors.map(SetupReason::of),
        )
    }
}
