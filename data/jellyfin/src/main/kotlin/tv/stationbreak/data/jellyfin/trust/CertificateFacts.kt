// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.SpkiPin
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.Date

internal enum class Validity { Valid, Expired, NotYetValid }

/** What the pinning reads off a leaf certificate, and nothing it keeps beyond [CertificateEvidence]. */
internal object CertificateFacts {
    /**
     * The SHA-256 of the SubjectPublicKeyInfo (`PublicKey.getEncoded()` is its X.509 DER). It survives a renewal on the
     * same key.
     */
    fun spki(certificate: X509Certificate): SpkiPin {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return SpkiPin(sha256.digest(certificate.publicKey.encoded))
    }

    /**
     * True when the leaf verifies under its own public key. Names are not compared: an issuer and a subject with the
     * same name prove nothing, and a self-signed leaf may use any names.
     */
    fun isSelfSigned(certificate: X509Certificate): Boolean =
        try {
            certificate.verify(certificate.publicKey)
            true
        } catch (_: GeneralSecurityException) {
            false
        }

    fun validity(
        certificate: X509Certificate,
        nowMillis: Long,
    ): Validity =
        try {
            certificate.checkValidity(Date(nowMillis))
            Validity.Valid
        } catch (_: CertificateExpiredException) {
            Validity.Expired
        } catch (_: CertificateNotYetValidException) {
            Validity.NotYetValid
        }

    fun evidence(certificate: X509Certificate): CertificateEvidence =
        CertificateEvidence(
            spki = spki(certificate),
            selfSigned = isSelfSigned(certificate),
            notBeforeMillis = certificate.notBefore.time,
            notAfterMillis = certificate.notAfter.time,
        )
}
