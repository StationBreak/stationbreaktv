// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Both sides of every boundary of the cleartext "private" set (ADR-0014, spec decision 5), decided from text only. */
class HostClassifierTest {
    private fun assertClass(
        expected: HostClass,
        vararg hosts: String,
    ) {
        for (host in hosts) assertEquals(expected, HostClassifier.classify(host), "host '$host'")
    }

    @Test
    fun ipv4Rfc1918TenSlashEight() {
        assertClass(HostClass.Public, "9.255.255.255", "11.0.0.0")
        assertClass(HostClass.Private, "10.0.0.0", "10.0.0.1", "10.255.255.255")
    }

    @Test
    fun ipv4Rfc1918OneSevenTwoSixteenSlashTwelve() {
        assertClass(HostClass.Public, "172.15.255.255", "172.32.0.0")
        assertClass(HostClass.Private, "172.16.0.0", "172.20.1.1", "172.31.255.255")
    }

    @Test
    fun ipv4Rfc1918OneNineTwoSlashSixteen() {
        assertClass(HostClass.Public, "192.167.255.255", "192.169.0.0")
        assertClass(HostClass.Private, "192.168.0.0", "192.168.1.10", "192.168.255.255")
    }

    @Test
    fun ipv4LinkLocal() {
        assertClass(HostClass.Public, "169.253.255.255", "169.255.0.0")
        assertClass(HostClass.Private, "169.254.0.0", "169.254.255.255")
    }

    @Test
    fun ipv4Loopback() {
        assertClass(HostClass.Public, "126.255.255.255", "128.0.0.0")
        assertClass(HostClass.Private, "127.0.0.0", "127.0.0.1", "127.255.255.255")
    }

    @Test
    fun carrierGradeNatIsPublicBecauseTreatingItAsPrivateWouldBeARiskAcceptance() {
        assertClass(
            HostClass.Public,
            "100.63.255.255",
            "100.64.0.0",
            "100.100.100.100",
            "100.127.255.255",
            "100.128.0.0",
        )
    }

    @Test
    fun ordinaryPublicIpv4() {
        assertClass(HostClass.Public, "8.8.8.8", "1.1.1.1", "203.0.113.5", "240.0.0.1", "254.255.255.254")
    }

    @Test
    fun ipv6UniqueLocalFcSlashSeven() {
        assertClass(HostClass.Public, "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe00::1")
        assertClass(HostClass.Private, "fc00::", "fc00::1", "fd00::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
    }

    @Test
    fun ipv6LinkLocalFe80Slash10() {
        assertClass(HostClass.Public, "fe7f:ffff::1", "fec0::1", "fe00::1")
        assertClass(HostClass.Private, "fe80::1", "fe80::abcd:1", "febf:ffff:ffff:ffff::1")
    }

    @Test
    fun ipv6Loopback() {
        assertClass(HostClass.Private, "::1")
        assertClass(HostClass.Public, "::2", "2001:db8::1", "2606:4700::1111")
    }

    @Test
    fun ipv4MappedIpv6IsClassifiedByItsIpv4Part() {
        // The canonical (hex) form a URL parser produces, and the dotted form.
        assertClass(HostClass.Private, "::ffff:a00:1", "::ffff:10.0.0.1", "::ffff:c0a8:1", "::ffff:7f00:1")
        assertClass(HostClass.Public, "::ffff:808:808", "::ffff:8.8.8.8")
    }

    @Test
    fun hostnamesEndingInLocalOrHomeArpaArePrivate() {
        assertClass(HostClass.Private, "nas.local", "a.b.local", "nas.home.arpa", "x.y.home.arpa")
        assertClass(HostClass.Public, "local.example.com", "home.arpa.example.com", "notlocal.example", "example.com")
    }

    @Test
    fun singleLabelHostnamesArePrivate() {
        // The documented residual risk: a single-label name can in principle resolve publicly (ADR-0014).
        assertClass(HostClass.Private, "jellyfin", "nas", "localhost", "local")
        assertClass(HostClass.Public, "jellyfin.example")
    }

    @Test
    fun numericSpellingsOtherThanStrictDottedDecimalAreInvalid() {
        assertClass(
            HostClass.Invalid,
            "127.1",
            "0x7f.1",
            "0x7f000001",
            "2130706433",
            "0177.0.0.1",
            "01.2.3.4",
            "1.2.3",
            "1.2.3.4.5",
            "256.1.1.1",
            "10.0.0.0x1",
        )
    }

    @Test
    fun zeroSlashEightMulticastAndBroadcastAreInvalid() {
        assertClass(HostClass.Invalid, "0.0.0.0", "0.1.2.3", "224.0.0.1", "239.255.255.255", "255.255.255.255")
        assertClass(HostClass.Public, "223.255.255.255")
    }

    @Test
    fun unspecifiedAndMulticastIpv6AreInvalid() {
        assertClass(HostClass.Invalid, "::", "ff02::1", "ff00::", "::ffff:0.0.0.0", "::ffff:e000:1")
    }

    @Test
    fun emptyLabelsAndEmptyHostsAreInvalid() {
        assertClass(HostClass.Invalid, "", "host.", "a..b", ".a")
    }
}
