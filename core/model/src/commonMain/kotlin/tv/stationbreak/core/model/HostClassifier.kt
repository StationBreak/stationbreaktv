// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/** What a host is, for the cleartext policy. */
enum class HostClass {
    /** On the local network by its text alone: cleartext needs no confirmation. */
    Private,

    /** Any other valid host: cleartext needs the user's confirmation. */
    Public,

    /** Not usable as a server: an ambiguous numeric spelling, a reserved range or a malformed name. */
    Invalid,
}

/**
 * Classifies a host from its text only, never from DNS (a resolver an attacker may influence would make the policy
 * depend on the attacker). The input is the canonical host a URL parser produces: lower-case, an IPv6 literal
 * without brackets, no trailing dot.
 *
 * Private (ADR-0014): IPv4 10/8, 172.16/12, 192.168/16, 169.254/16 and 127/8; IPv6 `fc00::/7`, `fe80::/10` and
 * `::1`; hostnames ending `.local` or `.home.arpa`; single-label hostnames. An IPv4-mapped IPv6 literal is
 * classified by its IPv4 part. Carrier-grade NAT (100.64/10) is public on purpose.
 *
 * Invalid: IPv4 in any spelling but four decimal octets without leading zeros (parsers disagree about `127.1`,
 * `0x7f.1`, `2130706433`), `0.0.0.0/8`, multicast and broadcast, the IPv6 unspecified and multicast addresses,
 * and names with an empty label.
 */
object HostClassifier {
    fun classify(host: String): HostClass =
        when (val parsed = HostSyntax.parse(host)) {
            HostSyntax.Malformed -> HostClass.Invalid
            HostSyntax.Name -> classifyName(host)
            is HostSyntax.Address -> classifyAddress(parsed.bytes)
        }

    fun isPrivate(host: String): Boolean = classify(host) == HostClass.Private

    private fun classifyName(host: String): HostClass =
        when {
            '.' !in host -> HostClass.Private
            host.endsWith(".local") || host.endsWith(".home.arpa") -> HostClass.Private
            else -> HostClass.Public
        }

    private fun classifyAddress(bytes: List<Int>): HostClass {
        val ipv4 = HostSyntax.ipv4Of(bytes)
        val (invalid, private) = if (ipv4 != null) INVALID_V4 to PRIVATE_V4 else INVALID_V6 to PRIVATE_V6
        val address = ipv4 ?: bytes
        return when {
            invalid.any { it.contains(address) } -> HostClass.Invalid
            private.any { it.contains(address) } -> HostClass.Private
            else -> HostClass.Public
        }
    }

    // Never usable as a server: this-network, multicast, broadcast; the IPv6 unspecified and multicast ranges.
    private val INVALID_V4 = listOf("0.0.0.0/8", "224.0.0.0/4", "255.255.255.255/32").map(Cidr::parse)
    private val INVALID_V6 = listOf("::/128", "ff00::/8").map(Cidr::parse)

    // RFC 1918, link-local and loopback; IPv6 unique-local, link-local and loopback. Not 100.64.0.0/10.
    private val PRIVATE_V4 =
        listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8").map(Cidr::parse)
    private val PRIVATE_V6 = listOf("fc00::/7", "fe80::/10", "::1/128").map(Cidr::parse)
}

/** An address range: the leading [prefix] bits of [network] (4 or 16 bytes) must match. */
internal class Cidr(
    private val network: List<Int>,
    private val prefix: Int,
) {
    fun contains(candidate: List<Int>): Boolean =
        candidate.size == network.size &&
            network.indices.all { index ->
                val bits = (prefix - index * HostSyntax.BYTE_BITS).coerceIn(0, HostSyntax.BYTE_BITS)
                ((network[index] xor candidate[index]) shr (HostSyntax.BYTE_BITS - bits)) == 0
            }

    companion object {
        fun parse(text: String): Cidr {
            val (address, prefix) = text.split('/')
            val bytes = (HostSyntax.parse(address) as HostSyntax.Address).bytes
            return Cidr(bytes, prefix.toInt())
        }
    }
}

