// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HeldCertificate
import tv.stationbreak.core.model.PinKind
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #223 over real TLS: a server whose certificates a private CA issues, pinned by that CA's key. */
class AuthorityPinTlsTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private val now = System.currentTimeMillis()
    private val day = TimeUnit.DAYS.toMillis(1)

    @AfterTest
    fun tearDown() = closeables.reversed().forEach { it.close() }

    private fun ca(
        name: String,
        intermediates: Int,
        signedBy: HeldCertificate? = null,
    ): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(name)
            .certificateAuthority(intermediates)
            .validityInterval(now - day, now + 3650 * day)
            .apply { signedBy?.let(::signedBy) }
            .build()

    private fun leaf(
        issuer: HeldCertificate,
        name: String = NAS,
    ): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(name)
            .addSubjectAlternativeName(name)
            .validityInterval(now - day, now + 30 * day)
            .signedBy(issuer)
            .build()

    private val root = ca("Home Lab Root", intermediates = 1)
    private val intermediate = ca("Home Lab Intermediate", intermediates = 0, signedBy = root)

    private fun serving(
        cert: HeldCertificate,
        vararg chain: HeldCertificate,
    ) = TlsJellyfin(cert, chain.toList()).also { closeables += it }

    private fun get(
        client: OkHttpClient,
        port: Int,
        host: String = NAS,
    ): Int {
        val request = Request.Builder().url("https://$host:$port/System/Info/Public").build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun rejection(block: () -> Unit): CertificateRejectedException? =
        generateSequence<Throwable>(assertFailsWith<IOException> { block() }) { it.cause }
            .filterIsInstance<CertificateRejectedException>()
            .firstOrNull()

    @Test
    fun anUnpinnedPrivateCaServerIsRefusedAndOffersItsAuthority() {
        val server = serving(leaf(intermediate), intermediate, root)
        val rejected = assertNotNull(rejection { get(pinnedClients().current(), server.port) })
        assertEquals(Rejection.Untrusted, rejected.kind)
        val offered = assertNotNull(rejected.evidence.authority)
        assertTrue(offered.spki.matches(CertificateFacts.spki(root.certificate)))
    }

    @Test
    fun aPinnedAuthorityIsTrustedAndSurvivesALeafRenewedOnANewKey() {
        val server = serving(leaf(intermediate), intermediate, root)
        val clients = pinnedClients()
        clients.pin(server.address(), CertificateFacts.spki(root.certificate), PinKind.Authority)
        assertEquals(200, get(clients.current(), server.port))

        server.rotateTo(leaf(intermediate), listOf(intermediate, root))
        clients.current().connectionPool.evictAll()
        assertEquals(200, get(clients.current(), server.port))
    }

    @Test
    fun aLeafFromAnotherAuthorityIsAChange() {
        val server = serving(leaf(intermediate), intermediate, root)
        val clients = pinnedClients()
        clients.pin(server.address(), CertificateFacts.spki(root.certificate), PinKind.Authority)
        val otherRoot = ca("Someone Else", intermediates = 0)
        server.rotateTo(leaf(otherRoot), listOf(otherRoot))
        clients.current().connectionPool.evictAll()

        assertEquals(Rejection.Changed, assertNotNull(rejection { get(clients.current(), server.port) }).kind)
    }

    @Test
    fun anAuthorityPinNeverExcusesALeafIssuedForAnotherName() {
        val server = serving(leaf(intermediate, name = OTHER), intermediate, root)
        val clients = pinnedClients()
        clients.pin(server.address(), CertificateFacts.spki(root.certificate), PinKind.Authority)

        assertNotNull(rejection { get(clients.current(), server.port) })
    }

    @Test
    fun aPinnedAuthorityIsForgottenWithUnpinAndTheNextConnectionIsRefused() {
        val server = serving(leaf(intermediate), intermediate, root)
        val clients = pinnedClients()
        clients.pin(server.address(), CertificateFacts.spki(root.certificate), PinKind.Authority)
        assertEquals(200, get(clients.current(), server.port))

        clients.unpin(server.address())
        assertNull(clients.pinnedTo(server.address()))
        assertNotNull(rejection { get(clients.current(), server.port) })
    }
}
