// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.trust

import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.model.AuthorityEvidence
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.PinKind
import tv.stationbreak.core.model.SpkiPin

/** Why the user is asked, which picks the screen's warning (spec 05, Unit 3). */
internal enum class TrustReason { Untrusted, HostnameMismatch, Expired, NotYetValid, Changed }

/**
 * What the trust screen asks about: a certificate the failed handshake saw, and why it was refused. The pin that is
 * stored if the user agrees is exactly [certificate]'s SPKI, never one fetched again. [toString] shows none of it.
 */
internal data class TrustPrompt(
    val reason: TrustReason,
    val certificate: CertificateEvidence,
    /** What is pinned on a yes: the self-signed leaf, or the private CA that issues the server's certificates. */
    val kind: PinKind = PinKind.Leaf,
) {
    init {
        require(kind == PinKind.Leaf || certificate.authority != null) { "A CA question needs the CA's evidence" }
    }

    // The private CA (#223) when that is what this question is about.
    private val authority: AuthorityEvidence? get() = certificate.authority?.takeIf { kind == PinKind.Authority }

    /** The SPKI that is shown and, on a yes, pinned: the leaf's, or the CA's. */
    val pin: SpkiPin get() = authority?.spki ?: certificate.spki

    /** The validity dates of what is pinned. */
    val notBeforeMillis: Long get() = authority?.notBeforeMillis ?: certificate.notBeforeMillis

    val notAfterMillis: Long get() = authority?.notAfterMillis ?: certificate.notAfterMillis

    override fun toString(): String = "TrustPrompt(reason=$reason, kind=$kind)"
}

/**
 * The question this error puts to the user, or null when there is none. A **self-signed** leaf can be trusted, and so
 * can a **private CA** the adapter found pinnable in the same handshake (#223): one a pin would accept, that is not a
 * public CA and that lives long enough. Any other CA-chained failure is a dead end with its own copy, and a handshake
 * failure or a plaintext answer is never offered a trust option.
 */
internal fun MediaServerError.toTrustPrompt(): TrustPrompt? {
    val found =
        when (this) {
            is MediaServerError.TlsUntrusted -> certificate to TrustReason.Untrusted
            is MediaServerError.TlsHostnameMismatch -> certificate to TrustReason.HostnameMismatch
            is MediaServerError.TlsCertificateExpired -> certificate to TrustReason.Expired
            is MediaServerError.TlsCertificateNotYetValid -> certificate to TrustReason.NotYetValid
            is MediaServerError.TlsCertificateChanged -> certificate to TrustReason.Changed
            else -> null
        }
    val (certificate, reason) = found ?: return null
    return when {
        certificate == null -> null
        certificate.selfSigned -> TrustPrompt(reason, certificate)
        certificate.authority != null -> TrustPrompt(reason, certificate, PinKind.Authority)
        else -> null
    }
}

/** The SPKI SHA-256 as the screen writes it: upper-case hex in groups of four (decision 4). */
internal object Fingerprint {
    private const val GROUP_SIZE = 4
    private const val GROUPS_PER_LINE = 8

    fun groups(pin: SpkiPin): List<String> = pin.hex.chunked(GROUP_SIZE)

    fun text(pin: SpkiPin): String = groups(pin).joinToString(" ")

    /**
     * What a screen reader says: every character on its own, with a comma between groups for a pause. Read as
     * written, a group such as "1118" comes out as a number.
     */
    fun spoken(pin: SpkiPin): String = groups(pin).joinToString(", ") { it.toList().joinToString(" ") }

    /** The same groups in lines of eight, for a screen that reads at ten feet. */
    fun lines(pin: SpkiPin): List<String> = groups(pin).chunked(GROUPS_PER_LINE).map { it.joinToString(" ") }
}
