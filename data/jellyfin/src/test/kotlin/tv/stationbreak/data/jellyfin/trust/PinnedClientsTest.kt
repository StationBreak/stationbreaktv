// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HeldCertificate
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.data.jellyfin.TlsFixture
import java.io.IOException
import java.net.Socket
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The pinned client against real TLS servers on the JVM (spec 05, Unit 3, Angua (Security)): a pin accepts exactly one
 * certificate for exactly one scheme, host and port; every change of pins builds a new client; and nothing weaker than
 * system trust or a pin match ever gets through.
 */
class PinnedClientsTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = closeables.reversed().forEach { it.close() }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables += it }

    private fun serving(cert: HeldCertificate = selfSignedFor(NAS)) = TlsJellyfin(cert).closing()

    private fun get(
        client: OkHttpClient,
        host: String,
        port: Int,
    ): Int {
        val request = Request.Builder().url("https://$host:$port/System/Info/Public").build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun failureOf(block: () -> Unit): Throwable = assertFailsWith<IOException> { block() }

    private fun rejection(error: Throwable): CertificateRejectedException? =
        generateSequence(error) { it.cause }.filterIsInstance<CertificateRejectedException>().firstOrNull()

    // --- pin match and mismatch ---

    @Test
    fun anUnpinnedSelfSignedServerIsRefusedWithWhatTheHandshakeSaw() {
        val cert = selfSignedFor(NAS)
        val server = serving(cert)
        val clients = pinnedClients()
        val failure = failureOf { get(clients.current(), NAS, server.port) }
        val rejected = assertNotNull(rejection(failure))
        assertEquals(Rejection.Untrusted, rejected.kind)
        assertEquals(CertificateFacts.spki(cert.certificate), rejected.evidence.spki)
        assertTrue(server.requests.isEmpty(), "no request may be sent over a refused handshake")
    }

    @Test
    fun aPinMatchAcceptsTheServer() {
        val cert = selfSignedFor(NAS)
        val server = serving(cert)
        val clients = pinnedClients()
        clients.pin(server.address(), CertificateFacts.spki(cert.certificate))
        assertEquals(200, get(clients.current(), NAS, server.port))
    }

    @Test
    fun aPinMismatchRefusesTheServerAsAChange() {
        val cert = selfSignedFor(NAS)
        val server = serving(cert)
        val clients = pinnedClients()
        clients.pin(server.address(), SpkiPin(ByteArray(SpkiPin.SIZE) { 1 }))
        val rejected = assertNotNull(rejection(failureOf { get(clients.current(), NAS, server.port) }))
        assertEquals(Rejection.Changed, rejected.kind)
        assertEquals(CertificateFacts.spki(cert.certificate), rejected.evidence.spki)
    }

    @Test
    fun aRotatedCertificateOnTheSamePortIsACertificateChange() {
        val first = selfSignedFor(NAS)
        val server = TlsJellyfin(first)
        val port = server.port
        val clients = pinnedClients()
        clients.pin(server.address(), CertificateFacts.spki(first.certificate))
        assertEquals(200, get(clients.current(), NAS, port))
        server.close()

        val second = selfSignedFor(NAS)
        TlsJellyfin(second, port).closing()
        val rejected = assertNotNull(rejection(failureOf { get(clients.current(), NAS, port) }))
        assertEquals(Rejection.Changed, rejected.kind)
        assertEquals(CertificateFacts.spki(second.certificate), rejected.evidence.spki)
    }

    // --- the pin is for one place ---

    @Test
    fun aPinDoesNotFollowACrossHostRedirect() {
        val cert = selfSignedFor(NAS, OTHER)
        val origin = serving(cert)
        val elsewhere = serving(cert)
        origin.redirectTo = "https://$OTHER:${elsewhere.port}/System/Info/Public"
        val clients = pinnedClients()
        clients.pin(origin.address(), CertificateFacts.spki(cert.certificate))
        val following =
            clients
                .current()
                .newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        val failure = failureOf { get(following, NAS, origin.port) }

        assertEquals(Rejection.Untrusted, assertNotNull(rejection(failure)).kind)
        assertEquals(1, origin.requests.size)
        assertTrue(elsewhere.requests.isEmpty(), "the redirect target saw a request: the pin was inherited")
    }

    @Test
    fun aPinForOnePortIsNotAPinForAnother() {
        val cert = selfSignedFor(NAS)
        val pinned = serving(cert)
        val other = serving(cert)
        val clients = pinnedClients()
        clients.pin(pinned.address(), CertificateFacts.spki(cert.certificate))
        assertEquals(200, get(clients.current(), NAS, pinned.port))
        failureOf { get(clients.current(), NAS, other.port) }
    }

    @Test
    fun noPinCanBeMadeOverHttp() {
        val clients = pinnedClients()
        val before = clients.current()
        clients.pin(
            tv.stationbreak.core.model
                .ServerAddress(tv.stationbreak.core.model.Scheme.Http, NAS, 8096),
            SpkiPin(ByteArray(SpkiPin.SIZE)),
        )
        assertSame(before, clients.current(), "a pin over http must change nothing")
    }

    // --- hostname ---

    @Test
    fun aNameMismatchOnAnUnpinnedSelfSignedServerIsReportedWithEvidence() {
        val server = serving(selfSignedFor(OTHER))
        val clients = pinnedClients()
        val rejected = assertNotNull(rejection(failureOf { get(clients.current(), NAS, server.port) }))
        assertEquals(Rejection.HostnameMismatch, rejected.kind)
        assertTrue(rejected.evidence.selfSigned)
    }

    @Test
    fun aPinnedCertificateIsAcceptedUnderAnotherName() {
        val cert = selfSignedFor(OTHER)
        val server = serving(cert)
        val clients = pinnedClients()
        clients.pin(server.address(), CertificateFacts.spki(cert.certificate))
        assertEquals(200, get(clients.current(), NAS, server.port))
    }

    @Test
    fun aSystemTrustedChainWithTheWrongNameIsRefusedByTheVerifierAndNeverPinned() {
        // The platform trusts everything here, so only the hostname verifier stands between the name and the leaf.
        val server = serving(selfSignedFor(OTHER))
        val clients = pinnedClients(system = { _, _, _ -> })
        val failure = failureOf { get(clients.current(), NAS, server.port) }
        assertTrue(failure is javax.net.ssl.SSLPeerUnverifiedException, failure.toString())
    }

    @Test
    fun theVerifierToleratesAMismatchOnlyForThePinOfThatExactHostAndPort() {
        val cert = selfSignedFor(OTHER)
        val server = serving(cert)
        val spki = CertificateFacts.spki(cert.certificate)
        // The pin is for the same host on another port and the platform trusts the chain: the name must still fail.
        val clients = pinnedClients(system = { _, _, _ -> })
        clients.pin(
            tv.stationbreak.core.model
                .ServerAddress(tv.stationbreak.core.model.Scheme.Https, NAS, server.port + 1),
            spki,
        )
        val failure = failureOf { get(clients.current(), NAS, server.port) }
        assertTrue(failure is javax.net.ssl.SSLPeerUnverifiedException, failure.toString())
    }

    // --- system trust and dates ---

    @Test
    fun aSystemTrustedChainNeedsNoPin() {
        val fixture = TlsFixture(hosts = listOf(NAS))
        val server =
            mockwebserver3.MockWebServer().apply {
                useHttps(fixture.server.sslSocketFactory())
                enqueue(
                    mockwebserver3.MockResponse
                        .Builder()
                        .code(200)
                        .body("ok")
                        .build(),
                )
                start(java.net.InetAddress.getByName(tv.stationbreak.data.jellyfin.LOOPBACK), 0)
            }
        closeables += server
        val trust = fixture.clientTrust.trustManager
        val clients = pinnedClients(system = { chain, authType, _ -> trust.checkServerTrusted(chain, authType) })
        assertEquals(200, get(clients.current(), NAS, server.port))
    }

    @Test
    fun anExpiredSelfSignedCertificateIsExpiredButCanBePinnedAndThenItsDatesAreNotChecked() {
        val now = System.currentTimeMillis()
        val cert =
            selfSignedFor(NAS, notBefore = now - TimeUnit.DAYS.toMillis(10), notAfter = now - TimeUnit.DAYS.toMillis(5))
        val server = serving(cert)
        val clients = pinnedClients()
        val rejected = assertNotNull(rejection(failureOf { get(clients.current(), NAS, server.port) }))
        assertEquals(Rejection.Expired, rejected.kind)
        assertTrue(rejected.evidence.selfSigned)
        clients.pin(server.address(), rejected.evidence.spki)
        assertEquals(200, get(clients.current(), NAS, server.port))
    }

    @Test
    fun anExpiredCaChainedCertificateIsAnExpiredDeadEnd() {
        val fixture = TlsFixture(hosts = listOf(NAS), leafExpired = true)
        val server =
            mockwebserver3.MockWebServer().apply {
                useHttps(fixture.server.sslSocketFactory())
                start(java.net.InetAddress.getByName(tv.stationbreak.data.jellyfin.LOOPBACK), 0)
            }
        closeables += server
        val trust = fixture.clientTrust.trustManager
        val clients = pinnedClients(system = { chain, authType, _ -> trust.checkServerTrusted(chain, authType) })
        val rejected = assertNotNull(rejection(failureOf { get(clients.current(), NAS, server.port) }))
        assertEquals(Rejection.Expired, rejected.kind)
        // Not self-signed: the trust screen offers nothing here.
        assertFalse(rejected.evidence.selfSigned)
    }

    // --- pin changes build a new client ---

    @Test
    fun everyPinChangeBuildsANewClientPoolAndSslContext() {
        val cert = selfSignedFor(NAS)
        val server = serving(cert)
        val address = server.address()
        val spki = CertificateFacts.spki(cert.certificate)
        val clients = pinnedClients()
        val none = clients.current()

        clients.pin(address, spki)
        val pinned = clients.current()
        assertNotSame(none, pinned)
        assertNotSame(none.sslSocketFactory, pinned.sslSocketFactory)
        assertNotSame(none.connectionPool, pinned.connectionPool)

        clients.pin(address, SpkiPin(ByteArray(SpkiPin.SIZE) { 2 }))
        val replaced = clients.current()
        assertNotSame(pinned, replaced)
        assertNotSame(pinned.sslSocketFactory, replaced.sslSocketFactory)

        clients.unpin(address)
        val cleared = clients.current()
        assertNotSame(replaced, cleared)
        assertNotSame(replaced.sslSocketFactory, cleared.sslSocketFactory)
    }

    @Test
    fun settingTheSamePinsAgainChangesNothing() {
        val clients = pinnedClients()
        val address =
            tv.stationbreak.core.model
                .ServerAddress(tv.stationbreak.core.model.Scheme.Https, NAS, 8920)
        val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 4 })
        clients.setPins(mapOf(address to pin))
        val built = clients.current()
        clients.setPins(mapOf(address to pin))
        assertSame(built, clients.current())
    }

    @Test
    fun clearingAPinEvictsTheOldPoolAndTheNextRequestFails() {
        val cert = selfSignedFor(NAS)
        val server = serving(cert)
        val clients = pinnedClients()
        clients.pin(server.address(), CertificateFacts.spki(cert.certificate))
        val pinned = clients.current()
        assertEquals(200, get(pinned, NAS, server.port))
        assertTrue(pinned.connectionPool.connectionCount() > 0, "the keep-alive connection should be pooled")

        clients.unpin(server.address())

        assertEquals(0, pinned.connectionPool.connectionCount(), "the old pool must be evicted")
        val failure = failureOf { get(clients.current(), NAS, server.port) }
        assertEquals(Rejection.Untrusted, assertNotNull(rejection(failure)).kind)
        assertEquals(1, server.requests.size, "the new client must not reuse the old connection")
    }

    @Test
    fun aReplacedPinRefusesTheCertificateThatWasPinnedBefore() {
        val cert = selfSignedFor(NAS)
        val server = serving(cert)
        val clients = pinnedClients()
        clients.pin(server.address(), CertificateFacts.spki(cert.certificate))
        assertEquals(200, get(clients.current(), NAS, server.port))
        clients.pin(server.address(), SpkiPin(ByteArray(SpkiPin.SIZE) { 5 }))
        val rejected = assertNotNull(rejection(failureOf { get(clients.current(), NAS, server.port) }))
        assertEquals(Rejection.Changed, rejected.kind)
    }

    // --- the trust manager itself never trusts all ---

    @Test
    fun theTrustManagerRefusesWithoutAHostAndRefusesClients() {
        val manager = PinningTrustManager({ _, _, _ -> throw CertificateException("no") }, PinPolicy(emptyMap()))
        val chain = arrayOf(selfSignedFor(NAS).certificate)
        assertFailsWith<CertificateException> { manager.checkServerTrusted(chain, "RSA") }
        assertFailsWith<CertificateException> { manager.checkClientTrusted(chain, "RSA") }
        assertFailsWith<CertificateException> { manager.checkServerTrusted(null, "RSA", null as Socket?) }
        assertFailsWith<CertificateException> { manager.checkServerTrusted(emptyArray(), "RSA", null as SSLEngine?) }
    }

    @Test
    fun aHandshakeRefusedByThePinsIsASslHandshakeFailureCarryingTheRejection() {
        val server = serving(selfSignedFor(NAS))
        val clients = pinnedClients()
        val failure = failureOf { get(clients.current(), NAS, server.port) }
        assertTrue(failure is SSLHandshakeException || failure.cause is SSLHandshakeException, failure.toString())
        assertNull(failure.message?.takeIf { "sha256" in it }, "the message should hold no certificate data")
    }

    @Test
    fun theMapperTurnsARejectionIntoTheMatchingError() {
        val cert = selfSignedFor(NAS)
        val server = serving(cert)
        val clients = pinnedClients()
        val adapter = adapterOn(clients)
        val result = blocking { adapter.connect(server.address()) }
        val error = assertIs<MediaServerError.TlsUntrusted>(result.errorOrNull())
        assertEquals(CertificateFacts.spki(cert.certificate), error.certificate?.spki)
        assertNull(result.getOrNull())
        assertTrue(result is ServerResult.Failure)
        assertFalse(error.toString().contains(error.certificate!!.spki.hex))
    }
}
