// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers every call itself and keeps what each carried when it got here: the `Authorization` header and the URL. */
private class Recorder : Interceptor {
    val seen = CopyOnWriteArrayList<Pair<String, String?>>()

    /** Off while the test signs in against the real fake server; on, nothing leaves the process. */
    @Volatile
    var armed = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!armed) return chain.proceed(request)
        seen += request.url.toString() to request.header("Authorization")
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("".toResponseBody())
            .build()
    }
}

class MediaCallFactoryTest {
    private val jellyfin = FakeJellyfin()
    private val recorder = Recorder()
    private val token = fake("token")

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun adapter() = testAdapter(tuning = AdapterTuning(ProbeTimeouts(), listOf(recorder)))

    /** A session this adapter issued, so it is bound to its server. */
    private fun signedIn(
        adapter: JellyfinMediaServer,
        address: ServerAddress = jellyfin.address,
        acknowledgement: CleartextAcknowledgement? = null,
    ): Session {
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token)) }
        return runBlocking {
            adapter.signIn(SignInRequest(address, "alice", "pw".toCharArray(), acknowledgement)).getOrNull()!!
        }.also {
            recorder.seen.clear()
            recorder.armed = true
        }
    }

    private fun Call.Factory.get(url: String): String? {
        newCall(Request.Builder().url(url).build()).execute().close()
        return recorder.seen.last().second
    }

    private val origin get() = "http://127.0.0.1:${jellyfin.server.port}"

    @Test
    fun theHeaderGoesToTheSessionOriginAndBasePath() {
        val adapter = adapter()
        val session = signedIn(adapter, jellyfin.address.copy(subpath = "/media/jf"))
        val factory = adapter.mediaCallFactory { session }

        val header = factory.get("$origin/media/jf/Videos/i1/master.m3u8?x=1")

        assertTrue(header!!.contains("Token=\"$token\""), header)
        assertTrue(header.contains("DeviceId=\"$TEST_DEVICE_ID\""), header)
        assertNotNull(factory.get("$origin/media/jf"))
    }

    @Test
    fun theHeaderNeverGoesToAnotherOrigin() {
        val adapter = adapter()
        val session = signedIn(adapter)
        val factory = adapter.mediaCallFactory { session }

        assertNull(factory.get("http://localhost:${jellyfin.server.port}/Videos/i1/master.m3u8"), "host")
        assertNull(factory.get("http://127.0.0.1:${jellyfin.server.port + 1}/Videos/i1/master.m3u8"), "port")
        assertNull(factory.get("https://127.0.0.1:${jellyfin.server.port}/Videos/i1/master.m3u8"), "scheme")
        assertNull(factory.get("http://127.0.0.1.evil.example:${jellyfin.server.port}/x"), "lookalike host")
        assertNull(factory.get("http://127.0.0.1:${jellyfin.server.port}@evil.example/x"), "userinfo")
    }

    @Test
    fun theHeaderNeverGoesToAnotherPathOnTheSameOrigin() {
        val adapter = adapter()
        val session = signedIn(adapter, jellyfin.address.copy(subpath = "/media/jf"))
        val factory = adapter.mediaCallFactory { session }

        assertNull(factory.get("$origin/Videos/i1/master.m3u8"), "root")
        assertNull(factory.get("$origin/media/jf2/Videos/i1/master.m3u8"), "sibling prefix")
        assertNull(factory.get("$origin/media/jf/../other/x"), "dot segments")
        assertNull(factory.get("$origin/media/%2e%2e/other/x"), "encoded dot segments")
    }

    @Test
    fun aCallerSuppliedAuthorizationNeverTravelsOn() {
        val adapter = adapter()
        val session = signedIn(adapter)
        val factory = adapter.mediaCallFactory { session }

        factory
            .newCall(
                Request
                    .Builder()
                    .url("http://localhost:${jellyfin.server.port}/x")
                    .header("Authorization", "x")
                    .build(),
            ).execute()
            .close()

        assertNull(recorder.seen.last().second)
    }

    @Test
    fun anUnacknowledgedCleartextSessionGetsNoHeader() {
        val adapter = adapter()
        val address = jellyfin.fallbackAddress
        val acknowledged = signedIn(adapter, address, CleartextAcknowledgement(address))
        val factory = adapter.mediaCallFactory { acknowledged.copy(acknowledgement = null) }

        assertNull(factory.get("$origin/Videos/i1/master.m3u8"))
        assertNotNull(adapter.mediaCallFactory { acknowledged }.get("$origin/Videos/i1/master.m3u8"))
    }

    @Test
    fun anUnboundSessionGetsNoHeader() {
        val adapter = adapter()
        recorder.armed = true
        val foreign = testSession(jellyfin.address, token)

        assertNull(adapter.mediaCallFactory { foreign }.get("$origin/Videos/i1/master.m3u8"))
        assertNull(adapter.mediaCallFactory { null }.get("$origin/Videos/i1/master.m3u8"))
    }

    @Test
    fun theSessionIsResolvedPerRequest() {
        val adapter = adapter()
        val session = signedIn(adapter)
        var current: Session? = null
        val factory = adapter.mediaCallFactory { current }

        assertNull(factory.get("$origin/Videos/i1/master.m3u8"))
        current = session
        assertNotNull(factory.get("$origin/Videos/i1/master.m3u8"))
        current = null
        assertNull(factory.get("$origin/Videos/i1/master.m3u8"))
    }

    @Test
    fun aRedirectIsNotFollowed() {
        val plain = testAdapter()
        val session = signedIn(plain)
        jellyfin.on("GET", "/Videos/i1/master.m3u8") { jellyfin.redirect("http://127.0.0.1:1/elsewhere") }

        val request = Request.Builder().url("$origin/Videos/i1/master.m3u8").build()
        val response = plain.mediaCallFactory { session }.newCall(request).execute()

        response.use { assertEquals(302, it.code) }
        assertEquals(1, jellyfin.requests("/Videos/i1/master.m3u8").size)
    }

    private fun Call.Factory.send(request: Request): String? {
        newCall(request).execute().close()
        return recorder.seen.last().second
    }

    @Test
    fun anApiKeyInTheQueryIsStrippedOnTheSessionOrigin() {
        val adapter = adapter()
        val session = signedIn(adapter)
        val factory = adapter.mediaCallFactory { session }

        val header = factory.get("$origin/Videos/i1/main.m3u8?api_key=SECRET&a=1&ApiKey=SECRET&APIKEY=SECRET&API_KEY=S")

        assertNotNull(header)
        assertEquals("$origin/Videos/i1/main.m3u8?a=1", recorder.seen.last().first)
    }

    @Test
    fun anApiKeyInTheQueryIsStrippedOnAnotherOriginToo() {
        val adapter = adapter()
        val session = signedIn(adapter)
        val factory = adapter.mediaCallFactory { session }

        val header = factory.get("http://localhost:${jellyfin.server.port}/x.ts?ApiKey=SECRET&api_key=SECRET&b=2")

        assertNull(header)
        assertEquals("http://localhost:${jellyfin.server.port}/x.ts?b=2", recorder.seen.last().first)
    }

    @Test
    fun anEncodedSeparatorInThePathGetsNoHeader() {
        val adapter = adapter()
        val session = signedIn(adapter)
        val factory = adapter.mediaCallFactory { session }

        assertNull(factory.get("$origin/Videos/i1%2fx/master.m3u8"), "%2f")
        assertNull(factory.get("$origin/Videos/i1%2Fx/master.m3u8"), "%2F")
        assertNull(factory.get("$origin/Videos/i1%5cx/master.m3u8"), "%5c")
        assertNull(factory.get("$origin/Videos/i1%5Cx/master.m3u8"), "%5C")
        assertNotNull(factory.get("$origin/Videos/i1%20x/master.m3u8"), "other escapes are fine")
    }

    private fun Call.Factory.sendAs(method: String): String? {
        val body = if (method == "GET" || method == "HEAD" || method == "DELETE") null else "x".toRequestBody()
        return send(
            Request
                .Builder()
                .url("$origin/Videos/i1/master.m3u8")
                .method(method, body)
                .build(),
        )
    }

    @Test
    fun onlyGetAndHeadCarryTheHeader() {
        val adapter = adapter()
        val session = signedIn(adapter)
        val factory = adapter.mediaCallFactory { session }

        for (method in listOf("GET", "HEAD")) assertNotNull(factory.sendAs(method), method)
        for (method in listOf("POST", "PUT", "DELETE", "PATCH")) assertNull(factory.sendAs(method), method)
    }

    @Test
    fun theMediaClientHasNoCallTimeoutAndAThirtySecondRead() {
        val clients = adapter().clients
        val media = clients.media
        val credentialed = clients.credentialed

        assertEquals(0, media.callTimeoutMillis)
        assertEquals(30_000, media.readTimeoutMillis)
        assertFalse(media.followRedirects)
        assertFalse(media.followSslRedirects)
        assertEquals(credentialed.interceptors.map { it::class }, media.interceptors.map { it::class })
        assertEquals(credentialed.connectionSpecs, media.connectionSpecs)
        assertEquals(credentialed.connectTimeoutMillis, media.connectTimeoutMillis)
        assertEquals(credentialed.hostnameVerifier, media.hostnameVerifier)
        assertEquals(credentialed.certificatePinner, media.certificatePinner)
        assertTrue(credentialed.callTimeoutMillis > 0, "the API client keeps its call timeout")
    }
}
