// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import okhttp3.internal.tls.OkHostnameVerifier
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

/**
 * The pinned client's hostname check (spec 05, Unit 3). OkHttp's own verifier runs first. A name it refuses is
 * tolerated only when the peer leaf's SPKI is the pin for exactly this scheme, host and port: that is how a pinned
 * self-signed certificate for `192.168.1.5` or an old hostname keeps working. It is never `HostnameVerifier { true }`.
 */
internal class PinnedHostnameVerifier(
    private val policy: PinPolicy,
    private val standard: HostnameVerifier = OkHostnameVerifier,
) : HostnameVerifier {
    override fun verify(
        hostname: String?,
        session: SSLSession?,
    ): Boolean {
        if (hostname == null || session == null) return false
        return standard.verify(hostname, session) || pinned(hostname, session)
    }

    private fun pinned(
        hostname: String,
        session: SSLSession,
    ): Boolean {
        val leaf = leafOf(session) ?: return false
        val port = session.peerPort
        return port in 1..MAX_PORT && policy.pinMatches(leaf, PinKey.https(hostname, port))
    }

    private fun leafOf(session: SSLSession): X509Certificate? =
        try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: SSLPeerUnverifiedException) {
            null
        }

    private companion object {
        const val MAX_PORT = 65_535
    }
}
