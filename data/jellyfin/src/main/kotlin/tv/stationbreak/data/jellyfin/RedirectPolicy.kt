// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.HttpUrl
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress

internal sealed interface RedirectDecision {
    /** Follow the redirect; the server is now at [address]. */
    data class Adopt(
        val address: ServerAddress,
    ) : RedirectDecision

    /** Don't follow: the probe returns `Redirected`. */
    data object Reject : RedirectDecision
}

/**
 * When a probe may follow a redirect (Angua (Security) R9), so a captive portal or a hijacked name can't quietly
 * become "the server". A redirect is adopted only if the target is the same endpoint of the same server: the same
 * host and subpath, on `http` or on `https`, with `http` to `https` the only change of scheme allowed, and a matching
 * port (the target scheme's default, or the port the original used). Never `https` to `http`. At most
 * [MAX_REDIRECTS] are followed.
 */
internal object RedirectPolicy {
    const val MAX_REDIRECTS = 5

    /** Decides on [target], the URL a probe of [original]'s [endpoint] was redirected to. */
    fun decide(
        original: ServerAddress,
        target: HttpUrl,
        endpoint: String,
    ): RedirectDecision {
        val suffix = "/$endpoint"
        val scheme = Scheme.entries.firstOrNull { it.text == target.scheme }
        val sameServer =
            isPlain(target) &&
                target.host.equals(original.host, ignoreCase = true) &&
                target.encodedPath.endsWith(suffix) &&
                target.encodedPath.removeSuffix(suffix) == original.subpath
        return if (scheme != null && sameServer && schemeAndPortMatch(original, scheme, target.port)) {
            RedirectDecision.Adopt(ServerAddress(scheme, original.host, target.port, original.subpath))
        } else {
            RedirectDecision.Reject
        }
    }

    // No query, and no credentials smuggled into the URL.
    private fun isPlain(target: HttpUrl): Boolean {
        val noCredentials = target.username.isEmpty() && target.password.isEmpty()
        return noCredentials && target.encodedQuery == null
    }

    private fun isHttpsUpgradePort(
        original: ServerAddress,
        port: Int,
    ): Boolean = port == Scheme.Https.defaultPort || port == original.port

    // The same scheme and port, or `http` upgraded to `https` on that scheme's default port or the same port.
    private fun schemeAndPortMatch(
        original: ServerAddress,
        scheme: Scheme,
        port: Int,
    ): Boolean =
        when {
            scheme == original.scheme -> {
                port == original.port
            }

            original.scheme == Scheme.Http && scheme == Scheme.Https -> {
                isHttpsUpgradePort(original, port)
            }

            else -> {
                false
            }
        }
}
