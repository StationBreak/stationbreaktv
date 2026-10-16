// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import okhttp3.tls.HeldCertificate
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the pinning (spec 05, Unit 3, task 3.2): keys, SPKI, "self-signed", and the verdicts. */
class PinPolicyTest {
    private val now = System.currentTimeMillis()
    private val host = "nas.example.test"
    private val key = PinKey.https(host, 8920)

    private fun selfSigned(
        name: String = host,
        notBefore: Long = now - TimeUnit.DAYS.toMillis(1),
        notAfter: Long = now + TimeUnit.DAYS.toMillis(30),
    ): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(name)
            .addSubjectAlternativeName(name)
            .validityInterval(notBefore, notAfter)
            .build()

    private fun policy(vararg pins: Pair<PinKey, SpkiPin>) = PinPolicy(pins.toMap())

    // Judged at the current time, not at `now`: a certificate built with OkHttp's default validity starts when it is
    // built, which can be a second after `now` was taken, and it would then read as not yet valid.
    private fun verdict(
        cert: HeldCertificate,
        policy: PinPolicy = policy(),
        key: PinKey? = this.key,
        host: String? = this.host,
    ) = policy.judge(cert.certificate, key, host, System.currentTimeMillis())

    // --- the key ---

    @Test
    fun theKeyIsTheExactSchemeHostAndPort() {
        val address = ServerAddress(Scheme.Https, "NAS.Example.Test".lowercase(), 8920)
        assertEquals(PinKey.https("nas.example.test", 8920), PinKey.of(address))
        assertNotEquals(PinKey.https("nas.example.test", 443), PinKey.of(address))
        assertNotEquals(PinKey.https("other.example.test", 8920), PinKey.of(address))
    }

    @Test
    fun theHostIsCanonicalisedForTheLookup() {
        assertEquals(PinKey.https("nas.example.test", 8920), PinKey.https("NAS.Example.TEST", 8920))
        assertEquals(PinKey.https("2001:db8::1", 443), PinKey.https("[2001:db8::1]", 443))
    }

    @Test
    fun thereIsNoKeyForHttp() {
        assertNull(PinKey.of(ServerAddress(Scheme.Http, "nas.example.test", 8096)))
    }

    // --- SPKI ---

    @Test
    fun theSpkiIsTheSha256OfTheSubjectPublicKeyInfo() {
        val cert = selfSigned()
        val expected = MessageDigest.getInstance("SHA-256").digest(cert.certificate.publicKey.encoded)
        assertEquals(SpkiPin(expected), CertificateFacts.spki(cert.certificate))
    }

    @Test
    fun aRenewedCertificateOnTheSameKeyKeepsItsPin() {
        val first = selfSigned()
        val renewed =
            HeldCertificate
                .Builder()
                .keyPair(first.keyPair)
                .serialNumber(2L)
                .commonName(host)
                .addSubjectAlternativeName(host)
                .duration(90, TimeUnit.DAYS)
                .build()
        assertNotEquals(first.certificate.serialNumber, renewed.certificate.serialNumber)
        assertEquals(CertificateFacts.spki(first.certificate), CertificateFacts.spki(renewed.certificate))
    }

    @Test
    fun anotherKeyIsAnotherPin() {
        assertNotEquals(
            CertificateFacts.spki(selfSigned().certificate),
            CertificateFacts.spki(selfSigned().certificate),
        )
    }

    // --- "self-signed" ---

    @Test
    fun aLeafThatVerifiesUnderItsOwnKeyIsSelfSigned() {
        assertTrue(CertificateFacts.isSelfSigned(selfSigned().certificate))
    }

    @Test
    fun aLeafSignedByACaIsNotSelfSigned() {
        val root =
            HeldCertificate
                .Builder()
                .certificateAuthority(0)
                .commonName("Test Root")
                .build()
        val leaf =
            HeldCertificate
                .Builder()
                .addSubjectAlternativeName(host)
                .signedBy(root)
                .build()
        assertFalse(CertificateFacts.isSelfSigned(leaf.certificate))
    }

    @Test
    fun matchingNamesDoNotMakeALeafSelfSigned() {
        // The issuer and the subject carry the same name, but the signature is another key's.
        val root =
            HeldCertificate
                .Builder()
                .certificateAuthority(0)
                .commonName(host)
                .build()
        val leaf =
            HeldCertificate
                .Builder()
                .commonName(host)
                .addSubjectAlternativeName(host)
                .signedBy(root)
                .build()
        assertEquals(leaf.certificate.subjectX500Principal, leaf.certificate.issuerX500Principal)
        assertFalse(CertificateFacts.isSelfSigned(leaf.certificate))
    }

    @Test
    fun aSelfSignedLeafWithADifferentIssuerNameIsStillSelfSignedByItsSignature() {
        assertTrue(CertificateFacts.isSelfSigned(selfSigned().certificate))
    }

    // --- the verdicts ---

    @Test
    fun aMatchingPinIsAcceptedWhateverTheDatesAndTheName() {
        val expired =
            selfSigned(
                name = "other.example.test",
                notBefore = now - TimeUnit.DAYS.toMillis(10),
                notAfter = now - TimeUnit.DAYS.toMillis(5),
            )
        val pinned = policy(key to CertificateFacts.spki(expired.certificate))
        assertEquals(PinVerdict.Accepted, verdict(expired, pinned))
    }

