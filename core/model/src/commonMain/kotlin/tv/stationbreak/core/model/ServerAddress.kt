// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlinx.serialization.Serializable

private const val MIN_PORT = 1
private const val MAX_PORT = 65_535

private fun isForbidden(char: Char): Boolean = char.isISOControl() || char == '='

/**
 * One canonical, validated media server address. It is a plain value: equality compares every part, which is what
 * makes a [CleartextAcknowledgement]'s "same address" check exact.
 *
 * [host] is lower-case, without brackets for an IPv6 literal, and without a trailing dot (except a single label, where
 * the dot is what makes it an absolute name such as `ai.`). [port] is always explicit (the scheme's default when the
 * user typed none). [subpath] is empty or starts with `/` and has no trailing `/`. [viaFallback] is true when the
 * `http` scheme was chosen by falling back after `https` failed, not typed.
 * Neither [host] nor [subpath] holds a control character or `=`.
 * The adapter builds these (`AddressParser`), but an address restored from storage or built by a fake is held to the
 * same rules: [HostClassifier] must not call the host invalid, so no ambiguous numeric spelling reaches a request.
 *
 * Not a secret, but the host is sensitive in diagnostics (S3.6.1), so [toString] redacts it.
 */
@Serializable
data class ServerAddress(
    val scheme: Scheme,
    val host: String,
    val port: Int,
    val subpath: String = "",
    val viaFallback: Boolean = false,
) {
    init {
        require(host.isNotEmpty() && host == host.lowercase()) { "An address needs a lower-case host" }
        require(HostClassifier.classify(host) != HostClass.Invalid) { "Not a usable host" }
        require(port in MIN_PORT..MAX_PORT) { "Port out of range" }
        // The record is stored as `key=value` lines: a newline or '=' in a part could forge another line (defence in
        // depth; a real URL's host and encoded path hold neither).
        require(host.none(::isForbidden)) { "Not a usable host" }
        require(subpath.none(::isForbidden)) { "Malformed subpath" }
        require(subpath.isEmpty() || (subpath.startsWith("/") && !subpath.endsWith("/"))) { "Malformed subpath" }
    }

    /**
     * True for `http` that the user has not been asked about: to a host that isn't private (see [HostClassifier]), or
     * reached by [viaFallback] even on a private host (owner decision, ADR-0014). The user must confirm that the
     * password and token will travel unencrypted before the adapter sends either. Dropping the fallback rule is
     * deleting `viaFallback ||`.
     */
    val cleartextNeedsConfirmation: Boolean
        get() = scheme == Scheme.Http && (viaFallback || HostClassifier.classify(host) != HostClass.Private)

    /**
     * True when this address is the same server as [other]: the same host and subpath, and the same port, or the
     * result of an `http` to `https` upgrade of [other] (on `https`'s default port or [other]'s own). Never a
     * downgrade. A stored token follows an adopted redirect this way and nothing else (token binding, R1).
     */
    fun bindsTo(other: ServerAddress): Boolean {
        val samePlace = host == other.host && subpath == other.subpath
        val unchanged = scheme == other.scheme && port == other.port
        val upgraded =
            scheme == Scheme.Https &&
                other.scheme == Scheme.Http &&
                (port == scheme.defaultPort || port == other.port)
        return samePlace && (unchanged || upgraded)
    }

    /** `scheme://host[:port][/subpath]`, the port left out when it is the scheme's default. */
    val baseUrl: String
        get() {
            val hostText = if (':' in host) "[$host]" else host
            val portText = if (port == scheme.defaultPort) "" else ":$port"
            return "${scheme.text}://$hostText$portText$subpath"
        }

    override fun toString(): String {
        val shown = "scheme=$scheme, port=$port, viaFallback=$viaFallback"
        return "ServerAddress($shown, host=<redacted>, subpath=<redacted>)"
    }
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