/** Reads a host's text as an IPv4 or IPv6 address (as bytes), as some other kind of name, or as malformed. */
internal object HostSyntax {
    sealed interface Parsed

    class Address(
        val bytes: List<Int>,
    ) : Parsed

    data object Name : Parsed

    data object Malformed : Parsed

    const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF
    private const val IPV4_BYTES = 4
    private const val IPV6_GROUPS = 8
    private const val IPV6_GROUP_MAX = 0xFFFF
    private const val HEX_RADIX = 16
    private const val MAPPED_ZERO_BYTES = 10
    private const val MAPPED_MARKER_BYTES = 2

    private val NUMERIC_LABEL = Regex("""^(?:[0-9]+|0[xX][0-9a-fA-F]*)$""")
    private val DECIMAL_OCTET = Regex("""^(?:0|[1-9][0-9]{0,2})$""")
    private val HEX_GROUP = Regex("""^[0-9a-fA-F]{1,4}$""")

    fun parse(host: String): Parsed =
        when {
            host.isEmpty() -> Malformed
            ':' in host -> ipv6(host)?.let(::Address) ?: Malformed
            else -> dotted(host)
        }

    /** The IPv4 bytes of an IPv4 address or of an IPv4-mapped IPv6 one (`::ffff:a.b.c.d`), else null. */
    fun ipv4Of(bytes: List<Int>): List<Int>? =
        when {
            bytes.size == IPV4_BYTES -> {
                bytes
            }

            isMapped(bytes) -> {
                bytes.takeLast(IPV4_BYTES)
            }

            else -> {
                null
            }
        }

    private fun isMapped(bytes: List<Int>): Boolean =
        bytes.take(MAPPED_ZERO_BYTES).all { it == 0 } &&
            bytes.drop(MAPPED_ZERO_BYTES).take(MAPPED_MARKER_BYTES).all { it == BYTE_MASK }

    // The last label decides, as in inet_aton: a numeric last label makes the whole name an address.
    private fun dotted(host: String): Parsed {
        val labels = host.split('.')
        return when {
            labels.any { it.isEmpty() } -> Malformed
            NUMERIC_LABEL.matches(labels.last()) -> strictIpv4(labels)?.let(::Address) ?: Malformed
            else -> Name
        }
    }

    private fun strictIpv4(labels: List<String>): List<Int>? =
        labels
            .takeIf { it.size == IPV4_BYTES && it.all(DECIMAL_OCTET::matches) }
            ?.map(String::toInt)
            ?.takeIf { octets -> octets.all { it <= BYTE_MASK } }

    private fun ipv6(text: String): List<Int>? {
        val halves = text.split("::")
        val head = halves.first().let(::groups)
        val tail = if (halves.size == 2) groups(halves[1]) else emptyList()
        val groups =
            when {
                head == null || tail == null || halves.size > 2 -> null
                halves.size == 1 -> head.takeIf { it.size == IPV6_GROUPS }
                head.size + tail.size < IPV6_GROUPS -> head + List(IPV6_GROUPS - head.size - tail.size) { 0 } + tail
                else -> null
            }
        return groups?.flatMap { listOf(it shr BYTE_BITS, it and BYTE_MASK) }
    }

    // 16-bit groups, the last two possibly written as a dotted IPv4.
    private fun groups(text: String): List<Int>? {
        val parts = if (text.isEmpty()) emptyList() else text.split(':')
        val decoded =
            parts.mapIndexed { index, part ->
                when {
                    index == parts.lastIndex && '.' in part -> strictIpv4(part.split('.'))?.let(::pairsOf)
                    HEX_GROUP.matches(part) -> listOf(part.toInt(HEX_RADIX))
                    else -> null
                }
            }
        return decoded
            .takeIf { list -> list.none { it == null } }
            ?.filterNotNull()
            ?.flatten()
            ?.takeIf { all -> all.all { it <= IPV6_GROUP_MAX } }
    }

    private fun pairsOf(octets: List<Int>): List<Int> {
        val (a, b) = octets.take(2)
        val (c, d) = octets.drop(2)
        return listOf((a shl BYTE_BITS) or b, (c shl BYTE_BITS) or d)
    }
}
