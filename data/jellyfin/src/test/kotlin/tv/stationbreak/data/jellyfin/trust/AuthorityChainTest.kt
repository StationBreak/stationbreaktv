// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import okhttp3.tls.HeldCertificate
import tv.stationbreak.data.jellyfin.trust.AuthorityChain.Candidate
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** #223: pinning a private CA, as a home lab's or Caddy's `tls internal` chain presents it. */
class AuthorityChainTest {
    private val now = System.currentTimeMillis()
    private val day = TimeUnit.DAYS.toMillis(1)

    private fun ca(
        name: String,
        intermediates: Int,
        days: Long,
        signedBy: HeldCertificate? = null,
    ): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(name)
            .certificateAuthority(intermediates)
            .validityInterval(now - day, now + days * day)
            .apply { signedBy?.let(::signedBy) }
            .build()

    private fun leaf(
        signedBy: HeldCertificate,
        vararg names: String = arrayOf(NAS),
    ): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName(names.first())
            .apply { names.forEach(::addSubjectAlternativeName) }
            .validityInterval(now - day, now + 30 * day)
            .signedBy(signedBy)
            .build()

    private val root = ca("Home Lab Root", intermediates = 1, days = 3650)

    // A public CA unrelated to the home lab: a non-empty system list, as on a real TV.
    private val publicCas = listOf(ca("Some Public Root", intermediates = 1, days = 3650).certificate)
    private val intermediate = ca("Home Lab Intermediate", intermediates = 0, days = 365, signedBy = root)
    private val server = leaf(intermediate)

    private fun chain(vararg certs: HeldCertificate): List<X509Certificate> = certs.map { it.certificate }

    private fun pinOf(cert: HeldCertificate) = CertificateFacts.spki(cert.certificate)

    @Test
    fun aPinnedRootThatTheChainLeadsToIsAccepted() {
        assertTrue(AuthorityChain.accepts(chain(server, intermediate, root), pinOf(root), NAS, now))
    }

    @Test
    fun aPinnedIntermediateIsAcceptedWithoutTheRoot() {
        assertTrue(AuthorityChain.accepts(chain(server, intermediate), pinOf(intermediate), NAS, now))
    }

    @Test
    fun thePathIsBuiltBySignaturesNotByTheOrderTheServerSent() {
        assertTrue(AuthorityChain.accepts(chain(server, root, intermediate), pinOf(root), NAS, now))
    }

    @Test
    fun aPinOnTheLeafItselfIsNeverAnAuthorityMatch() {
        assertFalse(AuthorityChain.accepts(chain(server, intermediate, root), pinOf(server), NAS, now))
    }

    @Test
    fun aLeafForAnotherNameIsRefused() {
        assertFalse(AuthorityChain.accepts(chain(server, intermediate, root), pinOf(root), OTHER, now))
    }

    @Test
    fun anImpostorIntermediateWithTheSameNameButAnotherKeyIsRefused() {
        val impostor = ca("Home Lab Intermediate", intermediates = 0, days = 365)
        val forged = leaf(impostor)
        assertFalse(AuthorityChain.accepts(chain(forged, impostor, root), pinOf(root), NAS, now))
    }

    @Test
    fun anExpiredAuthorityIsRefused() {
        val later = now + 4000 * day
        assertFalse(AuthorityChain.accepts(chain(server, intermediate, root), pinOf(root), NAS, later))
    }

    @Test
    fun aLeafThatIsItselfACaIsRefused() {
        val caLeaf =
            HeldCertificate
                .Builder()
                .commonName(NAS)
                .addSubjectAlternativeName(NAS)
                .certificateAuthority(0)
                .signedBy(intermediate)
                .build()
        assertFalse(AuthorityChain.accepts(chain(caLeaf, intermediate, root), pinOf(root), NAS, now))
    }

    @Test
    fun theOfferIsTheTopmostVerifiedCa() {
        val offered = AuthorityChain.candidate(chain(server, intermediate, root), NAS, now, publicCas)
        assertIs<Candidate.Pinnable>(offered)
        assertTrue(offered.evidence.spki.matches(pinOf(root)))
    }

    @Test
    fun aShortLivedIntermediateLikeDefaultCaddyIsNotOffered() {
        val weekly = ca("Caddy Local Authority - ECC Intermediate", intermediates = 0, days = 7, signedBy = root)
        val caddyLeaf = leaf(weekly)
        val offered = AuthorityChain.candidate(chain(caddyLeaf, weekly), NAS, now, publicCas)
        assertEquals(Candidate.TooShortLived, offered)
    }

    @Test
    fun aChainThatReachesASystemCaIsNeverOffered() {
        val offered = AuthorityChain.candidate(chain(server, intermediate, root), NAS, now, listOf(root.certificate))
        assertEquals(Candidate.None, offered)
    }

    @Test
    fun aChainThatWouldNotBeAcceptedIsNotOffered() {
        val offered = AuthorityChain.candidate(chain(server, intermediate, root), OTHER, now, publicCas)
        assertEquals(Candidate.None, offered)
    }

    @Test
    fun aSelfSignedLeafHasNoAuthorityToOffer() {
        assertEquals(Candidate.None, AuthorityChain.candidate(chain(selfSignedFor(NAS)), NAS, now, publicCas))
    }

    @Test
    fun withNoListOfSystemCasNothingIsOffered() {
        val offered = AuthorityChain.candidate(chain(server, intermediate, root), NAS, now, emptyList())
        assertEquals(Candidate.None, offered)
    }
}
