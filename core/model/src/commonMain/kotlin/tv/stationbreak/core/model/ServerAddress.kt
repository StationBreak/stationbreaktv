// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlinx.serialization.Serializable

private const val MIN_PORT = 1
private const val MAX_PORT = 65_535

/**
 * One canonical, validated media server address. It is a plain value: equality compares every part, which is what
 * makes a [CleartextAcknowledgement]'s "same address" check exact.
 *
 * [host] is lower-case, without brackets for an IPv6 literal, and without a trailing dot. [port] is always explicit
 * (the scheme's default when the user typed none). [subpath] is empty or starts with `/` and has no trailing `/`.
 * The adapter builds these (`AddressParser`); this type only refuses values that aren't well formed.
 *
 * Not a secret, but the host is sensitive in diagnostics (S3.6.1), so [toString] redacts it.
 */
@Serializable
data class ServerAddress(
    val scheme: Scheme,
    val host: String,
    val port: Int,
    val subpath: String = "",
) {
    init {
        require(host.isNotEmpty()) { "An address needs a host" }
        require(port in MIN_PORT..MAX_PORT) { "Port out of range" }
        require(subpath.isEmpty() || (subpath.startsWith("/") && !subpath.endsWith("/"))) { "Malformed subpath" }
    }

    /**
     * True for `http` to a host that isn't private (see [HostClassifier]): the user must confirm that the password
     * and token will travel unencrypted before the adapter sends either.
     */
    val cleartextNeedsConfirmation: Boolean
        get() = scheme == Scheme.Http && HostClassifier.classify(host) != HostClass.Private

    /** `scheme://host[:port][/subpath]`, the port left out when it is the scheme's default. */
    val baseUrl: String
        get() {
            val hostText = if (':' in host) "[$host]" else host
            val portText = if (port == scheme.defaultPort) "" else ":$port"
            return "${scheme.text}://$hostText$portText$subpath"
        }

    override fun toString() = "ServerAddress(scheme=$scheme, host=<redacted>, port=$port, subpath=<redacted>)"
}

/**
 * The user's confirmation that cleartext to [address] is acceptable. It is built from one [ServerAddress] and
 * [matches] only that exact address, so an acknowledgement given for one server can't be spent on another.
 */
@Serializable
data class CleartextAcknowledgement(
    val address: ServerAddress,
) {
    fun matches(other: ServerAddress): Boolean = address == other
}
