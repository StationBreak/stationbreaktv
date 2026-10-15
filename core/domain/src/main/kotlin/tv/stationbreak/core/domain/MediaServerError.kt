// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.ServerVersion

/**
 * Everything that can go wrong talking to a media server, as a closed set the UI can explain (S0.3.4).
 *
 * No variant carries a URL, hostname, header, token or [Throwable] (Angua (Security) R11): a raw exception from an
 * SDK or OkHttp can hold a URL in its message and must not cross the port. The caller already knows which host it
 * asked about. [causes] is the only diagnostic data: the class names of the cause chain, outermost first.
 */
sealed interface MediaServerError {
    val causes: List<String>

    data class DnsFailure(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    data class ConnectionRefused(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    data class NetworkUnreachable(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    data class Timeout(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    /**
     * The certificate chain isn't trusted. Never a reason to fall back to `http`. [certificate] is what the failed
     * handshake saw, so the trust screen can offer a pin (only for a self-signed leaf); it is null when the failure
     * wasn't raised by the pinning trust manager.
     */
    data class TlsUntrusted(
        override val causes: List<String> = emptyList(),
        val certificate: CertificateEvidence? = null,
    ) : MediaServerError

    /** The name doesn't match the certificate. [certificate] is set when the leaf is also untrusted (see above). */
    data class TlsHostnameMismatch(
        override val causes: List<String> = emptyList(),
        val certificate: CertificateEvidence? = null,
    ) : MediaServerError

    /**
     * The leaf's validity has ended. A self-signed one can be pinned with a warning; a CA-chained one is a dead end
     * (Angua (Security)). If the clock is wrong the same words apply, see [TlsCertificateNotYetValid].
     */
    data class TlsCertificateExpired(
        override val causes: List<String> = emptyList(),
        val certificate: CertificateEvidence? = null,
    ) : MediaServerError

    /** The leaf's validity hasn't begun: the certificate is new, or this TV's clock is wrong. */
    data class TlsCertificateNotYetValid(
        override val causes: List<String> = emptyList(),
        val certificate: CertificateEvidence? = null,
    ) : MediaServerError

    /**
     * The server presented a different certificate from the one pinned for it. [certificate] is the new one, for the
     * same confirm screen. Accepting it drops the stored session.
     */
    data class TlsCertificateChanged(
        override val causes: List<String> = emptyList(),
        val certificate: CertificateEvidence? = null,
    ) : MediaServerError

    /** The TLS handshake failed for another reason, for example a TLS 1.3-only proxy that API 25 can't speak. */
    data class TlsHandshakeFailed(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    /** The server answered a TLS hello in plain text: the port speaks `http`. The only TLS error that may fall back. */
    data class NotTls(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    /** A bad password or an invalid token on a sign-in. A rejected stored token is [SessionExpired]. */
    data class HttpUnauthorized(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    data class HttpForbidden(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    /** Something answered, but it isn't a Jellyfin server. */
    data class NotJellyfin(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    /** A Jellyfin server older than 12.0 (ADR-0006). */
    data class UnsupportedVersion(
        val found: ServerVersion,
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    /** The server's startup wizard isn't finished. */
    data class ServerNotSetUp(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    /** The server redirected somewhere the adapter won't follow (another host, port or path, or `https` to `http`). */
    data class Redirected(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    /** Cleartext to this address needs a [tv.stationbreak.core.model.CleartextAcknowledgement] for exactly it. */
    data class CleartextNotConfirmed(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    /** The stored token was revoked or has expired. */
    data class SessionExpired(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    /** A stored token belongs to another server than the one answering. */
    data class ServerMismatch(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    data class QuickConnectDisabled(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    data class ServerError(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    data class InvalidAddress(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    /** The server doesn't have what a call asked for (an item, a library). The probe reads a 404 as `NotJellyfin`. */
    data class NotFound(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError

    data class Unknown(
        override val causes: List<String> = emptyList(),
    ) : MediaServerError
}
