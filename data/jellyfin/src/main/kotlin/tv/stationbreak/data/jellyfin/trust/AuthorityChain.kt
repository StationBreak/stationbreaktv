// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import okhttp3.internal.tls.OkHostnameVerifier
import tv.stationbreak.core.model.AuthorityEvidence
import tv.stationbreak.core.model.SpkiPin
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Trust in a private certificate authority, pinned by its SPKI for one scheme, host and port (#223), from what each
 * handshake presents and nothing stored but the pin. Used only after system trust has refused the chain.
 *
 * A chain is accepted for a CA pin when: the path from the leaf is built by signatures (never by array order or by
 * key identifiers, which anyone can copy) up to a presented certificate whose SPKI is the pin, never the leaf itself;
 * the platform's PKIX validator accepts the certificates below it with that CA as the anchor (revocation off: a private
 * CA has no OCSP or CRL); the CA itself, which PKIX does not check, is a CA allowed to sign certificates, in date, with
 * a path length that allows the chain and no name constraints (refused rather than half-enforced); the leaf names the
 * host, is not a CA and, when it says what it is for, is for server authentication; and every certificate on the path
 * is signed with SHA-2 by a key of acceptable strength.
 */
internal object AuthorityChain {
    /** The longest path walked from the leaf. */
    private const val MAX_DEPTH = 5

    /** A CA that lives less than this is not offered: its pin would warn "changed" at each renewal (#223). */
    private val MIN_LIFETIME_MS = TimeUnit.DAYS.toMillis(90)

    private const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    private const val NAME_CONSTRAINTS = "2.5.29.30"
    private const val KEY_CERT_SIGN = 5
    private const val MIN_RSA_BITS = 2048

