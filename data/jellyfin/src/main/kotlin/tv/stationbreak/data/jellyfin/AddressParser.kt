// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.HostClass
import tv.stationbreak.core.model.HostClassifier
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress

/**
 * What the user typed, normalised but not yet resolved: [scheme] and [port] are null when they weren't typed, and are
 * filled in per scheme by [at]. No port is guessed: `192.168.1.10` is port 80 or 443, never 8096.
 */
internal data class AddressCandidate(
    val scheme: Scheme?,
    val host: String,
    val port: Int?,
    val subpath: String,
) {
    /** The address when the user typed a scheme, else null. */
    val explicit: ServerAddress? get() = scheme?.let(::at)

    fun at(scheme: Scheme): ServerAddress = ServerAddress(scheme, host, port ?: scheme.defaultPort, subpath)
}

/**
 * Turns what a user typed into an [AddressCandidate] (S0.3.1), built on OkHttp's `HttpUrl` for IPv6 literals, IDN and
 * percent-encoding. Rejects with a bare `InvalidAddress` (never repeating the input, which may hold a password):
 * empty input, user-info, a scheme other than `http`/`https`, a bad port, a malformed host, and every numeric host
 * spelling that isn't strict dotted-decimal (Angua (Security) R8).
 */
internal object AddressParser {
    private const val SCHEME_SEPARATOR = "://"
    private const val AUTHORITY_END = "/?#\\"
    private const val ASCII_LIMIT = 128

    /** Control flow for "this input is not an address"; never leaves this object. */
    private class Invalid : Exception()

    fun parse(input: String): ServerResult<AddressCandidate> =
        try {
            ServerResult.Success(candidateOf(input.trim()))
        } catch (_: Invalid) {
            ServerResult.Failure(MediaServerError.InvalidAddress())
        }

    private fun candidateOf(text: String): AddressCandidate {
        val (scheme, rest) = splitScheme(text)
        val authority = rest.takeWhile { it !in AUTHORITY_END }
        // A backslash is a path separator to some parsers and an authority delimiter to others: refuse the ambiguity.
        if (authority.isEmpty() || '@' in authority || '\\' in text) throw Invalid()
        // The scheme only matters to HttpUrl for its default port; the real one is chosen later.
        val url = "https://$rest".toHttpUrlOrNull() ?: throw Invalid()
        checkHost(rawHostOf(authority), url.host)
        return AddressCandidate(
            scheme = scheme,
            host = withoutTrailingDot(url.host),
            port = url.port.takeIf { typedPort(authority) },
            subpath = subpathOf(url.encodedPathSegments),
        )
    }

    private fun splitScheme(text: String): Pair<Scheme?, String> {
        val index = text.indexOf(SCHEME_SEPARATOR)
        if (index < 0) return null to text
        val scheme = Scheme.entries.firstOrNull { it.text == text.substring(0, index).lowercase() } ?: throw Invalid()
        return scheme to text.substring(index + SCHEME_SEPARATOR.length)
    }

    private fun rawHostOf(authority: String): String =
        if (authority.startsWith('[')) {
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBefore(':')
        }.lowercase()

    private fun typedPort(authority: String): Boolean {
        val afterHost =
            if (authority.startsWith('[')) {
                authority.substringAfter(']', "").removePrefix(":")
            } else {
                authority.substringAfter(':', "")
            }
        return afterHost.isNotEmpty()
    }

    // Both the raw text and the canonical host must be usable, so a spelling that HttpUrl normalises can't hide.
    private fun checkHost(
        raw: String,
        canonical: String,
    ) {
        // A non-ASCII raw host (an IDN) is judged by its canonical punycode form only.
        val hosts = if (raw.all { it.code < ASCII_LIMIT }) listOf(raw, canonical) else listOf(canonical)
        for (host in hosts) {
            if (HostClassifier.classify(withoutTrailingDot(host)) == HostClass.Invalid) throw Invalid()
        }
    }

    // One trailing dot on a name is the root label; a second one is an empty label, which the classifier rejects.
    private fun withoutTrailingDot(host: String): String = if (':' in host) host else host.removeSuffix(".")

    private fun subpathOf(segments: List<String>): String {
        val kept = withoutWebClient(segments.dropLastWhile { it.isEmpty() }).dropLastWhile { it.isEmpty() }
        return if (kept.isEmpty()) "" else kept.joinToString(separator = "/", prefix = "/")
    }

    // The web client lives at <base>/web/index.html; users paste that.
    private fun withoutWebClient(segments: List<String>): List<String> {
        val lower = segments.map { it.lowercase() }
        return when {
            lower.takeLast(2) == listOf("web", "index.html") -> segments.dropLast(2)
            lower.lastOrNull() == "web" -> segments.dropLast(1)
            else -> segments
        }
    }
}
