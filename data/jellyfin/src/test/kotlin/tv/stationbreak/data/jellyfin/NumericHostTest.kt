// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.HostClass
import tv.stationbreak.core.model.HostClassifier
import tv.stationbreak.core.model.Scheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Angua (Security) R8: numeric hosts are strict dotted-decimal, or an error; parsers disagree about the rest. */
class NumericHostTest {
    private fun assertInvalid(vararg inputs: String) {
        for (input in inputs) {
            for (prefix in listOf("", "http://", "https://")) {
                assertEquals(
                    ServerResult.Failure(MediaServerError.InvalidAddress()),
                    AddressParser.parse(prefix + input),
                    "input '$prefix$input'",
                )
            }
        }
    }

    private fun hostOf(input: String): String? = AddressParser.parse(input).getOrNull()?.host

    @Test
    fun otherSpellingsOfAnIpv4AddressAreRejected() {
        assertInvalid(
            "127.1",
            "0x7f.1",
            "0x7f.0.0.1",
            "0x7f000001",
            "2130706433",
            "017700000001",
            "0177.0.0.1",
            "010.0.0.1",
            "10.0.0.01",
            "1.2.3",
            "1.2.3.4.5",
            "256.1.1.1",
            "300.300.300.300",
            "%31%32%37.0.0.1",
        )
    }

    @Test
    fun theStrictFormIsAccepted() {
        assertEquals("127.0.0.1", hostOf("127.0.0.1"))
        assertEquals("192.168.1.10", hostOf("192.168.1.10:8096"))
        assertEquals("8.8.8.8", hostOf("http://8.8.8.8"))
    }

    @Test
    fun anIpv4MappedIpv6LiteralIsClassifiedByItsIpv4Part() {
        val host = hostOf("[::ffff:10.0.0.1]:8096")!!
        assertEquals(HostClass.Private, HostClassifier.classify(host))
        val publicHost = hostOf("[::ffff:8.8.8.8]")!!
        assertEquals(HostClass.Public, HostClassifier.classify(publicHost))
    }

    @Test
    fun thisNetworkMulticastAndBroadcastAreRejected() {
        assertInvalid("0.0.0.0", "0.1.2.3", "224.0.0.1", "239.255.255.255", "255.255.255.255")
        assertInvalid("[::]", "[ff02::1]", "[::ffff:0.0.0.0]")
    }

    @Test
    fun oneTrailingDotIsStrippedAndASecondIsRejected() {
        assertEquals("media.example.com", hostOf("media.example.com."))
        assertEquals("10.0.0.1", hostOf("http://10.0.0.1./"))
        assertInvalid("media.example.com..", "10.0.0.1..", ".", "..")
    }

    @Test
    fun anAcceptedNumericHostKeepsNoSchemeOrPortOfItsOwn() {
        val candidate = AddressParser.parse("10.0.0.1").getOrNull()!!
        assertEquals(null, candidate.scheme)
        assertEquals(null, candidate.port)
        assertEquals(Scheme.Http, candidate.at(Scheme.Http).scheme)
    }
}
