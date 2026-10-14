// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import javax.net.ssl.SSLException

/**
 * How a TLS stack words it when the server answers a TLS hello with plain text (Angua (Security) R5). Only this
 * finding lets `discover` fall back from `https` to `http`: any other [SSLException] (a handshake alert, a protocol
 * mismatch, a reset or EOF mid-handshake, something unrecognised) is a TLS error and never downgrades.
 *
 * The two stacks word it differently, so each has its own table, and each is checked by a test on that stack:
 * [JVM] by `PlaintextTlsMarkersTest` and `SchemeResolutionTest` on the JDK, [CONSCRYPT] by the app's
 * `PlaintextTlsMarkerTest` on the API 25 emulator. A marker is added here only after a test has observed it.
 */
object PlaintextTlsMarkers {
    /**
     * JDK 17 (`SSLSocketInputRecord`): the first bytes of the answer aren't a TLS record. Observed for an HTTP/1.1
     * answer (`HTTP/1.1 400`) to a TLS hello, as Kestrel sends it.
     */
    val JVM: List<String> = listOf("Unsupported or unrecognized SSL message")

    /** Conscrypt/BoringSSL on Android 7.1 (API 25). */
    val CONSCRYPT: List<String> = emptyList()

    /** True when an [SSLException] in [error]'s cause chain carries one of the markers. */
    fun indicatesPlaintext(error: Throwable): Boolean {
        val markers = JVM + CONSCRYPT
        return error.causeChain().any { cause ->
            val message = cause.message
            cause is SSLException && message != null && markers.any { message.contains(it, ignoreCase = true) }
        }
    }
}
