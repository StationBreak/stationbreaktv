// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.Session

/**
 * The call factory for the player's media requests (HLS playlists and segments, subtitles): the only handle a
 * long-lived consumer (Media3's data source) should keep. [currentSession] is asked for every request, so a sign-out or
 * a switch of server is seen by the next call and never by a stale copy.
 *
 * The `Authorization` header is added only when both hold. The request is for the session's own server: same scheme,
 * host and port, and a path under the address's base path. And the session may carry its token: it passes the
 * adapter's cleartext gate and its token is bound to that server ([AuthorizedCall.isCleared], the same checks every API
 * call makes). Anything else is sent without credentials, and any `Authorization` header or tag the caller supplied is
 * dropped, so neither a wrong origin nor a wrong path on the right origin ever sees the token. Redirects are not
 * followed (the credentialed client's rule), so a redirect can't carry the header anywhere either.
 *
 * Also: only `GET` and `HEAD` get the header, a path with an encoded separator (`%2f`, `%5c`) never does, and every
 * token query parameter ([TOKEN_NAMES]) is removed from every request, on the session's server or any other.
 * The requests go through the media client ([ClientFactory.media]): no call timeout, a 30 s read timeout.
 */
fun JellyfinMediaServer.mediaCallFactory(currentSession: () -> Session?): Call.Factory =
    MediaCallFactory(clients, calls, deviceIds, currentSession)

internal class MediaCallFactory(
    private val clients: ClientFactory,
    private val calls: AuthorizedCall,
    private val deviceIds: DeviceIdProvider,
    private val currentSession: () -> Session?,
) : Call.Factory {
    override fun newCall(request: Request): Call {
        val builder =
            request
                .newBuilder()
                .url(withoutApiKey(request.url))
                .removeHeader("Authorization")
                .tag(AuthTag::class.java, null)
        TOKEN_HEADERS.forEach(builder::removeHeader)
        val session = currentSession()
        if (session != null && mayCarryToken(request, session) && calls.isCleared(session)) {
            // Cached after its first read, so this blocks at most once per install, on Media3's loader thread.
            val deviceId = runBlocking { deviceIds.deviceId() }
            builder.tag(AuthTag::class.java, AuthTag(deviceId, session.token))
        }
        return clients.media.newCall(builder.build())
    }

    /** Only a plain read of a path with no encoded separator, on the session's own server. */
    private fun mayCarryToken(
        request: Request,
        session: Session,
    ): Boolean =
        request.method in READ_METHODS &&
            !ENCODED_SEPARATOR.containsMatchIn(request.url.encodedPath) &&
            isForSessionServer(session.address, request.url)

    /**
     * Jellyfin's own playlists can carry a token query parameter in the variant and segment URLs they list. It
     * would be a second credential on the wire (and in anything that logs a URL), so it never leaves: the header is
     * the only one.
     */
    private fun withoutApiKey(url: HttpUrl): HttpUrl {
        fun isToken(name: String) = TOKEN_NAMES.any { it.equals(name, ignoreCase = true) }
        if ((0 until url.querySize).none { isToken(url.queryParameterName(it)) }) return url
        // Each pair's name is compared decoded, as OkHttp decodes it (removeAllQueryParameters misses a percent-encoded
        // name like `api%5Fkey`), but the pairs that stay keep their raw bytes, so a CDN or proxy URL signed over the
        // query still validates (#898).
        val kept =
            url.encodedQuery
                .orEmpty()
                .split('&')
                .filterNot { isToken(decodedName(it)) }
        return url.newBuilder().encodedQuery(kept.joinToString("&").ifEmpty { null }).build()
    }

    /** One raw `name=value` pair's name, decoded by OkHttp itself. The pair comes from a parsed URL, so it parses. */
    private fun decodedName(pair: String): String = "$PAIR_BASE$pair".toHttpUrl().queryParameterName(0)

    private fun isForSessionServer(
        address: ServerAddress,
        url: HttpUrl,
    ): Boolean {
        val base = address.baseUrl.toHttpUrlOrNull() ?: return false
        val prefix = base.encodedPath.trimEnd('/')
        val path = url.encodedPath
        return url.scheme == base.scheme &&
            url.host == base.host &&
            url.port == base.port &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            (path == prefix || path.startsWith("$prefix/"))
    }

    private companion object {
        val READ_METHODS = setOf("GET", "HEAD")

        /** Any URL will do: only its query is read, to decode one pair's name. */
        const val PAIR_BASE = "http://pair.invalid/?"

        /** `%2f` and `%5c` (any case) turn into a path separator on some servers and proxies. */
        val ENCODED_SEPARATOR = Regex("%2f|%5c", RegexOption.IGNORE_CASE)

        /** The headers a caller may not set: Jellyfin reads them as a token or auth. */
        val TOKEN_HEADERS = listOf("X-Emby-Token", "X-MediaBrowser-Token", "X-Emby-Authorization")

        /** Every query name Jellyfin reads as a token or auth (matched case-insensitively). */
        val TOKEN_NAMES = listOf("api_key", "ApiKey") + TOKEN_HEADERS
    }
}
