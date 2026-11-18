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

    /**
     * Conscrypt on Android. The API 25 socket path reports BoringSSL's `WRONG_VERSION_NUMBER` (the record header
     * isn't a TLS one) inside an `SSLHandshakeException("Handshake failed")`, so the cause chain is read.
     * "Unable to parse TLS packet header" is the wording of Conscrypt's engine path (Android 10 and later, such as a
     * Fire TV Stick on API 30) for the same finding; it comes from Conscrypt's source and has not been observed on
     * the emulator, so a device smoke test confirms it.
     */
    val CONSCRYPT: List<String> =
        listOf("WRONG_VERSION_NUMBER", "wrong version number", "Unable to parse TLS packet header")

    private val markers: List<String> by lazy { JVM + CONSCRYPT }

    /**
     * A TLS alert the server sent, in the wordings observed: the JDK's "Received fatal alert: …" and BoringSSL's
     * `SSLV3_ALERT_…` / `TLSV1_ALERT_…`. A server that sends one speaks TLS, so a marker in the same chain is not
     * believed. Defence in depth: BoringSSL reports an alert instead of a version error, not beside it, so this is not
     * what decides #850's case.
     */
    private val alert = Regex("Received fatal alert|_ALERT_", RegexOption.IGNORE_CASE)

    /**
     * True when an [SSLException] in [error]'s cause chain carries one of the markers and none reports a TLS alert
     * received from the server.
     */
    fun indicatesPlaintext(error: Throwable): Boolean {
        val messages = error.causeChain().filterIsInstance<SSLException>().mapNotNull { it.message }
        return messages.none { alert.containsMatchIn(it) } &&
            messages.any { message -> markers.any { message.contains(it, ignoreCase = true) } }
    }
}
