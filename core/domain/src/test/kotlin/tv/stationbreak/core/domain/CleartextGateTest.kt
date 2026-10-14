// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.StoredSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Angua (Security) R7: an acknowledgement is bound to one address, and the port accepts it only in that form. */
class CleartextGateTest {
    private val publicHttp = ServerAddress(Scheme.Http, "media.example.com", 8096, "/jf")

    private val notConfirmed = MediaServerError.CleartextNotConfirmed()

    @Test
    fun cleartextToAPublicHostNeedsAnAcknowledgement() {
        assertEquals(notConfirmed, CleartextGate.check(publicHttp, null))
    }

    @Test
    fun theMatchingAcknowledgementPasses() {
        assertNull(CleartextGate.check(publicHttp, CleartextAcknowledgement(publicHttp)))
    }

    @Test
    fun anAcknowledgementForAnotherHostPortSchemeOrSubpathFails() {
        val others =
            mapOf(
                "host" to publicHttp.copy(host = "other.example.com"),
                "port" to publicHttp.copy(port = 8097),
                "scheme" to publicHttp.copy(scheme = Scheme.Https),
                "subpath" to publicHttp.copy(subpath = "/jf2"),
            )
        for ((what, other) in others) {
            assertEquals(notConfirmed, CleartextGate.check(publicHttp, CleartextAcknowledgement(other)), what)
        }
    }

    @Test
    fun privateAndHttpsAddressesNeedNone() {
        assertNull(CleartextGate.check(ServerAddress(Scheme.Http, "192.168.1.10", 8096), null))
        assertNull(CleartextGate.check(ServerAddress(Scheme.Http, "nas.local", 80), null))
        assertNull(CleartextGate.check(ServerAddress(Scheme.Https, "media.example.com", 443), null))
    }

    @Test
    fun httpReachedByFallbackNeedsTheAcknowledgementEvenOnTheLan() {
        val fallback = ServerAddress(Scheme.Http, "192.168.1.10", 8096, viaFallback = true)
        assertEquals(notConfirmed, CleartextGate.check(fallback, null))
        val typedAck = CleartextAcknowledgement(fallback.copy(viaFallback = false))
        assertEquals(notConfirmed, CleartextGate.check(fallback, typedAck))
        assertNull(CleartextGate.check(fallback, CleartextAcknowledgement(fallback)))
    }

    @Test
    fun httpTypedForAPrivateHostNeedsNone() {
        assertNull(CleartextGate.check(ServerAddress(Scheme.Http, "192.168.1.10", 8096), null))
        assertNull(CleartextGate.check(ServerAddress(Scheme.Http, "jellyfin", 8096), null))
    }

    @Test
    fun anUnneededAcknowledgementIsHarmless() {
        val private = ServerAddress(Scheme.Http, "192.168.1.10", 8096)
        assertNull(CleartextGate.check(private, CleartextAcknowledgement(publicHttp)))
    }

    @Test
    fun noPortMethodTakesAnAcknowledgementInAnyOtherForm() {
        val allowed =
            setOf(
                String::class.java,
                ServerAddress::class.java,
                CleartextAcknowledgement::class.java,
                // Each carries its acknowledgement as a CleartextAcknowledgement?, never as a flag or a string.
                SignInRequest::class.java,
                StoredSession::class.java,
                Session::class.java,
                // The media calls (Unit 3) take a session, which carries the acknowledgement, plus plain values.
                Library::class.java,
                PlaybackReport::class.java,
                Int::class.javaPrimitiveType,
                kotlin.coroutines.Continuation::class.java,
            )
        for (method in MediaServer::class.java.methods.filterNot { it.isSynthetic }) {
            val unexpected = method.parameterTypes.filterNot { it in allowed }
            assertEquals(emptyList(), unexpected, method.name)
        }
    }
}
