// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.net.http.X509TrustManagerExtensions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Spec 03, Unit 4 (S0.3.5): on the API 25 emulator, the platform trust path that honours the app's
 * `network_security_config` accepts chains that end at the bundled Let's Encrypt roots, without the X1 cross-sign,
 * and rejects a chain from an unknown root.
 *
 * The platform manager is the one TLS uses: `TrustManagerFactory` initialised with no key store is the app's
 * network-security-config-aware manager (API 24+), and `X509TrustManagerExtensions` is its public, host-aware entry
 * point. Nothing here builds a `KeyStore` of its own or bypasses the config.
 *
 * Why the chains are frozen CA certificates and not a live server leaf: the platform manager checks "now" and has no
 * way to pin a date, and a live leaf lasts 90 days. The two certificates below are the long-lived parts of the chains
 * Let's Encrypt serves. API 25 ships neither ISRG Root X2 (Android 14 does) nor Root YE, so each only passes through
 * a bundled anchor. Each is accepted in its own test because a certificate that shares its subject and key with a
 * bundled anchor is itself the anchor, so the cross-signed Root YE says nothing about Root X2.
 */
@RunWith(AndroidJUnit4::class)
class NetworkSecurityConfigChainTest {
    private val host = "example.test"

    private fun asset(name: String): X509Certificate =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("chains/$name.pem")
            .use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    private fun commonName(principal: X500Principal): String =
        principal.name
            .split(",")
            .first { it.startsWith("CN=") }
            .removePrefix("CN=")

    private fun platformTrust(): X509TrustManagerExtensions {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return X509TrustManagerExtensions(factory.trustManagers.filterIsInstance<X509TrustManager>().first())
    }

    @Test
    fun rootYeCrossSignedByRootX2ValidatesThroughTheBundledRootYe() {
        val validated = platformTrust().checkServerTrusted(arrayOf(asset("isrg_root_ye_by_x2")), "ECDSA", host)
        assertEquals("Root YE", commonName(validated.first().subjectX500Principal))
    }

    @Test
    fun intermediateIssuedByRootX2ValidatesThroughTheBundledRootX2() {
        val intermediate = asset("intermediate_e8_by_x2")
        // E8 is the last frozen intermediate X2 signed directly (retired for new issuance, valid until 2027-03-12), and
        // the only X2-signed certificate that doesn't share a subject with a bundled anchor. Once it expires nothing
        // valid is left to present, so this test ends there; the X2 anchor is then covered by TrustAnchorsTest's pin.
        if (Date().after(intermediate.notAfter)) return
        val validated = platformTrust().checkServerTrusted(arrayOf(intermediate), "ECDSA", host)
        assertEquals("E8", commonName(validated.first().subjectX500Principal))
        val last = validated.last()
        val names = listOf(last.issuerX500Principal, last.subjectX500Principal).map(::commonName)
        assertTrue("validated path ends at: $names", "ISRG Root X2" in names)
    }

    @Test
    fun chainFromAnUnknownRootIsRejected() {
        // Same subject names as the real chain, but signed by a throwaway key that was discarded: only a match on the
        // anchor's key lets a chain through, never its name.
        val chain = arrayOf(asset("unknown_root_lookalike"))
        try {
            platformTrust().checkServerTrusted(chain, "ECDSA", host)
            fail("a chain from an unknown root was accepted")
        } catch (expected: CertificateException) {
            // Rejected for want of a trust anchor, not for a date or a key-id mismatch.
            val causes = generateSequence<Throwable>(expected) { it.cause }.take(MAX_CAUSES).toList()
            val noAnchor =
                causes.any { it is CertPathValidatorException && it.message.orEmpty().contains("Trust anchor") }
            assertTrue("rejected for another reason: $causes", noAnchor)
        }
    }

    private companion object {
        const val MAX_CAUSES = 8
    }
}
