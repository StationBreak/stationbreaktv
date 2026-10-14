// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Angua (Security) R5 and R6: `http` is a fallback only for a refusal, an unreachable network or a positively
 * identified plaintext answer, never for a TLS problem, a timeout or a DNS failure; and `connect` never downgrades.
 * Every case also checks which schemes were actually attempted.
 */
class SchemeResolutionTest {
    private val log = AttemptLog()
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = closeables.reversed().forEach { it.close() }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables += it }

    private fun plainServer(): MockWebServer =
        MockWebServer()
            .apply {
                enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(JELLYFIN_BODY)
                        .build(),
                )
                start(InetAddress.getByName(LOOPBACK), 0)
            }.closing()

    private fun client(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(log)
            .apply(configure)
            .build()

    private val quick = ProbeTimeouts(connect = 2.seconds, read = 2.seconds, call = 5.seconds)

    private fun discover(
        client: OkHttpClient,
        input: String,
        timeouts: ProbeTimeouts = quick,
    ): ServerResult<ResolvedServer> = runBlocking { adapter(client, timeouts).discover(input) }

    // The adapter clears the base client's interceptors (R9), so the test seams are handed over separately.
    private fun adapter(
        client: OkHttpClient,
        timeouts: ProbeTimeouts = quick,
    ): JellyfinMediaServer {
        val bare = client.newBuilder().apply { interceptors().clear() }.build()
        return JellyfinMediaServer(bare, timeouts, client.interceptors)
    }

    private fun connect(
        client: OkHttpClient,
        address: ServerAddress,
        acknowledgement: CleartextAcknowledgement? = null,
    ): ServerResult<ResolvedServer> = runBlocking { adapter(client).connect(address, acknowledgement) }

    private fun tlsServer(fixture: TlsFixture): MockWebServer =
        MockWebServer()
            .apply {
                useHttps(fixture.server.sslSocketFactory())
                enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(JELLYFIN_BODY)
                        .build(),
                )
                start(InetAddress.getByName(LOOPBACK), 0)
            }.closing()

    private fun trusting(
        fixture: TlsFixture,
        names: List<String> = listOf("jellyfin.test"),
    ): OkHttpClient =
        client {
            sslSocketFactory(fixture.clientTrust.sslSocketFactory(), fixture.clientTrust.trustManager)
            dns(loopbackDns(*names.toTypedArray()))
        }

    private fun assertNoDowngrade(result: ServerResult<ResolvedServer>) {
        assertIs<ServerResult.Failure>(result)
        assertEquals(listOf("https"), log.schemes.toList(), "an http request followed a TLS failure")
    }

    // Fallback to http: exactly these three findings.

    @Test
    fun fallsBackWhenTheConnectionIsRefused() {
        val server = plainServer()
        val client = client { addInterceptor(FailingInterceptor("https") { ConnectException("Connection refused") }) }

        val resolved = discover(client, "$LOOPBACK:${server.port}").getOrNull()!!

        assertEquals(ServerAddress(Scheme.Http, LOOPBACK, server.port, viaFallback = true), resolved.address)
        assertTrue(resolved.resolvedByFallback)
        assertEquals(listOf("https", "http"), log.schemes.toList())
    }

    @Test
    fun fallsBackWhenTheNetworkIsUnreachable() {
        val server = plainServer()
        val unreachable = FailingInterceptor("https") { SocketException("Network is unreachable") }
        val client = client { addInterceptor(unreachable) }

        val resolved = discover(client, "$LOOPBACK:${server.port}").getOrNull()!!

        assertTrue(resolved.resolvedByFallback)
        assertEquals(listOf("https", "http"), log.schemes.toList())
    }

    @Test
    fun fallsBackWhenAPlainHttpServerAnswersTheTlsHelloInPlainText() {
        val lan = PlaintextServer().closing()

        val resolved = discover(client(), "$LOOPBACK:${lan.port}").getOrNull()!!

        assertEquals(ServerAddress(Scheme.Http, LOOPBACK, lan.port, viaFallback = true), resolved.address)
        assertTrue(resolved.resolvedByFallback)
        assertEquals(listOf("https", "http"), log.schemes.toList())
        assertEquals(1, lan.httpRequests, "the TLS hello was answered, then one plain request was made")
    }

    @Test
    fun theJvmWordingOfAPlaintextAnswerIsInTheMarkerTable() {
        // The text this JVM's TLS stack produces for an HTTP answer to a TLS hello (recorded on the PR).
        val lan = PlaintextServer().closing()
        val failure =
            runCatching {
                OkHttpClient()
                    .newCall(
                        okhttp3.Request
                            .Builder()
                            .url("https://$LOOPBACK:${lan.port}/")
                            .build(),
                    ).execute()
            }.exceptionOrNull()!!

        assertIs<SSLException>(failure)
        println("OBSERVED-JVM-PLAINTEXT-ANSWER: ${failure::class.java.name}: ${failure.message}")
        assertTrue(PlaintextTlsMarkers.indicatesPlaintext(failure), "${failure::class.java.name}: ${failure.message}")
    }

    @Test
    fun bothSchemesRefusedReportsTheRefusal() {
        val closedPort = java.net.ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }

        val result = discover(client(), "$LOOPBACK:$closedPort")

        assertIs<MediaServerError.ConnectionRefused>(result.errorOrNull())
        assertEquals(listOf("https", "http"), log.schemes.toList())
    }

    @Test
    fun anHttpsServerIsFoundFirstWithoutAFallback() {
        val fixture = TlsFixture()
        val server = tlsServer(fixture)

        val resolved = discover(trusting(fixture), "jellyfin.test:${server.port}").getOrNull()!!

        assertEquals(ServerAddress(Scheme.Https, "jellyfin.test", server.port), resolved.address)
        assertFalse(resolved.resolvedByFallback)
        assertEquals(listOf("https"), log.schemes.toList())
    }

    // Owner decision (ADR-0014): http reached by fallback always needs the one-time acknowledgement, even on the LAN.

    @Test
    fun aFallbackToAPrivateHostNeedsTheAcknowledgementBeforeAnythingElseIsSent() {
        val lan = PlaintextServer().closing()
        val resolved = discover(client(), "$LOOPBACK:${lan.port}").getOrNull()!!
        val address = resolved.address
        assertTrue(address.cleartextNeedsConfirmation, "a private host reached by fallback")
        val requestsBefore = lan.httpRequests

        assertIs<MediaServerError.CleartextNotConfirmed>(connect(client(), address).errorOrNull())
        assertEquals(requestsBefore, lan.httpRequests, "nothing may be sent without the acknowledgement")

        assertIs<ServerResult.Success<ResolvedServer>>(connect(client(), address, CleartextAcknowledgement(address)))
    }

    @Test
    fun httpTypedForAPrivateHostNeedsNoAcknowledgement() {
        val server = plainServer()
        val address = discover(client(), "http://$LOOPBACK:${server.port}").getOrNull()!!.address
        assertFalse(address.viaFallback)
        assertFalse(address.cleartextNeedsConfirmation)
    }

    // Never a fallback.

    @Test
    fun aTlsFailureSuppressedBehindAnotherAddressesFailureStillBlocksTheFallback() {
        // OkHttp tries a name's addresses in parallel, throws the first failure and attaches the others as suppressed:
        // IPv6 unreachable at once, IPv4 untrusted. The TLS failure is in the error, so there is no http request.
        val client =
            client {
                addInterceptor(
                    FailingInterceptor("https") {
                        ConnectException(
                            "Network is unreachable",
                        ).apply { addSuppressed(SSLHandshakeException("PKIX path building failed")) }
                    },
                )
            }

        val result = discover(client, "$LOOPBACK:8096")

        assertIs<MediaServerError.TlsUntrusted>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun aDualStackNameWhereIpv6IsRefusedAndIpv4IsUntrustedDoesNotFallBack() {
        val server = tlsServer(TlsFixture())
        val twoAddresses = Dns { listOf(InetAddress.getByName("::1"), InetAddress.getByName(LOOPBACK)) }
        // The default trust store doesn't hold the test root, so the IPv4 handshake is untrusted.
        val client = client { dns(twoAddresses) }

        val result = discover(client, "jellyfin.test:${server.port}")

        assertNotNull(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun neverFallsBackOnAnUntrustedCertificate() {
        val server = tlsServer(TlsFixture())
        // The default trust store doesn't hold the test root.
        val client = client { dns(loopbackDns("jellyfin.test")) }

        val result = discover(client, "jellyfin.test:${server.port}")

        assertIs<MediaServerError.TlsUntrusted>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun neverFallsBackOnAHostnameMismatch() {
        val fixture = TlsFixture(hosts = listOf("other.test"))
        val server = tlsServer(fixture)

        val result = discover(trusting(fixture), "jellyfin.test:${server.port}")

        assertIs<MediaServerError.TlsHostnameMismatch>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun neverFallsBackOnAnExpiredCertificate() {
        val fixture = TlsFixture(leafExpired = true)
        val server = tlsServer(fixture)

        val result = discover(trusting(fixture), "jellyfin.test:${server.port}")

        assertIs<MediaServerError.TlsCertificateExpired>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun neverFallsBackOnAHandshakeFailure() {
        // A server whose only cipher suite is one the client doesn't offer: the same failure as a proxy that
        // speaks only what API 25 can't.
        val fixture = TlsFixture()
        val restricted =
            RestrictedSslSocketFactory(
                fixture.server.sslSocketFactory(),
                arrayOf("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA"),
                arrayOf("TLSv1.2"),
            )
        val server =
            MockWebServer()
                .apply {
                    useHttps(restricted)
                    start(InetAddress.getByName(LOOPBACK), 0)
                }.closing()

        val result = discover(trusting(fixture), "jellyfin.test:${server.port}")

        assertIs<MediaServerError.TlsHandshakeFailed>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun neverFallsBackWhenTheConnectionIsResetMidHandshake() {
        val fixture = TlsFixture()
        val server =
            MockWebServer()
                .apply {
                    useHttps(fixture.server.sslSocketFactory())
                    enqueue(MockResponse.Builder().failHandshake().build())
                    start(InetAddress.getByName(LOOPBACK), 0)
                }.closing()

        val result = discover(trusting(fixture), "jellyfin.test:${server.port}")

        assertIs<MediaServerError.TlsHandshakeFailed>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun neverFallsBackOnAnUnrecognisedSslException() {
        val odd = FailingInterceptor("https") { SSLException("a failure nobody has seen before") }
        val client = client { addInterceptor(odd) }

        val result = discover(client, "$LOOPBACK:8096")

        assertIs<MediaServerError.TlsHandshakeFailed>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun neverFallsBackOnATimeout() {
        val client = client { addInterceptor(FailingInterceptor("https") { SocketTimeoutException("timeout") }) }

        val result = discover(client, "$LOOPBACK:8096")

        assertIs<MediaServerError.Timeout>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun neverFallsBackWhenTheServerNeverAnswersTheTlsHello() {
        val silent = PlaintextServer(blackhole = true).closing()
        val timeouts = ProbeTimeouts(connect = 1.seconds, read = 300.milliseconds, call = 3.seconds)

        val result = discover(client(), "$LOOPBACK:${silent.port}", timeouts)

        assertIs<MediaServerError.Timeout>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun neverFallsBackOnADnsFailure() {
        val client = client { dns { throw UnknownHostException("no such host") } }

        val result = discover(client, "nowhere.test:8096")

        assertIs<MediaServerError.DnsFailure>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    // An explicit scheme is honoured exactly.

    @Test
    fun anExplicitHttpIsNeverProbedAsHttps() {
        val server = plainServer()

        val resolved = discover(client(), "http://$LOOPBACK:${server.port}").getOrNull()!!

        assertEquals(Scheme.Http, resolved.address.scheme)
        assertFalse(resolved.resolvedByFallback)
        assertEquals(listOf("http"), log.schemes.toList())
    }

    @Test
    fun anExplicitHttpsIsNeverProbedAsHttpEvenWhenRefused() {
        val client = client { addInterceptor(FailingInterceptor("https") { ConnectException("Connection refused") }) }

        val result = discover(client, "https://$LOOPBACK:8096")

        assertIs<MediaServerError.ConnectionRefused>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun anExplicitHttpsAnsweredInPlainTextIsNotTlsAndNotDowngraded() {
        val lan = PlaintextServer().closing()

        val result = discover(client(), "https://$LOOPBACK:${lan.port}")

        assertIs<MediaServerError.NotTls>(result.errorOrNull())
        assertNoDowngrade(result)
        assertEquals(0, lan.httpRequests)
    }

    // connect never downgrades.

    @Test
    fun connectProbesTheGivenSchemeOnlyAndNeverDowngradesAfterATlsFailure() {
        val fixture = TlsFixture(hosts = listOf("other.test"))
        val server = tlsServer(fixture)

        val result = connect(trusting(fixture), ServerAddress(Scheme.Https, "jellyfin.test", server.port))

        assertIs<MediaServerError.TlsHostnameMismatch>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun connectToAnHttpsAddressWhoseServerIsPlainTextDoesNotFallBack() {
        val lan = PlaintextServer().closing()

        val result = connect(client(), ServerAddress(Scheme.Https, LOOPBACK, lan.port))

        assertIs<MediaServerError.NotTls>(result.errorOrNull())
        assertNoDowngrade(result)
    }

    @Test
    fun connectToAnHttpAddressSucceedsOnHttpWithoutAFallbackFlag() {
        val server = plainServer()

        val resolved = connect(client(), ServerAddress(Scheme.Http, LOOPBACK, server.port)).getOrNull()!!

        assertFalse(resolved.resolvedByFallback)
        assertEquals(listOf("http"), log.schemes.toList())
    }
}
