// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Watches every request the adapter makes, behind its own header interceptor, so the suite can assert the
 * `Authorization` form the server really accepted. It keeps names and shapes, never a token.
 */
internal class RecordingInterceptor : Interceptor {
    class Seen(
        val method: String,
        val path: String,
        val authorization: List<String>,
        val legacyHeaders: List<String>,
        val legacyQueryNames: List<String>,
    )

    val seen = CopyOnWriteArrayList<Seen>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        seen +=
            Seen(
                request.method,
                request.url.encodedPath,
                request.headers("Authorization"),
                request.headers.names().filter { it.lowercase() in LEGACY_HEADERS },
                request.url.queryParameterNames.filter { it.lowercase() in LEGACY_QUERY },
            )
        return chain.proceed(request)
    }

    private companion object {
        val LEGACY_HEADERS = setOf("x-emby-authorization", "x-emby-token", "x-mediabrowser-token", "x-emby-client")
        val LEGACY_QUERY = setOf("api_key", "apikey", "x-emby-token", "x-emby-authorization")
    }
}
