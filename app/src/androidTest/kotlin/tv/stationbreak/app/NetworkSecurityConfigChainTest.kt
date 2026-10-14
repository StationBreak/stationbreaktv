// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.net.http.X509TrustManagerExtensions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Spec 03, Unit 4 (S0.3.5): on the API 25 emulator, the platform trust path that honours the app's
 * `network_security_config` accepts a chain that ends at ISRG Root X2 without the X1 cross-sign, and rejects a chain
 * from an unknown root.
 *
 * The platform manager is the one TLS uses: `TrustManagerFactory` initialised with no key store is the app's
 * network-security-config-aware manager (API 24+), and `X509TrustManagerExtensions` is its public, host-aware entry
 * point. Nothing here builds a `KeyStore` of its own or bypasses the config.
 *
 * Why the chain is the cross-signed Root YE and not a live server leaf: the platform manager checks "now", and it has
 * no way to pin a date. A live leaf lasts 90 days, so a frozen one would fail on its own expiry. The cross-signed
 * Root YE certificate (issued by ISRG Root X2, valid to 2032) is the long-lived part of every chain the owner's server
 * presents and has no clock dependence until then. API 25 does not ship X2 (Android 14 does), so this passes there only
 * through the bundled anchor.
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
    fun chainEndingAtRootX2WithoutTheX1CrossSignValidates() {
        val chain = arrayOf(asset("isrg_root_ye_by_x2"))
        val validated = platformTrust().checkServerTrusted(chain, "ECDSA", host)
        // The platform returns the validated path, with or without the anchor itself depending on the release; either
        // way it ends at, or directly under, ISRG Root X2.
        assertEquals("Root YE", commonName(validated.first().subjectX500Principal))
        val last = validated.last()
        val names = listOf(last.issuerX500Principal, last.subjectX500Principal).map(::commonName)
        val endsAtX2 = "ISRG Root X2" in names
        assertTrue("validated path does not reach ISRG Root X2", endsAtX2)
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
            assertFalse(expected.message.orEmpty().isEmpty())
        }
    }
}
