// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import okhttp3.Authenticator
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
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

    private val followingClient = Derived(source) { derive(it).redirects(true) }

    val credentialed: OkHttpClient get() = credentialedClient.get()

    val following: OkHttpClient get() = followingClient.get()

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
}