    @Test
    fun anUnpinnedSelfSignedLeafIsUntrustedAndCarriesItsEvidence() {
        val cert = selfSigned()
        val result = verdict(cert)
        assertIs<PinVerdict.Rejected>(result)
        assertEquals(Rejection.Untrusted, result.kind)
        assertEquals(CertificateFacts.spki(cert.certificate), result.evidence.spki)
        assertTrue(result.evidence.selfSigned)
        assertEquals(cert.certificate.notBefore.time, result.evidence.notBeforeMillis)
        assertEquals(cert.certificate.notAfter.time, result.evidence.notAfterMillis)
    }

    @Test
    fun aDifferentPinForTheSameHostIsACertificateChange() {
        val cert = selfSigned()
        val old = SpkiPin(ByteArray(SpkiPin.SIZE) { 9 })
        val result = verdict(cert, policy(key to old))
        assertIs<PinVerdict.Rejected>(result)
        assertEquals(Rejection.Changed, result.kind)
        assertEquals(CertificateFacts.spki(cert.certificate), result.evidence.spki)
    }

    @Test
    fun aPinForAnotherHostOrPortIsNeverConsulted() {
        val cert = selfSigned()
        val pinsForOthers =
            policy(
                PinKey.https("other.example.test", 8920) to CertificateFacts.spki(cert.certificate),
                PinKey.https(host, 443) to CertificateFacts.spki(cert.certificate),
            )
        val result = verdict(cert, pinsForOthers)
        assertIs<PinVerdict.Rejected>(result)
        assertEquals(Rejection.Untrusted, result.kind)
    }

    @Test
    fun withNoKnownHostNothingMatches() {
        val cert = selfSigned()
        val pins = policy(key to CertificateFacts.spki(cert.certificate))
        val result = verdict(cert, pins, key = null, host = null)
        assertIs<PinVerdict.Rejected>(result)
        assertEquals(Rejection.HostnameMismatch, result.kind)
    }

    @Test
    fun aSelfSignedLeafForAnotherNameIsAHostnameMismatchWithEvidence() {
        val result = verdict(selfSigned(name = "other.example.test"))
        assertIs<PinVerdict.Rejected>(result)
        assertEquals(Rejection.HostnameMismatch, result.kind)
        assertTrue(result.evidence.selfSigned)
    }

    @Test
    fun anExpiredSelfSignedLeafIsExpiredButItsEvidenceSaysItCanBePinned() {
        val cert = selfSigned(notBefore = now - TimeUnit.DAYS.toMillis(10), notAfter = now - TimeUnit.DAYS.toMillis(5))
        val result = verdict(cert)
        assertIs<PinVerdict.Rejected>(result)
        assertEquals(Rejection.Expired, result.kind)
        assertTrue(result.evidence.selfSigned)
    }

    @Test
    fun aNotYetValidSelfSignedLeafIsNotYetValid() {
        val cert = selfSigned(notBefore = now + TimeUnit.DAYS.toMillis(5), notAfter = now + TimeUnit.DAYS.toMillis(50))
        val result = verdict(cert)
        assertIs<PinVerdict.Rejected>(result)
        assertEquals(Rejection.NotYetValid, result.kind)
        assertTrue(result.evidence.selfSigned)
    }

    @Test
    fun anExpiredCaChainedLeafIsExpiredAndNotSelfSignedSoItIsADeadEnd() {
        val root =
            HeldCertificate
                .Builder()
                .certificateAuthority(0)
                .commonName("Test Root")
                .build()
        val leaf =
            HeldCertificate
                .Builder()
                .addSubjectAlternativeName(host)
                .validityInterval(now - TimeUnit.DAYS.toMillis(10), now - TimeUnit.DAYS.toMillis(5))
                .signedBy(root)
                .build()
        val result = verdict(leaf)
        assertIs<PinVerdict.Rejected>(result)
        assertEquals(Rejection.Expired, result.kind)
        assertFalse(result.evidence.selfSigned)
    }

    @Test
    fun aCaChainedLeafIsNeverReportedSelfSignedEvenWhenUnpinned() {
        val root =
            HeldCertificate
                .Builder()
                .certificateAuthority(0)
                .commonName("Test Root")
                .build()
        val leaf =
            HeldCertificate
                .Builder()
                .addSubjectAlternativeName(host)
                .signedBy(root)
                .build()
        val result = verdict(leaf)
        assertIs<PinVerdict.Rejected>(result)
        assertEquals(Rejection.Untrusted, result.kind)
        assertFalse(result.evidence.selfSigned)
    }

    // --- the hostname verifier's question ---

    @Test
    fun aNameMismatchIsToleratedOnlyOnAPinMatchForTheExactKey() {
        val cert = selfSigned(name = "other.example.test")
        val spki = CertificateFacts.spki(cert.certificate)
        assertTrue(policy(key to spki).pinMatches(cert.certificate, key))
        assertFalse(policy().pinMatches(cert.certificate, key))
        assertFalse(policy(key to spki).pinMatches(cert.certificate, PinKey.https(host, 443)))
        assertFalse(policy(key to spki).pinMatches(cert.certificate, null))
        assertFalse(policy(key to SpkiPin(ByteArray(SpkiPin.SIZE))).pinMatches(cert.certificate, key))
    }
}
