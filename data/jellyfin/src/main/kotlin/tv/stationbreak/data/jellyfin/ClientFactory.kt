// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.Authenticator
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.time.Duration
import kotlin.time.toJavaDuration

/**
 * The clients for calls that carry credentials (R9, R10), derived from the injected base client with `newBuilder()`
 * so its trust manager and pin carry over. Everything else the base client brought (interceptors, cookie jar,
 * authenticators) is dropped: it could add a second `Authorization` or a credential of its own. The header is added
 * by [AuthHeaderInterceptor]. Modern TLS or cleartext only (no `COMPATIBLE_TLS`).
 *
 * [credentialed] follows no redirect: every credentialed call uses it, and a 3xx is `Redirected`. [following] is the
 * same client with redirects on; the adapter never uses it, it exists so the two-host test can show the header
 * doesn't survive a cross-host redirect even there.
 */
internal class ClientFactory(
    source: HttpClientSource,
    private val header: AuthHeaderBuilder,
    private val timeouts: ProbeTimeouts,
    private val testInterceptors: List<Interceptor> = emptyList(),
) {
    constructor(
        base: OkHttpClient,
        header: AuthHeaderBuilder,
        timeouts: ProbeTimeouts,
        testInterceptors: List<Interceptor> = emptyList(),
    ) : this(HttpClientSource { base }, header, timeouts, testInterceptors)

    // The base is the pinned client, replaced whenever a pin changes: both are derived again from whatever is current,
    // so a pin change reaches every client at once and none keeps the trust it had.
    private val credentialedClient = Derived(source) { derive(it).redirects(false) }

    private val mediaClient =
        Derived(source) {
            derive(it)
                .redirects(false)
                .newBuilder()
                .callTimeout(Duration.ZERO)
                .readTimeout(MEDIA_READ_TIMEOUT)
                .build()
        }

    private val followingClient = Derived(source) { derive(it).redirects(true) }

    val credentialed: OkHttpClient get() = credentialedClient.get()

    val following: OkHttpClient get() = followingClient.get()

    /**
     * The credentialed client for the player's media requests: the same interceptors, trust and no-redirect rule, but
     * with no call timeout (a call is a whole playlist or segment read and, for a long-lived stream, can last as long
     * as the player wants) and a 30 s read timeout, so a server that stops sending is still noticed.
     */
    val media: OkHttpClient get() = mediaClient.get()

    private fun derive(base: OkHttpClient): OkHttpClient =
        base
            .newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
                addInterceptor(AuthHeaderInterceptor(header))
                testInterceptors.forEach(::addInterceptor)
            }.cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .connectTimeout(timeouts.connect.toJavaDuration())
            .readTimeout(timeouts.read.toJavaDuration())
            .callTimeout(timeouts.call.toJavaDuration())
            .build()

    private fun OkHttpClient.redirects(follow: Boolean): OkHttpClient =
        newBuilder().followRedirects(follow).followSslRedirects(follow).build()

    private companion object {
        val MEDIA_READ_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
