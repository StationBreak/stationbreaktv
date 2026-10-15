// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.map
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

internal data class ProbeTimeouts(
    val connect: Duration = 10.seconds,
    val read: Duration = 10.seconds,
    val call: Duration = 20.seconds,
)

/**
 * `GET {base}/System/Info/Public`: asks an address who it is, with no credentials of any kind.
 *
 * The client is derived from the injected one (`newBuilder()` keeps its trust configuration), speaks only modern TLS or
 * cleartext, and never lets OkHttp follow a redirect itself: this class follows at most
 * [RedirectPolicy.MAX_REDIRECTS], each only if [RedirectPolicy] adopts it, so a redirect can't leave the server it
 * started from. The answer is size-limited before it is parsed ([ProbeChecks]).
 */
internal class ServerProbe(
    source: HttpClientSource,
    private val timeouts: ProbeTimeouts = ProbeTimeouts(),
    private val testInterceptors: List<Interceptor> = emptyList(),
) {
    constructor(
        baseClient: OkHttpClient,
        timeouts: ProbeTimeouts = ProbeTimeouts(),
        testInterceptors: List<Interceptor> = emptyList(),
    ) : this(HttpClientSource { baseClient }, timeouts, testInterceptors)

    // Only the base client's trust configuration is inherited. Its interceptors, cookie jar and authenticators could
    // add a credential to a request that must carry none (Angua (Security) R9), so they are dropped. `testInterceptors`
    // is the seam for tests to observe or fail calls. The base is the pinned client, replaced whenever a pin changes,
    // so this one is derived again from whatever is current.
    private val derived = Derived(source) { base -> derive(base) }

    private fun derive(baseClient: OkHttpClient): OkHttpClient =
        baseClient
            .newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
                testInterceptors.forEach(::addInterceptor)
            }.cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .connectTimeout(timeouts.connect.toJavaDuration())
            .readTimeout(timeouts.read.toJavaDuration())
            .callTimeout(timeouts.call.toJavaDuration())
            .build()

    private val maxRedirects = RedirectPolicy.MAX_REDIRECTS

    suspend fun probe(address: ServerAddress): ServerResult<ResolvedServer> = follow(address, address, maxRedirects)

    private suspend fun follow(
        original: ServerAddress,
        current: ServerAddress,
        redirectsLeft: Int,
    ): ServerResult<ResolvedServer> =
        when (val answer = fetch(current)) {
            is Answer.Body -> {
                ProbeChecks.check(answer.text).map { ResolvedServer(current, it) }
            }

            is Answer.Failed -> {
                ServerResult.Failure(answer.error)
            }

            is Answer.Redirect -> {
                val decision = answer.target?.let { RedirectPolicy.decide(original, it, ENDPOINT, current) }
                if (decision is RedirectDecision.Adopt && redirectsLeft > 0) {
                    follow(original, decision.address, redirectsLeft - 1)
                } else {
                    ServerResult.Failure(MediaServerError.Redirected())
                }
            }
        }

    private sealed interface Answer {
        class Body(
            val text: String,
        ) : Answer

        class Redirect(
            val target: HttpUrl?,
        ) : Answer

        class Failed(
            val error: MediaServerError,
        ) : Answer
    }

    private suspend fun fetch(address: ServerAddress): Answer {
        // A parser-less failure: toHttpUrl() would throw with the host in its message.
        val url =
            address.baseUrl
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addPathSegments(ENDPOINT)
                ?.build()
                ?: return Answer.Failed(MediaServerError.InvalidAddress())
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()
        return try {
            derived
                .get()
                .newCall(request)
                .await()
                .use(::read)
        } catch (error: IOException) {
            Answer.Failed(ErrorMapper.map(error, secure = address.scheme == Scheme.Https))
        }
    }

    private fun read(response: Response): Answer =
        when {
            response.isRedirect -> Answer.Redirect(response.header("Location")?.let(response.request.url::resolve))

            // The probe asks for a fixed endpoint, so a 404 means "something else is here", not "no such item".
            response.code == HTTP_NOT_FOUND -> Answer.Failed(MediaServerError.NotJellyfin())

            response.code != HTTP_OK -> Answer.Failed(ErrorMapper.fromStatus(response.code))

            else -> readLimited(response)
        }

    private fun readLimited(response: Response): Answer {
        val source = response.body.source()
        source.request(ProbeChecks.MAX_BODY_BYTES + 1L)
        return if (source.buffer.size > ProbeChecks.MAX_BODY_BYTES) {
            Answer.Failed(MediaServerError.NotJellyfin())
        } else {
            Answer.Body(source.buffer.readUtf8())
        }
    }

    companion object {
        const val ENDPOINT = "System/Info/Public"
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
    }
}

internal suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) = continuation.resumeWithException(e)

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) = continuation.resume(response) { response.close() }
            },
        )
    }
