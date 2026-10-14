// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale

/**
 * Spec 03, Unit 4 (S0.3.5, R16): the certificates bundled in `res/raw` are the intended Let's Encrypt roots, and
 * nothing else. The expected fingerprints are the ones recorded in `app/trust-anchors/README.md`; changing a
 * certificate means changing the constant here and the README, and a reviewer comparing both.
 */
class TrustAnchorsTest {
    private val rawDirectory = File("src/main/res/raw")

    private val expectedFingerprints =
        mapOf(
            "isrg_root_x2" to "69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470",
            "isrg_root_ye" to "E14FFCAD5B0025731006CAA43A121A22D8E9700F4FB9CF852F02A708AA5D5666",
        )

    private fun bundled(): Map<String, ByteArray> =
        rawDirectory
            .listFiles { file -> file.extension == "pem" }
            .orEmpty()
            .associate { it.nameWithoutExtension to it.readBytes() }

    private fun parse(pem: ByteArray): X509Certificate =
        pem.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    private fun sha256Hex(certificate: X509Certificate): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { String.format(Locale.ROOT, "%02X", it) }

    @Test
    fun eachFileHoldsExactlyOneCertificateAndNothingElse() {
        // Android trusts every certificate in a raw file (it reads them all), so a second one appended to a file would
        // become a trusted root without changing the first one's pin.
        bundled().forEach { (name, pem) ->
            val text = String(pem, Charsets.US_ASCII)
            val parsed = pem.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            assertEquals("$name holds more than one certificate", 1, parsed.size)
            assertEquals("$name BEGIN lines", 1, Regex("-----BEGIN ").findAll(text).count())
            assertTrue("$name has text after END", text.substringAfter("-----END CERTIFICATE-----").isBlank())
        }
    }

    @Test
    fun theCrossSignedRootYeIsTheBundledRootYeAndVerifiesUnderRootX2() {
        // Ties Root YE, which no trust store lists yet, to Root X2, which Mozilla's does: same key, signed by X2.
        val crossSign = parse(File("src/androidTest/assets/chains/isrg_root_ye_by_x2.pem").readBytes())
        val rootYe = parse(bundled().getValue("isrg_root_ye"))
        val rootX2 = parse(bundled().getValue("isrg_root_x2"))
        assertEquals(rootYe.publicKey, crossSign.publicKey)
        assertEquals(rootX2.subjectX500Principal, crossSign.issuerX500Principal)
        crossSign.verify(rootX2.publicKey)
    }

    @Test
    fun bundlesExactlyTheTwoIntendedRoots() {
        assertEquals(expectedFingerprints.keys, bundled().keys)
    }

    @Test
    fun everyAnchorMatchesItsPinnedSha256Fingerprint() {
        bundled().forEach { (name, pem) ->
            assertEquals("fingerprint of $name", expectedFingerprints.getValue(name), sha256Hex(parse(pem)))
        }
    }

    @Test
    fun everyAnchorIsASelfSignedCaWithAValidSignature() {
        bundled().forEach { (name, pem) ->
            val certificate = parse(pem)
            assertEquals("$name is self-issued", certificate.subjectX500Principal, certificate.issuerX500Principal)
            // Throws if the signature doesn't verify under the certificate's own key.
            certificate.verify(certificate.publicKey)
            assertNotEquals("$name basicConstraints says CA", -1, certificate.basicConstraints)
            // Key usage bit 5 is keyCertSign.
            assertTrue("$name may sign certificates", certificate.keyUsage?.get(5) == true)
        }
    }

    @Test
    fun everyAnchorIsValidNowAndForAtLeastAnotherYear() {
        // A root that is about to expire fails the build, not a user. The year of runway is the refresh warning
        // (CONTRIBUTING.md, "Bundled Let's Encrypt roots").
        val now = Date()
        val inAYear = Date(now.time + RUNWAY_MILLIS)
        bundled().forEach { (name, pem) ->
            val certificate = parse(pem)
            certificate.checkValidity(now)
            assertTrue("$name expires within a year: ${certificate.notAfter}", certificate.notAfter.after(inAYear))
        }
    }

    private companion object {
        const val RUNWAY_MILLIS = 365L * 24 * 60 * 60 * 1000
    }
}