    // The only EC curves accepted, compared by their parameters, so a brainpool, secp256k1 or explicit curve of the
    // same size is refused. Read from a generated key, not AlgorithmParameters("EC"), which Android has only from
    // API 26: on API 25 that left the list empty and refused every EC chain.
    private val NAMED_CURVES: List<ECParameterSpec> by lazy {
        listOf("secp256r1", "secp384r1", "secp521r1").mapNotNull { name ->
            try {
                val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(name)) }
                (generator.generateKeyPair().public as ECPublicKey).params
            } catch (_: GeneralSecurityException) {
                null
            }
        }
    }
    private val SHA2 = Regex("SHA-?(256|384|512)", RegexOption.IGNORE_CASE)
    private val SIGNERS = Regex("RSA|ECDSA|PSS", RegexOption.IGNORE_CASE)

    /** The verified path from the leaf up: each next certificate is the one whose key signed the one before. */
    fun path(chain: List<X509Certificate>): List<X509Certificate> {
        if (chain.isEmpty()) return emptyList()
        val left = chain.drop(1).toMutableList()
        return generateSequence(chain[0]) { current ->
            if (isSelfSigned(current)) null else left.firstOrNull { issued(current, by = it) }?.also { left -= it }
        }.take(MAX_DEPTH).toList()
    }

    // [by] issued [certificate]: its name is the issuer's, and its key verifies the signature.
    private fun issued(
        certificate: X509Certificate,
        by: X509Certificate,
    ): Boolean = by.subjectX500Principal == certificate.issuerX500Principal && signs(by.publicKey, certificate)

    /** True when [chain] is accepted for a connection to [host] under the CA pin [pin]. */
    fun accepts(
        chain: List<X509Certificate>,
        pin: SpkiPin,
        host: String,
        nowMillis: Long,
    ): Boolean {
        val path = path(chain)
        val at = (1 until path.size).firstOrNull { pin.matches(CertificateFacts.spki(path[it])) } ?: return false
        val anchor = path[at]
        val below = path.subList(0, at)
        return anchorOk(anchor, intermediates = at - 1, nowMillis) &&
            path.subList(0, at + 1).all(::strongEnough) &&
            leafOk(path[0], host) &&
            pkixAccepts(below, anchor, nowMillis)
    }

    /**
     * The CA that could be pinned for [chain] at [host]: the topmost certificate the path verifiably reaches, when a
     * pin on it would be accepted, it is not one the system trusts ([systemIssuers]) and it lives at least 90 days.
     */
    fun candidate(
        chain: List<X509Certificate>,
        host: String,
        nowMillis: Long,
        systemIssuers: List<X509Certificate>,
    ): Candidate {
        val path = path(chain)
        val top = path.drop(1).lastOrNull()?.takeIf { it.basicConstraints >= 0 } ?: return Candidate.None
        val spki = CertificateFacts.spki(top)
        // No list of system CAs means none can be ruled out: nothing is offered. A CA the user disabled or the platform
        // blocklisted is not in the list, so a chain to one could be offered; the pin is still for one host and port.
        val public =
            systemIssuers.isEmpty() ||
                systemIssuers.any {
                    spki.matches(CertificateFacts.spki(it)) || issued(top, by = it)
                }
        return when {
            public || !accepts(chain, spki, host, nowMillis) -> Candidate.None
            top.notAfter.time - top.notBefore.time < MIN_LIFETIME_MS -> Candidate.TooShortLived
            else -> Candidate.Pinnable(AuthorityEvidence(spki, top.notBefore.time, top.notAfter.time))
        }
    }

    sealed interface Candidate {
        data object None : Candidate

        data object TooShortLived : Candidate

        data class Pinnable(
            val evidence: AuthorityEvidence,
        ) : Candidate
    }

    private fun anchorOk(
        anchor: X509Certificate,
        intermediates: Int,
        nowMillis: Long,
    ): Boolean {
        val usage = anchor.keyUsage
        val pathLen = anchor.basicConstraints
        return pathLen >= 0 &&
            (usage == null || usage.getOrElse(KEY_CERT_SIGN) { false }) &&
            CertificateFacts.validity(anchor, nowMillis) == Validity.Valid &&
            pathLen >= intermediates &&
            anchor.getExtensionValue(NAME_CONSTRAINTS) == null
    }

    private fun leafOk(
        leaf: X509Certificate,
        host: String,
    ): Boolean {
        val purposes = leaf.extendedKeyUsage
        return leaf.basicConstraints == -1 &&
            (purposes == null || SERVER_AUTH in purposes) &&
            OkHostnameVerifier.verify(host, leaf)
    }

    private fun strongEnough(certificate: X509Certificate): Boolean {
        val algorithm = certificate.sigAlgName.orEmpty()
        val signedWell = SHA2.containsMatchIn(algorithm) && SIGNERS.containsMatchIn(algorithm)
        val key = certificate.publicKey
        val keyWell =
            when (key) {
                is RSAPublicKey -> key.modulus.bitLength() >= MIN_RSA_BITS
                is ECPublicKey -> NAMED_CURVES.any { it.sameCurveAs(key.params) }
                else -> false
            }
        return signedWell && keyWell
    }

    private fun pkixAccepts(
        below: List<X509Certificate>,
        anchor: X509Certificate,
        nowMillis: Long,
    ): Boolean =
        try {
            val path = CertificateFactory.getInstance("X.509").generateCertPath(below)
            val parameters =
                PKIXParameters(setOf(TrustAnchor(anchor, null))).apply {
                    isRevocationEnabled = false
                    date = Date(nowMillis)
                }
            CertPathValidator.getInstance("PKIX").validate(path, parameters)
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (
            @Suppress("TooGenericExceptionCaught") _: RuntimeException,
        ) {
            // A provider that throws on an unusual certificate: refused, never trusted.
            false
        }

    private fun isSelfSigned(certificate: X509Certificate): Boolean =
        certificate.subjectX500Principal == certificate.issuerX500Principal && signs(certificate.publicKey, certificate)

    private fun signs(
        key: PublicKey,
        certificate: X509Certificate,
    ): Boolean =
        try {
            certificate.verify(key)
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (
            @Suppress("TooGenericExceptionCaught") _: RuntimeException,
        ) {
            false
        }

    private fun ECParameterSpec.sameCurveAs(other: ECParameterSpec): Boolean =
        curve == other.curve && generator == other.generator && order == other.order && cofactor == other.cofactor
}
