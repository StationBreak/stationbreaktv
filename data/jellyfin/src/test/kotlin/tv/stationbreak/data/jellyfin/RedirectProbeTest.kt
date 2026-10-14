// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Angua (Security) R9 end to end: the probe follows a redirect only when the policy adopts it, at most 5 times. */
class RedirectProbeTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() = closeables.reversed().forEach { it.close() }

    private fun server(): MockWebServer =
        MockWebServer()
            .apply { start(InetAddress.getByName(LOOPBACK), 0) }
            .also { closeables += it }

    // MockWebServer.url() names the host "localhost"; the addresses under test use the loopback IP.
    private fun MockWebServer.endpoint(path: String = "/System/Info/Public") = "http://$LOOPBACK:$port$path"

    private fun redirect(location: String) =
        MockResponse
            .Builder()
            .code(302)
            .addHeader("Location", location)
            .build()

    private fun ok() =
        MockResponse
            .Builder()
            .code(200)
            .body(JELLYFIN_BODY)
            .build()

    private fun probe(
        address: ServerAddress,
        client: OkHttpClient = OkHttpClient(),
    ): ServerResult<ResolvedServer> = runBlocking { ServerProbe(client).probe(address) }

    @Test
    fun aRedirectToTheSameAddressIsFollowedAndTheAnswerIsUsed() {
        val server = server()
        server.enqueue(redirect(server.endpoint()))
        server.enqueue(ok())

        val resolved = probe(ServerAddress(Scheme.Http, LOOPBACK, server.port)).getOrNull()!!

        assertEquals(2, server.requestCount)
        assertEquals(ServerAddress(Scheme.Http, LOOPBACK, server.port), resolved.address)
    }

    @Test
    fun aRedirectToTheSameHostOnAnotherPortIsRedirected() {
        val first = server()
        val second = server()
        second.enqueue(ok())
        first.enqueue(redirect(second.endpoint()))

        val result = probe(ServerAddress(Scheme.Http, LOOPBACK, first.port))

        assertIs<MediaServerError.Redirected>(result.errorOrNull())
        assertEquals(0, second.requestCount, "the redirect target must not be contacted")
    }

    @Test
    fun aRedirectToAnotherHostIsRedirectedAndNeverContacted() {
        val first = server()
        val second = server()
        second.enqueue(ok())
        // a.test and b.test both resolve to loopback; the target is the other name on the other server's port.
        first.enqueue(redirect("http://b.test:${second.port}/System/Info/Public"))
        val client = OkHttpClient.Builder().dns(loopbackDns("a.test", "b.test")).build()

        val result = probe(ServerAddress(Scheme.Http, "a.test", first.port), client)

        assertIs<MediaServerError.Redirected>(result.errorOrNull())
        assertEquals(0, second.requestCount)
    }

    @Test
    fun aRedirectToAnotherPathIsRedirected() {
        val server = server()
        server.enqueue(redirect(server.endpoint("/login")))

        assertIs<MediaServerError.Redirected>(probe(ServerAddress(Scheme.Http, LOOPBACK, server.port)).errorOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRedirectToANonHttpSchemeOrWithoutALocationIsRedirected() {
        val server = server()
        server.enqueue(redirect("ftp://$LOOPBACK/System/Info/Public"))
        server.enqueue(MockResponse.Builder().code(302).build())

        val address = ServerAddress(Scheme.Http, LOOPBACK, server.port)
        assertIs<MediaServerError.Redirected>(probe(address).errorOrNull())
        assertIs<MediaServerError.Redirected>(probe(address).errorOrNull())
    }

    @Test
    fun atMostFiveRedirectsAreFollowed() {
        val server = server()
        val self = server.endpoint()
        repeat(RedirectPolicy.MAX_REDIRECTS) { server.enqueue(redirect(self)) }
        server.enqueue(ok())
        // 5 redirects then an answer is fine.
        assertEquals(
            ServerAddress(Scheme.Http, LOOPBACK, server.port),
            probe(ServerAddress(Scheme.Http, LOOPBACK, server.port)).getOrNull()?.address,
        )
        assertEquals(RedirectPolicy.MAX_REDIRECTS + 1, server.requestCount)

        // The sixth redirect is one too many.
        repeat(RedirectPolicy.MAX_REDIRECTS + 1) { server.enqueue(redirect(self)) }
        server.enqueue(ok())
        val before = server.requestCount
        assertIs<MediaServerError.Redirected>(probe(ServerAddress(Scheme.Http, LOOPBACK, server.port)).errorOrNull())
        assertEquals(RedirectPolicy.MAX_REDIRECTS + 1, server.requestCount - before)
    }

    @Test
    fun theFollowedRequestsCarryNoCredentials() {
        val server = server()
        server.enqueue(redirect(server.endpoint()))
        server.enqueue(ok())

        probe(ServerAddress(Scheme.Http, LOOPBACK, server.port))

        repeat(2) {
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(null, request.headers["Authorization"])
        }
    }
}
