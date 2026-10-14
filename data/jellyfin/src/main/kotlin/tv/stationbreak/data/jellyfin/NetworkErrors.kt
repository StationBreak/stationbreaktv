// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import tv.stationbreak.core.domain.MediaServerError
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException

/**
 * The connection half of [ErrorMapper] (DNS and timeouts are mapped before this): unreachable, refused, or closed
 * before any answer. Android words these with errno names (`ECONNREFUSED`), the JDK in English, so both are matched.
 */
internal object NetworkErrors {
    fun map(
        chain: List<Throwable>,
        names: List<String>,
        secure: Boolean,
    ): MediaServerError? =
        when {
            chain.any(::isUnreachable) -> MediaServerError.NetworkUnreachable(names)
            chain.any(::isRefused) -> MediaServerError.ConnectionRefused(names)
            chain.any(::isClosed) -> closedBeforeAnAnswer(names, secure)
            else -> null
        }

    // On TLS, no answer at all means the handshake didn't finish.
    private fun closedBeforeAnAnswer(
        names: List<String>,
        secure: Boolean,
    ): MediaServerError {
        val handshake = MediaServerError.TlsHandshakeFailed(names)
        return if (secure) handshake else MediaServerError.Unknown(names)
    }

    private fun isUnreachable(error: Throwable): Boolean =
        error is NoRouteToHostException ||
            error is PortUnreachableException ||
            error.says("unreachable", "no route", "enetunreach", "ehostunreach")

    private fun isRefused(error: Throwable): Boolean = error is ConnectException || error.says("refused")

    private fun isClosed(error: Throwable): Boolean =
        error is SocketException ||
            error is EOFException ||
            error.says("unexpected end of stream", "connection reset", "broken pipe")
}
