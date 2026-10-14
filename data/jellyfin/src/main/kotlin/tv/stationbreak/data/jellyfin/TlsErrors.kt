// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import tv.stationbreak.core.domain.MediaServerError
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The TLS half of [ErrorMapper]. A specific certificate finding anywhere in the chain beats the generic handshake
 * exception around it: expired, then hostname, then untrusted, then a plaintext answer, then any other failure.
 */
internal object TlsErrors {
    fun map(
        chain: List<Throwable>,
        names: List<String>,
    ): MediaServerError? =
        when {
            chain.none(::isTlsRelated) -> null

            chain.any(::isExpired) -> MediaServerError.TlsCertificateExpired(names)

            chain.any { it is SSLPeerUnverifiedException } -> MediaServerError.TlsHostnameMismatch(names)

            chain.any(::isUntrusted) -> MediaServerError.TlsUntrusted(names)

            // Plaintext only if every TLS failure in the chain is the plaintext one.
            chain.filter(::isTlsRelated).all(PlaintextTlsMarkers::indicatesPlaintext) -> MediaServerError.NotTls(names)

            else -> MediaServerError.TlsHandshakeFailed(names)
        }

    private fun isTlsRelated(error: Throwable): Boolean =
        error is SSLException ||
            error is SecureConnectionException ||
            error is CertificateException ||
            error is CertPathValidatorException ||
            error is CertPathBuilderException

    private fun isExpired(error: Throwable): Boolean =
        error is CertificateExpiredException ||
            error is CertificateNotYetValidException ||
            (error as? CertPathValidatorException)?.reason in
            listOf(CertPathValidatorException.BasicReason.EXPIRED, CertPathValidatorException.BasicReason.NOT_YET_VALID)

    private fun isUntrusted(error: Throwable): Boolean =
        error is CertPathValidatorException ||
            error is CertPathBuilderException ||
            error is CertificateException ||
            error.javaClass.name == "sun.security.validator.ValidatorException" ||
            error.says("pkix path", "unable to find valid certification path", "trust anchor")
}
