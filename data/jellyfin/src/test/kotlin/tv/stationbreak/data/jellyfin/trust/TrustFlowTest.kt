// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.data.jellyfin.AdapterLog
import tv.stationbreak.data.jellyfin.PlaintextServer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The trust flow through the adapter (spec 05, Unit 3): a failed handshake carries the SPKI, the user's confirmation
 * pins exactly that, and a fresh probe on the pinned client decides whether the pin stays. No credential is involved
 * until sign-in, which comes after.
 */
class TrustFlowTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = closeables.reversed().forEach { it.close() }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables += it }

    private fun evidence(result: ServerResult<*>): CertificateEvidence = assertNotNull(evidenceOf(result))

    @Test
    fun aFailedTestCarriesTheSpkiAndTheSelfSignedFlag() {
        val cert = selfSignedFor(NAS)
        val server = TlsJellyfin(cert).closing()
        val adapter = adapterOn(pinnedClients())

        val result = blocking { adapter.discover("$NAS:${server.port}") }

        assertIs<MediaServerError.TlsUntrusted>(result.errorOrNull())
        val seen = evidence(result)
        assertEquals(CertificateFacts.spki(cert.certificate), seen.spki)
        assertTrue(seen.selfSigned)
        assertEquals(cert.certificate.notAfter.time, seen.notAfterMillis)
        assertTrue(server.requests.isEmpty(), "nothing may be sent over a handshake that failed")
    }

    @Test
    fun trustingTheCapturedSpkiProbesAfreshAndKeepsThePin() {
        val cert = selfSignedFor(NAS)
        val server = TlsJellyfin(cert).closing()
        val clients = pinnedClients()
        val adapter = adapterOn(clients)
        val spki = evidence(blocking { adapter.discover("$NAS:${server.port}") }).spki

        val trusted = blocking { adapter.trust("$NAS:${server.port}", spki) }

        val resolved = assertNotNull(trusted.getOrNull())
        assertEquals(server.address(), resolved.address)
        assertEquals(Scheme.Https, resolved.address.scheme)
        assertEquals(1, server.requests.size)
        assertFalse(
            server.requests
                .single()
                .headers
                .names()
                .any { it.equals("Authorization", true) },
        )
        // The pin stays in force: the next connect works without being told again.
        assertNotNull(blocking { adapter.connect(server.address()) }.getOrNull())
        // And a sign-in-style call from the credentialed client is trusted by the same pin.
        val request = Request.Builder().url("https://$NAS:${server.port}/System/Info/Public").build()
        adapter
            .credentialedHttpClient()
            .newCall(request)
            .execute()
            .use { assertEquals(200, it.code) }
    }

    @Test
    fun aServerThatShowsAnotherCertificateAtConfirmTimeIsRejectedAndLeavesNoPin() {
        val real = selfSignedFor(NAS)
        val server = TlsJellyfin(real).closing()
        val clients = pinnedClients()
        val adapter = adapterOn(clients)
        val shown = SpkiPin(ByteArray(SpkiPin.SIZE) { 0x42 })
        val before = clients.current()

        val trusted = blocking { adapter.trust("$NAS:${server.port}", shown) }

        assertIs<MediaServerError.TlsCertificateChanged>(trusted.errorOrNull())
        assertEquals(CertificateFacts.spki(real.certificate), evidence(trusted).spki)
        assertTrue(server.requests.isEmpty())
        // The pin was taken back, so the client trusts nothing again.
        val again = blocking { adapter.discover("$NAS:${server.port}") }
        assertIs<MediaServerError.TlsUntrusted>(again.errorOrNull())
        assertNotSame(before, clients.current())
    }

    @Test
    fun aCertificateThatRotatesAfterBeingPinnedIsReportedAsChanged() {
        val first = selfSignedFor(NAS)
        val server = TlsJellyfin(first).closing()
        val port = server.port
        val clients = pinnedClients()
        val adapter = adapterOn(clients)
        val spki = evidence(blocking { adapter.discover("$NAS:$port") }).spki
        assertNotNull(blocking { adapter.trust("$NAS:$port", spki) }.getOrNull())

        // The server restarts with a new certificate: its old connections are gone.
        val second = selfSignedFor(NAS)
        server.rotateTo(second)
        clients.current().connectionPool.evictAll()
        val result = blocking { adapter.connect(ServerAddress(Scheme.Https, NAS, port)) }

        assertIs<MediaServerError.TlsCertificateChanged>(result.errorOrNull())
        assertEquals(CertificateFacts.spki(second.certificate), evidence(result).spki)
    }

    @Test
    fun confirmingTheNewCertificateAfterAChangeReplacesThePin() {
        val first = selfSignedFor(NAS)
        val server = TlsJellyfin(first).closing()
        val port = server.port
        val clients = pinnedClients()
        val adapter = adapterOn(clients)
        val spki = evidence(blocking { adapter.discover("$NAS:$port") }).spki
        assertNotNull(blocking { adapter.trust("$NAS:$port", spki) }.getOrNull())
        val second = selfSignedFor(NAS)
        server.rotateTo(second)
        clients.current().connectionPool.evictAll()
        val changed = evidence(blocking { adapter.connect(ServerAddress(Scheme.Https, NAS, port)) })

        assertNotNull(blocking { adapter.trust("$NAS:$port", changed.spki) }.getOrNull())
        assertNotNull(blocking { adapter.connect(ServerAddress(Scheme.Https, NAS, port)) }.getOrNull())
    }

    @Test
    fun aHostnameMismatchIsReportedAndThePinnedCertificateThenWorks() {
        val cert = selfSignedFor(OTHER)
        val server = TlsJellyfin(cert).closing()
        val adapter = adapterOn(pinnedClients())

        val result = blocking { adapter.discover("$NAS:${server.port}") }

        assertIs<MediaServerError.TlsHostnameMismatch>(result.errorOrNull())
        val seen = evidence(result)
        assertTrue(seen.selfSigned)
        assertNotNull(blocking { adapter.trust("$NAS:${server.port}", seen.spki) }.getOrNull())
    }

    @Test
    fun anExpiredSelfSignedCertificateCanBePinned() {
        val day = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val server = TlsJellyfin(selfSignedFor(NAS, notBefore = now - 10 * day, notAfter = now - 5 * day)).closing()
        val adapter = adapterOn(pinnedClients())

        val result = blocking { adapter.discover("$NAS:${server.port}") }

        assertIs<MediaServerError.TlsCertificateExpired>(result.errorOrNull())
        assertTrue(evidence(result).selfSigned)
        assertNotNull(blocking { adapter.trust("$NAS:${server.port}", evidence(result).spki) }.getOrNull())
    }

    @Test
    fun aNotYetValidSelfSignedCertificateIsItsOwnError() {
        val day = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val server = TlsJellyfin(selfSignedFor(NAS, notBefore = now + 5 * day, notAfter = now + 50 * day)).closing()
        val adapter = adapterOn(pinnedClients())

        val result = blocking { adapter.discover("$NAS:${server.port}") }

        assertIs<MediaServerError.TlsCertificateNotYetValid>(result.errorOrNull())
        assertTrue(evidence(result).selfSigned)
    }

    @Test
    fun noPinIsEverMadeOverHttpAndNothingIsProbed() {
        PlaintextServer().use { plain ->
            val clients = pinnedClients()
            val adapter = adapterOn(clients)
            val before = clients.current()

            val result = blocking { adapter.trust("http://127.0.0.1:${plain.port}", SpkiPin(ByteArray(SpkiPin.SIZE))) }

            assertIs<MediaServerError.InvalidAddress>(result.errorOrNull())
            assertEquals(0, plain.httpRequests)
            assertSame(before, clients.current())
        }
    }

    @Test
    fun anInputThatIsNotAnAddressIsInvalid() {
        val adapter = adapterOn(pinnedClients())
        val result = blocking { adapter.trust("not an address", SpkiPin(ByteArray(SpkiPin.SIZE))) }
        assertIs<MediaServerError.InvalidAddress>(result.errorOrNull())
    }

    @Test
    fun aCancelledConfirmationTakesThePinBack() {
        val cert = selfSignedFor(NAS)
        val server = TlsJellyfin(cert).closing()
        val adapter = adapterOn(pinnedClients())
        val spki = CertificateFacts.spki(cert.certificate)

        runBlocking {
            // Runs up to the probe's first suspension, then the caller goes away.
            val job = async(start = CoroutineStart.UNDISPATCHED) { adapter.trust("$NAS:${server.port}", spki) }
            job.cancelAndJoin()
        }

        // Nothing is left pinned: the same server is refused again.
        assertIs<MediaServerError.TlsUntrusted>(blocking { adapter.discover("$NAS:${server.port}") }.errorOrNull())
    }

    @Test
    fun aRejectedConfirmationRestoresThePinThatWasThereNotNone() {
        val cert = selfSignedFor(NAS)
        val server = TlsJellyfin(cert).closing()
        val clients = pinnedClients()
        val adapter = adapterOn(clients)
        val good = CertificateFacts.spki(cert.certificate)
        clients.pin(server.address(), good)

        val result = blocking { adapter.trust("$NAS:${server.port}", SpkiPin(ByteArray(SpkiPin.SIZE) { 0x42 })) }

        assertIs<MediaServerError.TlsCertificateChanged>(result.errorOrNull())
        assertEquals(good, clients.pinFor(server.address()))
        assertNotNull(blocking { adapter.connect(server.address()) }.getOrNull())
    }

    @Test
    fun aRejectedConfirmationWithNoEarlierPinLeavesNone() {
        val server = TlsJellyfin(selfSignedFor(NAS)).closing()
        val clients = pinnedClients()
        blocking { adapterOn(clients).trust("$NAS:${server.port}", SpkiPin(ByteArray(SpkiPin.SIZE))) }
        assertEquals(null, clients.pinFor(server.address()))
    }

    @Test
    fun theCredentialedClientSharesThePinnedTrustAndFollowsEveryPinChange() {
        val cert = selfSignedFor(NAS)
        val server = TlsJellyfin(cert).closing()
        val clients = pinnedClients()
        val adapter = adapterOn(clients)

        val unpinned = adapter.credentialedHttpClient()
        assertSame(clients.current().sslSocketFactory, unpinned.sslSocketFactory)
        assertSame(clients.current().hostnameVerifier, unpinned.hostnameVerifier)

        clients.pin(server.address(), CertificateFacts.spki(cert.certificate))
        val pinned = adapter.credentialedHttpClient()
        assertNotSame(unpinned.sslSocketFactory, pinned.sslSocketFactory)
        assertSame(clients.current().sslSocketFactory, pinned.sslSocketFactory)
        assertSame(clients.current().hostnameVerifier, pinned.hostnameVerifier)
        assertSame(clients.current().connectionPool, pinned.connectionPool)
        assertFalse(pinned.followRedirects)
        // An image or player client derived from it keeps the pinned socket factory and verifier.
        val derived = pinned.newBuilder().build()
        assertSame(pinned.sslSocketFactory, derived.sslSocketFactory)
        assertSame(pinned.hostnameVerifier, derived.hostnameVerifier)
    }

    @Test
    fun noFingerprintReachesTheAdaptersLogOrAnErrorsText() {
        val cert = selfSignedFor(NAS)
        val server = TlsJellyfin(cert).closing()
        val lines = CopyOnWriteArrayList<String>()
        val adapter = adapterOn(pinnedClients(), AdapterLog.redacting { lines += it })

        val failed = blocking { adapter.discover("$NAS:${server.port}") }
        val seen = evidence(failed)
        blocking { adapter.trust("$NAS:${server.port}", seen.spki) }
        blocking { adapter.trust("$NAS:${server.port}", SpkiPin(ByteArray(SpkiPin.SIZE) { 1 })) }

        val everything = lines.joinToString("\n") + failed.toString() + failed.errorOrNull().toString()
        val base64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(seen.spki.toByteArray())
        for (needle in listOf(seen.spki.hex, seen.spki.hex.lowercase(), base64, "sha256/")) {
            assertFalse(needle in everything, "found $needle in:\n$everything")
        }
    }
}
