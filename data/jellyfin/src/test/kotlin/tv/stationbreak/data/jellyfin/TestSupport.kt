// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal const val LOOPBACK = "127.0.0.1"

/** A Jellyfin public-info body that passes every server check. */
internal const val JELLYFIN_BODY =
    "{\"ServerName\":\"Home\",\"Version\":\"12.2.1\",\"Id\":\"0123abcd\",\"ProductName\":\"Jellyfin Server\"," +
        "\"StartupWizardCompleted\":true,\"OperatingSystem\":\"\"}"

/** Records the scheme of every call the client starts, before anything else in the chain can fail it. */
internal class AttemptLog : Interceptor {
    val schemes = CopyOnWriteArrayList<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        schemes += chain.request().url.scheme
        return chain.proceed(chain.request())
    }
}

/** Fails every call of one scheme with [error], as the network would. */
internal class FailingInterceptor(
    private val scheme: String,
    private val error: () -> IOException,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().url.scheme == scheme) throw error()
        return chain.proceed(chain.request())
    }
}

/** Resolves [names] to the loopback address and fails every other name the way a resolver does. */
internal fun loopbackDns(vararg names: String): Dns =
    Dns { hostname ->
        if (hostname in names || hostname == LOOPBACK) {
            listOf(InetAddress.getByName(LOOPBACK))
        } else {
            throw UnknownHostException(hostname)
        }
    }

/** A locally generated CA, a leaf certificate for [hosts] signed by it, and the matching client and server trust. */
internal class TlsFixture(
    hosts: List<String> = listOf("jellyfin.test"),
    leafExpired: Boolean = false,
) {
    private val root =
        HeldCertificate
            .Builder()
            .certificateAuthority(0)
            .commonName("Test Root")
            .duration(1, TimeUnit.DAYS)
            .build()

    private val leaf =
        HeldCertificate
            .Builder()
            .apply {
                hosts.forEach(::addSubjectAlternativeName)
                if (leafExpired) {
                    val now = System.currentTimeMillis()
                    validityInterval(now - TimeUnit.DAYS.toMillis(10), now - TimeUnit.DAYS.toMillis(5))
                } else {
                    duration(1, TimeUnit.DAYS)
                }
            }.signedBy(root)
            .build()

    /** What the server presents. */
    val server: HandshakeCertificates =
        HandshakeCertificates
            .Builder()
            .heldCertificate(leaf, root.certificate)
            .build()

    /** A client that trusts the fixture's root. */
    val clientTrust: HandshakeCertificates =
        HandshakeCertificates
            .Builder()
            .addTrustedCertificate(root.certificate)
            .build()
}

/** Forces a TLS server onto cipher suites and protocols the client under test doesn't offer. */
internal class RestrictedSslSocketFactory(
    private val delegate: SSLSocketFactory,
    private val suites: Array<String>,
    private val protocols: Array<String>,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = suites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(
        socket: Socket,
        host: String?,
        port: Int,
        autoClose: Boolean,
    ): Socket =
        (delegate.createSocket(socket, host, port, autoClose) as SSLSocket).apply {
            enabledCipherSuites = suites
            enabledProtocols = protocols
        }

    override fun createSocket(
        host: String?,
        port: Int,
    ): Socket = throw UnsupportedOperationException()

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = throw UnsupportedOperationException()

    override fun createSocket(
        host: InetAddress?,
        port: Int,
    ): Socket = throw UnsupportedOperationException()

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = throw UnsupportedOperationException()
}

/**
 * A plain-HTTP-only server on the loopback, as a LAN Jellyfin is: it answers a TLS hello with an HTTP error in plain
 * text (what Kestrel does), and answers an HTTP request with [JELLYFIN_BODY]. With [blackhole] it accepts and never
 * answers, to make a client time out.
 */
internal class PlaintextServer(
    blackhole: Boolean = false,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK))
    private val open = CopyOnWriteArrayList<Socket>()
    private val served = AtomicInteger()

    val port: Int get() = serverSocket.localPort

    /** How many plain HTTP requests were answered. */
    val httpRequests: Int get() = served.get()

    private val acceptor =
        Thread {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                open += socket
                if (!blackhole) Thread { handle(socket) }.apply { isDaemon = true }.start()
            }
        }.apply {
            isDaemon = true
            start()
        }

    private fun handle(socket: Socket) {
        runCatching {
            val input = BufferedInputStream(socket.getInputStream())
            input.mark(1)
            val first = input.read()
            input.reset()
            val output = socket.getOutputStream()
            if (first == TLS_HANDSHAKE_RECORD) {
                output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                var tail = 0
                while (tail != END_OF_HEADERS) tail = (tail shl 8) or input.read().also { if (it < 0) error("closed") }
                served.incrementAndGet()
                val body = JELLYFIN_BODY.toByteArray()
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    ).toByteArray() + body,
                )
            }
            output.flush()
        }
        runCatching { socket.close() }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        open.forEach { runCatching { it.close() } }
        acceptor.join(TimeUnit.SECONDS.toMillis(1))
    }

    private companion object {
        const val TLS_HANDSHAKE_RECORD = 0x16
        const val END_OF_HEADERS = 0x0D0A0D0A
    }
}
