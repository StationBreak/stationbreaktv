// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.app.net.AndroidSystemTrust
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.model.ClientIdentity
import tv.stationbreak.core.model.PinKind
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.JellyfinMediaServer
import tv.stationbreak.data.jellyfin.trust.PinnedHttpClients
import tv.stationbreak.data.jellyfin.trust.SystemTrust
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * #223 on the API 25 emulator: Conscrypt's certificates, Android's PKIX provider and the Network Security Config's
 * list of system CAs, which the JVM tests can't reach. A P-256 chain from a private CA is offered its CA, and a pin on
 * that CA's key is accepted by a fresh handshake; the system-CA list a CA pin is checked against is the real one. (A
 * leaf renewed on a new key is covered on the JVM, AuthorityPinTlsTest.)
 */
@RunWith(AndroidJUnit4::class)
class AuthorityPinOnApi25Test {
    private val servers = mutableListOf<MockWebServer>()
    private val now = System.currentTimeMillis()
    private val day = TimeUnit.DAYS.toMillis(1)

    @After
    fun tearDown() = servers.forEach { it.close() }

    private fun ca(
        name: String,
        intermediates: Int,
        signedBy: HeldCertificate? = null,
    ): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(name)
            .certificateAuthority(intermediates)
            .validityInterval(now - day, now + YEARS * day)
            .apply { signedBy?.let(::signedBy) }
            .build()

    private fun leaf(issuer: HeldCertificate): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(NAS)
            .addSubjectAlternativeName(NAS)
            .validityInterval(now - day, now + MONTH * day)
            .signedBy(issuer)
            .build()

    private val root = ca("Home Lab Root", intermediates = 1)
    private val intermediate = ca("Home Lab Intermediate", intermediates = 0, signedBy = root)

    private fun serving(
        cert: HeldCertificate,
        vararg chain: HeldCertificate,
    ): MockWebServer {
        val tls =
            HandshakeCertificates
                .Builder()
                .heldCertificate(cert, *chain.map { it.certificate }.toTypedArray())
                .build()
        val server = MockWebServer()
        server.useHttps(tls.sslSocketFactory())
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse
                        .Builder()
                        .code(OK)
                        .body(JELLYFIN_BODY)
                        .build()
            }
        server.start(InetAddress.getByName(LOOPBACK), 0)
        servers += server
        return server
    }

    private fun clients(): PinnedHttpClients {
        val dns =
            Dns { name ->
                if (name != NAS) throw UnknownHostException(name)
                listOf(InetAddress.getByName(LOOPBACK))
            }
        return PinnedHttpClients(AndroidSystemTrust(), OkHttpClient.Builder().dns(dns).build())
    }

    private fun adapter(clients: PinnedHttpClients) =
        JellyfinMediaServer(
            clients,
            clients,
            ClientIdentity("StationBreak", "Authority pin test", "0.0.0"),
            DeviceIdProvider { "authority-pin-device" },
            AdapterLog.NONE,
        )

    @Test
    fun theSystemCaListIsTheNetworkSecurityConfigsAndHoldsTheBundledRoot() {
        val issuers = SystemTrust.defaultX509TrustManager().acceptedIssuers
        assertTrue("no system CAs: no CA pin could ever be offered safely", issuers.isNotEmpty())
        assertTrue(issuers.any { "ISRG Root X2" in it.subjectX500Principal.name })
    }

    @Test
    fun aPrivateCaChainIsOfferedItsAuthorityAndAPinOnItIsAcceptedByAndroidsOwnTls() =
        runBlocking {
            val clients = clients()
            val server = serving(leaf(intermediate), intermediate, root)
            val input = "https://$NAS:${server.port}"
            val adapter = adapter(clients)

            val refused = adapter.discover(input).errorOrNull()
            val untrusted = refused as? MediaServerError.TlsUntrusted
            val offered = checkNotNull(untrusted?.certificate?.authority) { "no CA offered: $refused" }

            // The confirm's own fresh handshake (Conscrypt, Android's PKIX) must accept the chain under the CA pin.
            assertEquals(null, adapter.trust(input, offered.spki, PinKind.Authority).errorOrNull())
            val pinned = clients.pinnedTo(ServerAddress(Scheme.Https, NAS, server.port))
            assertEquals(PinKind.Authority, pinned?.second)
        }

    private companion object {
        const val NAS = "nas.test"
        const val LOOPBACK = "127.0.0.1"
        const val OK = 200
        const val YEARS = 3650L
        const val MONTH = 30L
        const val JELLYFIN_BODY =
            "{\"ServerName\":\"Home\",\"Version\":\"12.2.1\",\"Id\":\"0123abcd\",\"ProductName\":\"Jellyfin Server\"," +
                "\"StartupWizardCompleted\":true,\"OperatingSystem\":\"\"}"
    }
}
