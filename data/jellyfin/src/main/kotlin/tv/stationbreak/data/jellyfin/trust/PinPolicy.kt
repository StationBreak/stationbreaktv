// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import okhttp3.internal.tls.OkHostnameVerifier
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.SpkiPin
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/** Why the pinning trust manager refused a leaf that system trust had already refused. */
internal enum class Rejection { Untrusted, HostnameMismatch, Expired, NotYetValid, Changed }

internal sealed interface PinVerdict {
    /** The leaf's SPKI is the pin for the exact scheme, host and port. Its dates are not checked. */
    data object Accepted : PinVerdict

    /** Refused, with what the failed handshake saw (see [CertificateEvidence]). */
    data class Rejected(
        val kind: Rejection,
        val evidence: CertificateEvidence,
    ) : PinVerdict
}

/**
 * Thrown from the trust manager when a leaf is neither system-trusted nor pinned. It carries [evidence] across the
 * TLS stack to the error mapper, which turns it into the matching `MediaServerError`. The message is fixed: nothing
 * about the certificate, host or address is in it, because stacks print messages.
 */
internal class CertificateRejectedException(
    val kind: Rejection,
    val evidence: CertificateEvidence,
) : CertificateException("Certificate not accepted")

/**
 * The decisions behind the pinning trust manager and hostname verifier (spec 05, Unit 3), apart from any TLS stack so
 * they can be tested on plain certificates. [pins] is the immutable set in force for one client: a pin change builds a
 * new policy, a new `SSLContext` and a new client.
 */
internal class PinPolicy(
    private val pins: Map<PinKey, SpkiPin>,
    private val nameMatches: (String, X509Certificate) -> Boolean = OkHostnameVerifier::verify,
) {
    /** True when [leaf]'s SPKI equals the pin for exactly [key]. With no [key] nothing matches. */
    fun pinMatches(
        leaf: X509Certificate,
        key: PinKey?,
    ): Boolean {
        val pinned = key?.let(pins::get) ?: return false
        return pinned.matches(CertificateFacts.spki(leaf))
    }

    /**
     * The verdict on [leaf] after system trust refused it, for a connection to [key] under the name [host] (each null
     * when the stack didn't say, which can never match). Order: a pin match is accepted; a different pin for the same
     * place is a change; then expiry, then a name that doesn't fit, then plain "not trusted".
     */
    fun judge(
        leaf: X509Certificate,
        key: PinKey?,
        host: String?,
        nowMillis: Long,
    ): PinVerdict {
        if (pinMatches(leaf, key)) return PinVerdict.Accepted
        val evidence = CertificateFacts.evidence(leaf)
        val kind =
            when {
                key?.let(pins::get) != null -> Rejection.Changed
                CertificateFacts.validity(leaf, nowMillis) == Validity.Expired -> Rejection.Expired
                CertificateFacts.validity(leaf, nowMillis) == Validity.NotYetValid -> Rejection.NotYetValid
                host == null || !nameMatches(host, leaf) -> Rejection.HostnameMismatch
                else -> Rejection.Untrusted
            }
        return PinVerdict.Rejected(kind, evidence)
    }
}
