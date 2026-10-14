// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Angua (Security) R9: the probe inherits trust from the base client and nothing that could carry a credential. */
class ProbeIsolationTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun interceptorsCookiesAndAuthenticatorsOfTheBaseClientNeverReachTheProbe() {
        val jar =
            object : CookieJar {
                override fun loadForRequest(url: HttpUrl) =
                    listOf(
                        Cookie
                            .Builder()
                            .name("session")
                            .value("x")
                            .domain(url.host)
                            .build(),
                    )

                override fun saveFromResponse(
                    url: HttpUrl,
                    cookies: List<Cookie>,
                ) = Unit
            }
        val base =
            OkHttpClient
                .Builder()
                .cookieJar(jar)
                .authenticator(
                    Authenticator { _, response ->
                        response.request
                            .newBuilder()
                            .header("Authorization", "Basic x")
                            .build()
                    },
                ).proxyAuthenticator(
                    Authenticator {
                        _,
                        response,
                        ->
                        response.request
                            .newBuilder()
                            .header("Proxy-Authorization", "Basic x")
                            .build()
                    },
                ).addInterceptor {
                    it.proceed(
                        it
                            .request()
                            .newBuilder()
                            .header("Authorization", "MediaBrowser Token=\"t\"")
                            .build(),
                    )
                }.addNetworkInterceptor {
                    it.proceed(
                        it
                            .request()
                            .newBuilder()
                            .header("X-Emby-Token", "t")
                            .build(),
                    )
                }.build()
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body(JELLYFIN_BODY)
                .build(),
        )

        runBlocking { ServerProbe(base).probe(ServerAddress(Scheme.Http, "127.0.0.1", server.port)) }

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        val credentialHeaders = listOf("Authorization", "Proxy-Authorization", "X-Emby-Token", "Cookie")
        for (name in credentialHeaders) assertNull(request.headers[name], name)
        assertTrue(server.requestCount == 1, "a 401 must not be retried with credentials from an authenticator")
    }
}
