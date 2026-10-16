// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tv.stationbreak.core.domain.DeviceIdProvider
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.io.IOException

/** The most of an answer the adapter reads (1 MiB): more is a server error, never an out-of-memory. */
internal const val MAX_BODY_BYTES = 1 shl 20

/** One call to the server: its method, its path under the base address, and what it carries. */
internal class Endpoint(
    val method: String,
    val path: String,
    val query: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
) {
    companion object {
        fun get(
            path: String,
            query: List<Pair<String, String>> = emptyList(),
        ) = Endpoint("GET", path, query)

        fun post(
            path: String,
            body: ByteArray? = null,
        ) = Endpoint("POST", path, body = body)
    }
}

/**
 * Sends one request on the no-redirect client and reads the answer as text. It adds the `Authorization` header through
 * the request's tag, never puts a credential in a URL, and turns every failure into a [MediaServerError] with no URL
 * in it. A 3xx is `Redirected`; a 401 on a call that sent a token is `SessionExpired`.
 */
internal class Transport(
    private val clients: ClientFactory,
    private val deviceIds: DeviceIdProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun send(
        address: ServerAddress,
        endpoint: Endpoint,
        token: AccessToken? = null,
    ): ServerResult<String> {
        val url =
            address.baseUrl
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addPathSegments(endpoint.path)
                ?.apply { endpoint.query.forEach { (name, value) -> addQueryParameter(name, value) } }
                ?.build()
                ?: return ServerResult.Failure(MediaServerError.InvalidAddress())
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .tag(AuthTag::class.java, AuthTag(deviceIds.deviceId(), token))
                .method(endpoint.method, requestBody(endpoint))
                .build()
        return try {
            // Main-safe: the call and the body read run on `io`, not on the caller's dispatcher `await` resumes on.
            withContext(io) {
                clients.credentialed
                    .newCall(request)
                    .await()
                    .use { read(it, tokenBearing = token != null) }
            }
        } catch (error: IOException) {
            val secure = address.scheme == Scheme.Https
            ServerResult.Failure(ErrorMapper.map(error, tokenBearing = token != null, secure = secure))
        }
    }

    private fun requestBody(endpoint: Endpoint) =
        if (endpoint.method == "GET") null else (endpoint.body ?: ByteArray(0)).toRequestBody(JSON)

    private fun read(
        response: Response,
        tokenBearing: Boolean,
    ): ServerResult<String> =
        when {
            response.code in REDIRECTS -> ServerResult.Failure(MediaServerError.Redirected())
            !response.isSuccessful -> ServerResult.Failure(ErrorMapper.fromStatus(response.code, tokenBearing))
            else -> readLimited(response)
        }

    private fun readLimited(response: Response): ServerResult<String> {
        val source = response.body.source()
        source.request(MAX_BODY_BYTES + 1L)
        return if (source.buffer.size > MAX_BODY_BYTES) {
            ServerResult.Failure(MediaServerError.ServerError())
        } else {
            ServerResult.Success(source.buffer.readUtf8())
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val REDIRECTS = 300..399
    }
}
