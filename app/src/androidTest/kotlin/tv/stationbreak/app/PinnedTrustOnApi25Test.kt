// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.app.net.AndroidSystemTrust
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import tv.stationbreak.data.jellyfin.trust.PinnedHttpClients
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/**
 * The pinned trust on the API 25 emulator, against local TLS servers (spec 05, Unit 3, Angua (Security)): the real
 * Conscrypt stack, the real `X509TrustManagerExtensions`, and the Network Security Config's trust. Only test
 * certificates are used. Covers: a pin match, a pin mismatch, a cross-host redirect that must not inherit the pin,
 * certificate rotation, clearing a pin (the pool is evicted and nothing is reused), the fresh connect after a confirm,
 * and the credentialed client that images and the player share.
 */
@RunWith(AndroidJUnit4::class)
class PinnedTrustOnApi25Test {
    private val closeables = CopyOnWriteArrayList<AutoCloseable>()

    @After
    fun tearDown() = closeables.reversed().forEach { it.close() }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables += it }

    /** A self-signed leaf for [names], valid for a month unless told otherwise. */
    private fun selfSignedFor(
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
    private class RotatingSocketFactory(
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

    /** A TLS server on the loopback presenting a certificate that records its requests and can redirect them. */
    private inner class TlsServer(
        cert: HeldCertificate,
    ) : AutoCloseable {
        private val server = MockWebServer()
        private val factory = RotatingSocketFactory(socketFactoryFor(cert))
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        @Volatile
        var redirectTo: String? = null

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
                                .body(JELLYFIN_BODY)
                                .build()
                        }
                    }
                }
            server.start(InetAddress.getByName(LOOPBACK), 0)
        }

        val port: Int get() = server.port

        fun address(host: String = NAS) = ServerAddress(Scheme.Https, host, port)

        /** From the next handshake on, the server presents [cert]. Open connections keep what they negotiated. */
        fun rotateTo(cert: HeldCertificate) {
            factory.current = socketFactoryFor(cert)
        }

        override fun close() = server.close()
    }

    private fun clients(): PinnedHttpClients {
        val dns =
            Dns { name ->
                if (name != NAS && name != OTHER) throw UnknownHostException(name)
                listOf(InetAddress.getByName(LOOPBACK))
            }
        return PinnedHttpClients(AndroidSystemTrust(), OkHttpClient.Builder().dns(dns).build())
    }

    private fun adapter(clients: PinnedHttpClients) =
        JellyfinMediaServer(
            clients,
            clients,
            ClientIdentity("StationBreak", "Pinned trust test", "0.0.0"),
            DeviceIdProvider { "pinned-trust-device" },
            AdapterLog.NONE,
        )

    // JUnit's assertNotNull returns nothing; the tests want the value.
    private fun <T : Any> assertNotNull(value: T?): T = checkNotNull(value) { "expected a value" }

    private fun spkiOf(cert: HeldCertificate): SpkiPin =
        SpkiPin(MessageDigest.getInstance("SHA-256").digest(cert.certificate.publicKey.encoded))

    private fun get(
        client: OkHttpClient,
        host: String,
        port: Int,
    ): Int =
        client
            .newCall(Request.Builder().url("https://$host:$port/System/Info/Public").build())
            .execute()
            .use { it.code }

    private fun evidenceOf(result: ServerResult<*>): CertificateEvidence? =
        when (val error = result.errorOrNull()) {
            is MediaServerError.TlsUntrusted -> error.certificate
            is MediaServerError.TlsHostnameMismatch -> error.certificate
            is MediaServerError.TlsCertificateExpired -> error.certificate
            is MediaServerError.TlsCertificateNotYetValid -> error.certificate
            is MediaServerError.TlsCertificateChanged -> error.certificate
            else -> null
        }

    // --- the failed handshake carries the SPKI ---

    @Test
    fun anUnpinnedSelfSignedServerCarriesItsSpkiFromTheHandshake() =
        runBlocking<Unit> {
            val cert = selfSignedFor(NAS)
            val server = TlsServer(cert).closing()
            val result = adapter(clients()).discover("$NAS:${server.port}")

            assertTrue(result.errorOrNull().toString(), result.errorOrNull() is MediaServerError.TlsUntrusted)
            val seen = assertNotNull(evidenceOf(result))
            assertEquals(spkiOf(cert), seen.spki)
            assertTrue(seen.selfSigned)
            assertEquals(cert.certificate.notAfter.time, seen.notAfterMillis)
            assertTrue("nothing may be sent over a refused handshake", server.requests.isEmpty())
        }

    @Test
    fun aNameMismatchIsReportedWithEvidenceAndThePinnedCertificateThenWorks() =
        runBlocking<Unit> {
            val cert = selfSignedFor(OTHER)
            val server = TlsServer(cert).closing()
            val adapter = adapter(clients())

            val refused = adapter.discover("$NAS:${server.port}")
            assertTrue(refused.errorOrNull().toString(), refused.errorOrNull() is MediaServerError.TlsHostnameMismatch)
            val seen = assertNotNull(evidenceOf(refused))

            assertNotNull(adapter.trust("$NAS:${server.port}", seen.spki).getOrNull())
        }

    @Test
    fun anExpiredSelfSignedCertificateIsExpiredAndCanBePinned() =
        runBlocking<Unit> {
            val now = System.currentTimeMillis()
            val ended = now - TimeUnit.DAYS.toMillis(5)
            val cert = selfSignedFor(NAS, notBefore = now - TimeUnit.DAYS.toMillis(10), notAfter = ended)
            val server = TlsServer(cert).closing()
            val adapter = adapter(clients())

            val refused = adapter.discover("$NAS:${server.port}")
            val error = refused.errorOrNull()
            assertTrue(error.toString(), error is MediaServerError.TlsCertificateExpired)
            val seen = assertNotNull(evidenceOf(refused))
            assertTrue(seen.selfSigned)

            assertNotNull(adapter.trust("$NAS:${server.port}", seen.spki).getOrNull())
        }

    // --- pin match and mismatch ---

    @Test
    fun aPinMatchAcceptsTheServerAndTheConfirmProbesAfresh() =
        runBlocking<Unit> {
            val cert = selfSignedFor(NAS)
            val server = TlsServer(cert).closing()
            val clients = clients()
            val adapter = adapter(clients)
            val spki = assertNotNull(evidenceOf(adapter.discover("$NAS:${server.port}"))).spki

            val trusted = adapter.trust("$NAS:${server.port}", spki)

            assertEquals(server.address(), assertNotNull(trusted.getOrNull()).address)
            assertEquals("a fresh probe on the pinned client", 1, server.requests.size)
            assertFalse(
                server.requests
                    .single()
                    .headers
                    .names()
                    .any { it.equals("Authorization", ignoreCase = true) },
            )
            assertNotNull(adapter.connect(server.address()).getOrNull())
            assertEquals(200, get(clients.current(), NAS, server.port))
        }

    @Test
    fun aServerThatShowsAnotherCertificateAtConfirmTimeIsRejectedAndLeavesNoPin() =
        runBlocking<Unit> {
            val cert = selfSignedFor(NAS)
            val server = TlsServer(cert).closing()
            val adapter = adapter(clients())
            val shown = SpkiPin(ByteArray(SpkiPin.SIZE) { 0x42 })

            val trusted = adapter.trust("$NAS:${server.port}", shown)

            val error = trusted.errorOrNull()
            assertTrue(error.toString(), error is MediaServerError.TlsCertificateChanged)
            assertEquals(spkiOf(cert), assertNotNull(evidenceOf(trusted)).spki)
            assertTrue(server.requests.isEmpty())
            assertTrue(adapter.discover("$NAS:${server.port}").errorOrNull() is MediaServerError.TlsUntrusted)
        }

    @Test
    fun aRotatedCertificateOnTheSamePortIsReportedAsChanged() =
        runBlocking<Unit> {
            val first = selfSignedFor(NAS)
            val server = TlsServer(first).closing()
            val port = server.port
            val clients = clients()
            val adapter = adapter(clients)
            assertNotNull(adapter.trust("$NAS:$port", spkiOf(first)).getOrNull())

            // The server restarts with a new certificate: its old connections are gone.
            val second = selfSignedFor(NAS)
            server.rotateTo(second)
            clients.current().connectionPool.evictAll()
            val result = adapter.connect(ServerAddress(Scheme.Https, NAS, port))

            assertTrue(result.errorOrNull().toString(), result.errorOrNull() is MediaServerError.TlsCertificateChanged)
            assertEquals(spkiOf(second), assertNotNull(evidenceOf(result)).spki)
        }

    // --- the pin is for one place ---

    @Test
    fun aPinDoesNotFollowACrossHostRedirect() {
        val cert = selfSignedFor(NAS, OTHER)
        val origin = TlsServer(cert).closing()
        val elsewhere = TlsServer(cert).closing()
        origin.redirectTo = "https://$OTHER:${elsewhere.port}/System/Info/Public"
        val clients = clients()
        clients.pin(origin.address(), spkiOf(cert))
        val following =
            clients
                .current()
                .newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        try {
            get(following, NAS, origin.port)
            fail("the redirect target was trusted: the pin was inherited")
        } catch (expected: IOException) {
            // Refused at the handshake with the other host.
        }
        assertEquals(1, origin.requests.size)
        assertTrue("the redirect target saw a request", elsewhere.requests.isEmpty())
    }

    // --- pin changes ---

    @Test
    fun clearingAPinEvictsThePoolAndTheNextRequestFails() {
        val cert = selfSignedFor(NAS)
        val server = TlsServer(cert).closing()
        val clients = clients()
        clients.pin(server.address(), spkiOf(cert))
        val pinned = clients.current()
        assertEquals(200, get(pinned, NAS, server.port))
        assertTrue("the keep-alive connection should be pooled", pinned.connectionPool.connectionCount() > 0)

        clients.unpin(server.address())

        assertEquals("the old pool must be evicted", 0, pinned.connectionPool.connectionCount())
        try {
            get(clients.current(), NAS, server.port)
            fail("a cleared pin still trusted the server")
        } catch (expected: IOException) {
            // No pool and no TLS session survived the change.
        }
        assertEquals("the new client must not reuse the old connection", 1, server.requests.size)
    }

    @Test
    fun everyPinChangeBuildsANewSslContextAndPool() {
        val cert = selfSignedFor(NAS)
        val server = TlsServer(cert).closing()
        val clients = clients()
        val none = clients.current()
        clients.pin(server.address(), spkiOf(cert))
        val pinned = clients.current()
        clients.unpin(server.address())
        val cleared = clients.current()

        assertNotSame(none.sslSocketFactory, pinned.sslSocketFactory)
        assertNotSame(pinned.sslSocketFactory, cleared.sslSocketFactory)
        assertNotSame(none.connectionPool, pinned.connectionPool)
        assertNotSame(pinned.connectionPool, cleared.connectionPool)
    }

    // --- Media3, images and subtitles ---

    @Test
    fun theCredentialedClientSharesThePinnedSocketFactoryAndVerifier() {
        val cert = selfSignedFor(NAS)
        val server = TlsServer(cert).closing()
        val clients = clients()
        val adapter = adapter(clients)
        clients.pin(server.address(), spkiOf(cert))

        val credentialed = adapter.credentialedHttpClient()

        assertSame(clients.current().sslSocketFactory, credentialed.sslSocketFactory)
        assertSame(clients.current().hostnameVerifier, credentialed.hostnameVerifier)
        assertFalse(credentialed.followRedirects)
        // An image or player client built from it keeps the pinned trust, and reaches the pinned server.
        val derived = credentialed.newBuilder().build()
        assertSame(credentialed.sslSocketFactory, derived.sslSocketFactory)
        assertSame(credentialed.hostnameVerifier, derived.hostnameVerifier)
        assertEquals(200, get(derived, NAS, server.port))
    }

    // --- the system's own trust is still the first word ---

    private fun asset(name: String): X509Certificate =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("chains/$name.pem")
            .use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    @Test
    fun theAndroidSystemTrustStillAcceptsTheBundledRootsAndRejectsAnUnknownRoot() {
        val trust = AndroidSystemTrust()
        trust.check(arrayOf(asset("isrg_root_ye_by_x2")), "ECDSA", "example.test")
        try {
            trust.check(arrayOf(asset("unknown_root_lookalike")), "ECDSA", "example.test")
            fail("a chain from an unknown root was accepted")
        } catch (expected: CertificateException) {
            assertNull(expected.message?.takeIf { it.isEmpty() })
        }
    }

    private companion object {
        const val NAS = "nas.test"
        const val OTHER = "other.test"
        const val LOOPBACK = "127.0.0.1"
        const val JELLYFIN_BODY =
            "{\"ServerName\":\"Home\",\"Version\":\"12.2.1\",\"Id\":\"0123abcd\",\"ProductName\":\"Jellyfin Server\"," +
                "\"StartupWizardCompleted\":true,\"OperatingSystem\":\"\"}"
    }
}
