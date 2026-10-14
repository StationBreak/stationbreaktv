// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import tv.stationbreak.core.domain.MediaServerError
import java.io.InterruptedIOException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns whatever a call threw into a [MediaServerError] (S0.3.4, Angua (Security) R11).
 *
 * The SDK collapses refused, DNS and timeout failures into one wrapper type and TLS failures into
 * `SecureConnectionException`, so the whole cause chain is read, not just the top. Only exception types and messages
 * are inspected, never copied: the result carries the chain's class names and nothing else, because a message can
 * hold a URL, a host or a token. [CancellationException] is rethrown, never mapped.
 */
internal object ErrorMapper {
    private const val STATUS_UNAUTHORIZED = 401
    private const val STATUS_FORBIDDEN = 403
    private const val STATUS_NOT_FOUND = 404
    private val SERVER_ERRORS = 500..599

    /**
     * [tokenBearing] is true for a call that sent a token: a 401 then means the token is no longer good
     * ([MediaServerError.SessionExpired]) rather than a wrong password. [secure] is true when the call was on TLS:
     * a connection closed before any answer is then a failed handshake.
     */
    fun map(
        error: Throwable,
        tokenBearing: Boolean = false,
        secure: Boolean = false,
    ): MediaServerError {
        if (error is CancellationException) throw error
        val chain = error.causeChain()
        val names = chain.map { it.javaClass.name }
        return sdkStatus(chain, tokenBearing, names)
            ?: lookup(chain, names)
            ?: timeout(chain, names)
            ?: TlsErrors.map(chain, names)
            ?: NetworkErrors.map(chain, names, secure)
            ?: MediaServerError.Unknown(names)
    }

    /** The error for an HTTP status that arrived as a response, not an exception. */
    fun fromStatus(
        code: Int,
        tokenBearing: Boolean = false,
        causes: List<String> = emptyList(),
    ): MediaServerError =
        when (code) {
            STATUS_UNAUTHORIZED -> unauthorized(tokenBearing, causes)
            STATUS_FORBIDDEN -> MediaServerError.HttpForbidden(causes)
            STATUS_NOT_FOUND -> MediaServerError.NotJellyfin(causes)
            in SERVER_ERRORS -> MediaServerError.ServerError(causes)
            else -> MediaServerError.Unknown(causes)
        }

    private fun unauthorized(
        tokenBearing: Boolean,
        causes: List<String>,
    ): MediaServerError {
        val expired = MediaServerError.SessionExpired(causes)
        return if (tokenBearing) expired else MediaServerError.HttpUnauthorized(causes)
    }

    private fun sdkStatus(
        chain: List<Throwable>,
        tokenBearing: Boolean,
        names: List<String>,
    ): MediaServerError? {
        val status = chain.filterIsInstance<InvalidStatusException>().firstOrNull()
        return when {
            status != null -> fromStatus(status.status, tokenBearing, names)
            chain.any { it is InvalidContentException } -> MediaServerError.NotJellyfin(names)
            else -> null
        }
    }

    private fun lookup(
        chain: List<Throwable>,
        names: List<String>,
    ): MediaServerError? = if (chain.any { it is UnknownHostException }) MediaServerError.DnsFailure(names) else null

    private fun timeout(
        chain: List<Throwable>,
        names: List<String>,
    ): MediaServerError? = if (chain.any(::isTimeout)) MediaServerError.Timeout(names) else null

    // SocketTimeoutException is an InterruptedIOException.
    private fun isTimeout(error: Throwable): Boolean =
        error is InterruptedIOException || error is TimeoutException || error.says("timed out", "etimedout")
}
