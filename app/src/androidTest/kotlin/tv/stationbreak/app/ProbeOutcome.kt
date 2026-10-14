// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Spec 03, Unit 4 (R17, D1). The only thing the on-device probe may print: one line with PASS or FAIL, a taxonomy
 * type name and the server's major version. Nothing from an exception message, a URL, a host or a response body
 * ever reaches it: the type comes from the exception's class, and the version from a strict digit match.
 * The names are the adapter taxonomy's (spec 03, Unit 1); swapping this for the adapter's `discover` is a later,
 * optional change.
 */
internal object ProbeOutcome {
    private const val NONE = "none"
    private const val MAX_CAUSE_DEPTH = 16
    private val majorVersion = Regex("""(\d{1,3})\..*""")

    fun success(body: String): String {
        val version = runCatching { JSONObject(body).optString("Version") }.getOrDefault("")
        val major = majorVersion.matchEntire(version)?.groupValues?.get(1)
        return if (major == null) line(passed = false, type = "Unknown", major = NONE) else line(true, "None", major)
    }

    // The probe proves TLS trust, so a plain-HTTP address is a failure that never connects. Only the type is reported:
    // not the scheme, and nothing from the address.
    fun requireHttps(address: String): String? =
        if (address.trimStart().startsWith("https://", ignoreCase = true)) {
            null
        } else {
            line(passed = false, type = "NotTls", major = NONE)
        }

    @Suppress("UNUSED_PARAMETER")
    fun status(code: Int): String = line(passed = false, type = "Unknown", major = NONE)

    fun failure(failure: Throwable): String = line(passed = false, type = typeOf(failure), major = NONE)

    private fun line(
        passed: Boolean,
        type: String,
        major: String,
    ) = "ProbeOutcome result=${if (passed) "PASS" else "FAIL"} type=$type major=$major"

    private fun typeOf(failure: Throwable): String {
        val chain = causes(failure)
        return when {
            chain.any { it is CertificateExpiredException || it is CertificateNotYetValidException } -> {
                "TlsCertificateExpired"
            }

            chain.any { it is CertPathValidatorException || it is CertificateException } -> {
                "TlsUntrusted"
            }

            chain.any { it is SSLPeerUnverifiedException } -> {
                "TlsHostnameMismatch"
            }

            chain.any { it is SSLException } -> {
                if (looksLikePlaintext(chain)) "NotTls" else "TlsHandshakeFailed"
            }

            chain.any { it is NoRouteToHostException } -> {
                "NetworkUnreachable"
            }

            chain.any { it is ConnectException } -> {
                if (unreachable(chain)) "NetworkUnreachable" else "ConnectionRefused"
            }

            chain.any { it is UnknownHostException } -> {
                "DnsFailure"
            }

            chain.any { it is SocketTimeoutException } -> {
                "Timeout"
            }

            else -> {
                "Unknown"
            }
        }
    }

    // Bounded, and never follows a cycle: a hostile cause chain can't hang the probe.
    private fun causes(failure: Throwable): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && seen.size < MAX_CAUSE_DEPTH && seen.none { it === current }) {
            seen += current
            current = current.cause
        }
        return seen
    }

    // The messages are read to classify, never printed.
    private fun looksLikePlaintext(chain: List<Throwable>) =
        chain.any {
            val message = it.message.orEmpty()
            message.contains("Unable to parse TLS packet header") ||
                message.contains("not an SSL/TLS record") ||
                message.contains("WRONG_VERSION_NUMBER") ||
                message.contains("first record does not look like a TLS handshake")
        }

    private fun unreachable(chain: List<Throwable>) = chain.any { it.message.orEmpty().contains("unreachable") }
}
