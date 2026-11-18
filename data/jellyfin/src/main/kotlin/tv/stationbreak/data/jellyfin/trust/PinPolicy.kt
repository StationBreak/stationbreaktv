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
    /** CA pins (#223): a private certificate authority trusted for exactly one scheme, host and port. */
    private val authorities: Map<PinKey, SpkiPin> = emptyMap(),
    /** The CAs the system trusts, so a chain that reaches one is never offered a CA pin. */
    private val systemIssuers: () -> List<X509Certificate> = { emptyList() },
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
        chain: List<X509Certificate>,
        key: PinKey?,
        host: String?,
        nowMillis: Long,
    ): PinVerdict {
        val leaf = chain.first()
        if (pinMatches(leaf, key) || authorityAccepts(chain, key, host, nowMillis)) return PinVerdict.Accepted
        val evidence = evidenceOf(chain, host, nowMillis)
        val kind =
            when {
                key?.let(pins::get) != null || changedAuthority(chain, key) -> Rejection.Changed
                CertificateFacts.validity(leaf, nowMillis) == Validity.Expired -> Rejection.Expired
                CertificateFacts.validity(leaf, nowMillis) == Validity.NotYetValid -> Rejection.NotYetValid
                host == null || !nameMatches(host, leaf) -> Rejection.HostnameMismatch
                else -> Rejection.Untrusted
            }
        return PinVerdict.Rejected(kind, evidence)
    }

    // Under a CA pin, "changed" only when the path no longer reaches the pinned CA. A renewal that went wrong (an
    // expired leaf, a wrong name) under the same CA says what it is, not "someone may be intercepting".
    private fun changedAuthority(
        chain: List<X509Certificate>,
        key: PinKey?,
    ): Boolean {
        val pinned = key?.let(authorities::get) ?: return false
        return AuthorityChain.path(chain).drop(1).none { pinned.matches(CertificateFacts.spki(it)) }
    }

    // A CA pin for exactly [key] accepts the chain only through [AuthorityChain]'s checks, the leaf's name included.
    private fun authorityAccepts(
        chain: List<X509Certificate>,
        key: PinKey?,
        host: String?,
        nowMillis: Long,
    ): Boolean {
        val pinned = key?.let(authorities::get) ?: return false
        return host != null && AuthorityChain.accepts(chain, pinned, host, nowMillis)
    }

    // What the failed handshake saw, with the private CA that could be pinned instead of the leaf, if any (#223).
    private fun evidenceOf(
        chain: List<X509Certificate>,
        host: String?,
        nowMillis: Long,
    ): CertificateEvidence {
        val leafOnly = CertificateFacts.evidence(chain.first())
        if (host == null || leafOnly.selfSigned) return leafOnly
        val issuers = runCatching(systemIssuers).getOrDefault(emptyList())
        return when (val offer = AuthorityChain.candidate(chain, host, nowMillis, issuers)) {
            AuthorityChain.Candidate.None -> leafOnly
            AuthorityChain.Candidate.TooShortLived -> leafOnly.copy(authorityTooShortLived = true)
            is AuthorityChain.Candidate.Pinnable -> leafOnly.copy(authority = offer.evidence)
        }
    }
}
