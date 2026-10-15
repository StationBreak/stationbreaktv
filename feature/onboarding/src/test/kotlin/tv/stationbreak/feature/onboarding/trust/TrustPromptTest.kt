// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.model.CertificateEvidence
import tv.stationbreak.core.model.SpkiPin

/** When a TLS error becomes a question for the user, and how the answer's fingerprint is written (spec 05, Unit 3). */
class TrustPromptTest {
    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { it.toByte() })
    private val selfSigned = CertificateEvidence(pin, selfSigned = true, notBeforeMillis = 1L, notAfterMillis = 2L)
    private val chained = selfSigned.copy(selfSigned = false)

    @Test
    fun aSelfSignedCertificateWithEachTrustableErrorIsAQuestion() {
        val expected =
            mapOf(
                MediaServerError.TlsUntrusted(certificate = selfSigned) to TrustReason.Untrusted,
                MediaServerError.TlsHostnameMismatch(certificate = selfSigned) to TrustReason.HostnameMismatch,
                MediaServerError.TlsCertificateExpired(certificate = selfSigned) to TrustReason.Expired,
                MediaServerError.TlsCertificateNotYetValid(certificate = selfSigned) to TrustReason.NotYetValid,
                MediaServerError.TlsCertificateChanged(certificate = selfSigned) to TrustReason.Changed,
            )
        for ((error, reason) in expected) {
            assertEquals(error.toString(), TrustPrompt(reason, selfSigned), error.toTrustPrompt())
        }
    }

    @Test
    fun aCaChainedCertificateIsADeadEndWhateverItsError() {
        val errors =
            listOf(
                MediaServerError.TlsUntrusted(certificate = chained),
                MediaServerError.TlsHostnameMismatch(certificate = chained),
                MediaServerError.TlsCertificateExpired(certificate = chained),
                MediaServerError.TlsCertificateNotYetValid(certificate = chained),
                MediaServerError.TlsCertificateChanged(certificate = chained),
            )
        for (error in errors) assertNull(error.toString(), error.toTrustPrompt())
    }

    @Test
    fun noEvidenceMeansNoQuestion() {
        val errors =
            listOf(
                MediaServerError.TlsUntrusted(),
                MediaServerError.TlsHostnameMismatch(),
                MediaServerError.TlsCertificateExpired(),
                MediaServerError.TlsCertificateNotYetValid(),
                MediaServerError.TlsCertificateChanged(),
            )
        for (error in errors) assertNull(error.toString(), error.toTrustPrompt())
    }

    @Test
    fun aHandshakeFailureOrAPlainTextAnswerIsNeverAQuestion() {
        assertNull(MediaServerError.TlsHandshakeFailed().toTrustPrompt())
        assertNull(MediaServerError.NotTls().toTrustPrompt())
        assertNull(MediaServerError.Timeout().toTrustPrompt())
        assertNull(MediaServerError.HttpUnauthorized().toTrustPrompt())
    }

    @Test
    fun theFingerprintIsSixteenGroupsOfFourHexDigits() {
        val groups = Fingerprint.groups(pin)
        assertEquals(16, groups.size)
        assertTrue(groups.all { it.length == 4 && it.all { c -> c in "0123456789ABCDEF" } })
        assertEquals(pin.hex, groups.joinToString(""))
        val expected = "0001 0203 0405 0607 0809 0A0B 0C0D 0E0F 1011 1213 1415 1617 1819 1A1B 1C1D 1E1F"
        assertEquals(expected, Fingerprint.text(pin))
    }

    @Test
    fun theFingerprintIsShownInFourLinesOfFourGroups() {
        val lines = Fingerprint.lines(pin)
        assertEquals(4, lines.size)
        assertEquals("0001 0203 0405 0607", lines[0])
        assertEquals("1819 1A1B 1C1D 1E1F", lines[3])
        assertEquals(Fingerprint.text(pin), lines.joinToString(" "))
    }

    @Test
    fun aPromptShowsNoCertificateDataInToString() {
        val text = TrustPrompt(TrustReason.Untrusted, selfSigned).toString()
        assertFalse(pin.hex in text)
        assertFalse("0001" in text)
    }
}
