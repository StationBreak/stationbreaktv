// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * The platform's trust, asked about one chain for one host: on Android the Network Security Config's trust (the system
 * store plus the bundled ISRG roots) through `X509TrustManagerExtensions.checkServerTrusted(chain, authType, host)`.
 * Throws [CertificateException] when the chain isn't trusted. The app supplies the Android one; [jvmDefault] is for the
 * JVM and its tests.
 */
fun interface SystemTrust {
    @Throws(CertificateException::class)
    fun check(
        chain: Array<X509Certificate>,
        authType: String,
        host: String,
    )

    companion object {
        /** The default JVM trust store (`TrustManagerFactory.init(null)`). */
        fun jvmDefault(): SystemTrust {
            val manager = defaultX509TrustManager()
            return SystemTrust { chain, authType, _ -> manager.checkServerTrusted(chain, authType) }
        }

        /** The platform's default [X509TrustManager]: what `TrustManagerFactory.init(null)` yields. */
        fun defaultX509TrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}

/**
 * The trust manager of the pinned client (spec 05, Unit 3, Angua (Security)). A chain is accepted when the system
 * trusts it for the host. Only if it doesn't is the leaf compared with the pin for the exact scheme, host and port
 * being connected to, read from the handshake's own session, never from "the current server". A refusal throws
 * [CertificateRejectedException], which carries what the handshake saw so the user can be asked, and nothing is
 * fetched again.
 *
 * It extends [X509ExtendedTrustManager] so the stack passes the socket or engine, which is where the host and port
 * come from. The plain two-argument check, which has no host, always refuses. It is never a trust-all: there is no
 * path that returns without either system trust or a pin match. Client certificates are never accepted.
 */
internal class PinningTrustManager(
    private val system: SystemTrust,
    private val policy: PinPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(
        chain: Array<X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = verify(chain, authType, (socket as? SSLSocket)?.handshakeSession)

    override fun checkServerTrusted(
        chain: Array<X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = verify(chain, authType, engine?.handshakeSession)

    override fun checkServerTrusted(
        chain: Array<X509Certificate>?,
        authType: String?,
    ): Unit = throw CertificateException("The host is unknown, so nothing can be trusted")

    private fun verify(
        chain: Array<X509Certificate>?,
        authType: String?,
        session: SSLSession?,
    ) {
        if (chain.isNullOrEmpty() || authType == null) throw CertificateException("No certificate chain")
        val host = session?.peerHost
        val port = session?.peerPort ?: -1
        try {
            system.check(chain, authType, host.orEmpty())
            return
        } catch (_: CertificateException) {
            // Not trusted by the system: only an exact pin can still accept it.
        }
        val key = if (host != null && port in VALID_PORTS) PinKey.https(host, port) else null
        when (val verdict = policy.judge(chain[0], key, host, clock())) {
            PinVerdict.Accepted -> Unit
            is PinVerdict.Rejected -> throw CertificateRejectedException(verdict.kind, verdict.evidence)
        }
    }

    override fun checkClientTrusted(
        chain: Array<X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ): Unit = throw CertificateException("Client certificates are not accepted")

    override fun checkClientTrusted(
        chain: Array<X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ): Unit = throw CertificateException("Client certificates are not accepted")

    override fun checkClientTrusted(
        chain: Array<X509Certificate>?,
        authType: String?,
    ): Unit = throw CertificateException("Client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private companion object {
        val VALID_PORTS = 1..65_535
    }
}
