// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.AdapterTuning
import tv.stationbreak.data.jellyfin.JELLYFIN_BODY
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import tv.stationbreak.data.jellyfin.LOOPBACK
import tv.stationbreak.data.jellyfin.ProbeTimeouts
import tv.stationbreak.data.jellyfin.TEST_DEVICE_ID
import tv.stationbreak.data.jellyfin.TEST_IDENTITY
import tv.stationbreak.data.jellyfin.loopbackDns
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import kotlin.time.Duration.Companion.seconds

internal const val NAS = "nas.test"
internal const val OTHER = "other.test"

/** A self-signed leaf for [names] (the first is also the common name), valid for a month unless told otherwise. */
internal fun selfSignedFor(
    vararg names: String,
    notBefore: Long = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1),
    notAfter: Long = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30),
): HeldCertificate =
    HeldCertificate
        .Builder()
        .commonName(names.first())
        .apply { names.forEach(::addSubjectAlternativeName) }
        .validityInterval(notBefore, notAfter)
        .build()

/** Hands each new TLS handshake to whichever certificate is current, so a server can rotate without rebinding. */
internal class RotatingSocketFactory(
    @Volatile var current: SSLSocketFactory,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = current.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = current.supportedCipherSuites

    override fun createSocket(
        socket: Socket,
        host: String?,
        port: Int,
        autoClose: Boolean,
    ): Socket = current.createSocket(socket, host, port, autoClose)

    override fun createSocket(
        host: String?,
        port: Int,
    ): Socket = current.createSocket(host, port)

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = current.createSocket(host, port, localHost, localPort)

    override fun createSocket(
        host: InetAddress?,
        port: Int,
    ): Socket = current.createSocket(host, port)

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = current.createSocket(address, port, localAddress, localPort)
}

private fun socketFactoryFor(cert: HeldCertificate): SSLSocketFactory =
    HandshakeCertificates
        .Builder()
        .heldCertificate(cert)
        .build()
        .sslSocketFactory()

/** A TLS Jellyfin on the loopback presenting a certificate, that records its requests and can redirect them. */
internal class TlsJellyfin(
    cert: HeldCertificate,
) : AutoCloseable {
    private val server = MockWebServer()
    private val factory = RotatingSocketFactory(socketFactoryFor(cert))
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile
    var redirectTo: String? = null

    /** How long the server waits before it answers: a request that is in flight while the test acts. */
    @Volatile
    var answerDelayMillis = 0L

    init {
        server.useHttps(factory)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val target = redirectTo
                    return if (target != null) {
                        MockResponse
                            .Builder()
                            .code(302)
                            .addHeader("Location", target)
                            .build()
                    } else {
                        MockResponse
                            .Builder()
                            .code(200)
                            .headersDelay(answerDelayMillis, TimeUnit.MILLISECONDS)
                            .body(JELLYFIN_BODY)
                            .build()
                    }
                }
            }
        server.start(InetAddress.getByName(LOOPBACK), 0)
    }

    val port: Int get() = server.port

    fun address(host: String = NAS) = ServerAddress(Scheme.Https, host, port)

    /** From the next handshake on, the server presents [cert]. Connections already open keep what they negotiated. */
    fun rotateTo(cert: HeldCertificate) {
        factory.current = socketFactoryFor(cert)
    }

    override fun close() = server.close()
}

/** The pinned clients on the JVM's own trust (which holds no test certificate), with the test names on loopback. */
internal fun pinnedClients(system: SystemTrust = SystemTrust.jvmDefault()): PinnedHttpClients =
    PinnedHttpClients(system, OkHttpClient.Builder().dns(loopbackDns(NAS, OTHER)).build())

internal val QUICK = AdapterTuning(ProbeTimeouts(connect = 2.seconds, read = 2.seconds, call = 5.seconds))

internal fun adapterOn(
    clients: PinnedHttpClients,
    log: AdapterLog = AdapterLog.NONE,
): JellyfinMediaServer {
    val deviceIds = DeviceIdProvider { TEST_DEVICE_ID }
    return JellyfinMediaServer(clients, TEST_IDENTITY, deviceIds, log, QUICK, clients)
}

internal fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

/** The evidence of a failed result, which the test expects to carry one. */
internal fun evidenceOf(result: ServerResult<*>): CertificateEvidence? =
    when (val error = (result as? ServerResult.Failure)?.error) {
        is MediaServerError.TlsUntrusted -> error.certificate
        is MediaServerError.TlsHostnameMismatch -> error.certificate
        is MediaServerError.TlsCertificateExpired -> error.certificate
        is MediaServerError.TlsCertificateNotYetValid -> error.certificate
        is MediaServerError.TlsCertificateChanged -> error.certificate
        else -> null
    }
