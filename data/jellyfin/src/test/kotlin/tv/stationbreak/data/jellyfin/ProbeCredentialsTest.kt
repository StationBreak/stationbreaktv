// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The probe sends no credentials of any kind (spec, "Scheme resolution"). */
class ProbeCredentialsTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun anAddressTheUrlParserRefusesIsAnInvalidAddressThatNamesNoHost() {
        // Invalid punycode passes the host rules but not HttpUrl; the exception text would hold the host.
        val address = ServerAddress(Scheme.Http, "xn--zzzzzzzzzzzz.example", 80)
        val result = runBlocking { ServerProbe(OkHttpClient()).probe(address) }
        val error = (result as tv.stationbreak.core.domain.ServerResult.Failure).error
        assertEquals(
            tv.stationbreak.core.domain.MediaServerError
                .InvalidAddress(),
            error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theRequestLogShowsNoAuthorizationAndNoCredentialsInTheUrl() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("{}")
                .build(),
        )
        runBlocking { ServerProbe(OkHttpClient()).probe(ServerAddress(Scheme.Http, "127.0.0.1", server.port)) }

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/System/Info/Public", request.url.encodedPath)
        assertNull(request.url.encodedQuery)
        assertNull(request.url.fragment)
        assertEquals("", request.url.username)
        assertEquals("", request.url.password)
        for (name in listOf(
            "Authorization",
            "Proxy-Authorization",
            "X-Emby-Authorization",
            "X-Emby-Token",
            "X-MediaBrowser-Token",
            "Cookie",
        )) {
            assertNull(request.headers[name], name)
        }
        // Nothing but the plain transport headers.
        val sent =
            request.headers
                .names()
                .map { it.lowercase() }
                .toSet()
        val plain = setOf("host", "connection", "accept-encoding", "user-agent", "accept")
        assertTrue(sent.all { it in plain }, sent.toString())
    }
}
