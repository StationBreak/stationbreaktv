// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.trust

import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.model.CertificateEvidence
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
)

/**
 * The question this error puts to the user, or null when there is none. Only a **self-signed** leaf can be trusted: a
 * CA-chained one that fails (expired, wrong name, changed) is a dead end with its own copy, and a handshake failure
 * or a plaintext answer is never offered a trust option.
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
    return if (certificate != null && certificate.selfSigned) TrustPrompt(reason, certificate) else null
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
