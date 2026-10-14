// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.SignInRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedirectedCredentialedCallTest {
    private val jellyfin = FakeJellyfin()

    @AfterTest
    fun tearDown() = jellyfin.close()

    @Test
    fun theCredentialedClientFollowsNoRedirectAndSpeaksModernTlsOrCleartextOnly() {
        val factory = ClientFactory(OkHttpClient(), AuthHeaderBuilder(TEST_IDENTITY), ProbeTimeouts())
        val client = factory.credentialed
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT), client.connectionSpecs)
        assertFalse(ConnectionSpec.COMPATIBLE_TLS in client.connectionSpecs)
        assertEquals(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT), factory.following.connectionSpecs)
    }

    @Test
    fun aThreeXxOnAPasswordSignInIsRedirectedAndNothingIsFollowed() {
        val other = MockWebServer().apply { start() }
        try {
            jellyfin.on("POST", "/Users/AuthenticateByName") {
                jellyfin.redirect(other.url("/Users/AuthenticateByName").toString())
            }
            val result = signIn()
            assertIs<MediaServerError.Redirected>(result.errorOrNull())
            assertEquals(0, other.requestCount)
        } finally {
            other.close()
        }
    }

    @Test
    fun everyRedirectStatusIsRefusedOnACredentialedCall() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            jellyfin.on("POST", "/Users/AuthenticateByName") {
                jellyfin
                    .status(code)
                    .newBuilder()
                    .addHeader("Location", "http://127.0.0.1:1/")
                    .build()
            }
            assertIs<MediaServerError.Redirected>(signIn().errorOrNull(), code.toString())
        }
    }

    private fun signIn(): ServerResult<tv.stationbreak.core.model.Session> =
        runBlocking {
            testAdapter().signIn(SignInRequest(jellyfin.address, "alice", "pw".toCharArray(), null))
        }
}

class TwoHostRedirectTest {
    private val first = MockWebServer().apply { start() }
    private val second = MockWebServer().apply { start() }

    @AfterTest
    fun tearDown() {
        first.close()
        second.close()
    }

    @Test
    fun aRedirectFollowingClientBuiltTheSameWayNeverSendsTheTokenToTheSecondHost() {
        second.enqueue(
            mockwebserver3.MockResponse
                .Builder()
                .code(200)
                .body("{}")
                .build(),
        )
        first.enqueue(
            mockwebserver3.MockResponse
                .Builder()
                .code(302)
                // 127.0.0.1 and localhost are different hosts to OkHttp.
                .addHeader("Location", "http://localhost:${second.port}/landing")
                .build(),
        )
        val factory = ClientFactory(OkHttpClient(), AuthHeaderBuilder(TEST_IDENTITY), ProbeTimeouts())
        val request =
            Request
                .Builder()
                .url(first.url("/start"))
                .tag(AuthTag::class.java, AuthTag(TEST_DEVICE_ID, AccessToken("fake-token-for-redirect")))
                .build()
        factory.following
            .newCall(request)
            .execute()
            .close()

        val atFirst = first.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(1, atFirst.headers.values("Authorization").size)
        assertTrue(atFirst.headers["Authorization"]!!.contains("Token="))
        val atSecond = second.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/landing", atSecond.url.encodedPath)
        assertNull(atSecond.headers["Authorization"])
    }
}

class SingleAuthorizationHeaderTest {
    private val jellyfin = FakeJellyfin()

    @AfterTest
    fun tearDown() = jellyfin.close()

    @Test
    fun aBaseClientCarryingItsOwnAuthorizationCannotAddASecondOne() {
        val rogue =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    Interceptor {
                        it.proceed(
                            it
                                .request()
                                .newBuilder()
                                .addHeader("Authorization", "Bearer rogue")
                                .build(),
                        )
                    },
                ).addNetworkInterceptor(
                    Interceptor {
                        it.proceed(
                            it
                                .request()
                                .newBuilder()
                                .addHeader("X-Emby-Token", "rogue")
                                .build(),
                        )
                    },
                ).build()
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult("tok-1")) }
        runBlocking {
            testAdapter(rogue).signIn(SignInRequest(jellyfin.address, "alice", "pw".toCharArray(), null))
        }
        val credentialed = jellyfin.requests("/Users/AuthenticateByName").single()
        assertEquals(1, credentialed.headers.values("Authorization").size)
        assertTrue(credentialed.headers["Authorization"]!!.startsWith("MediaBrowser Client=\"StationBreak\""))
        assertNull(credentialed.headers["X-Emby-Token"])
        assertNull(credentialed.headers["X-Emby-Authorization"])
    }

    @Test
    fun everyRequestAfterTheProbeHasExactlyOneHeaderAndNoCredentialInTheUrl() {
        val token = fake("token")
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token)) }
        jellyfin.on("POST", "/Sessions/Logout") { jellyfin.status(204) }
        val adapter = testAdapter()
        runBlocking {
            val request = SignInRequest(jellyfin.address, "alice", "pw".toCharArray(), null)
            val session = (adapter.signIn(request) as ServerResult.Success).value
            adapter.revoke(session)
        }
        for (request in jellyfin.log) {
            assertNull(request.url.encodedQuery, request.url.encodedPath)
            assertEquals("", request.url.username)
            assertFalse(request.url.toString().contains(token))
            assertNull(request.headers["X-Emby-Authorization"])
            assertNull(request.headers["X-Emby-Token"])
            if (request.url.encodedPath.endsWith("/System/Info/Public")) {
                assertNull(request.headers["Authorization"], "the probe carries no credentials")
            } else {
                assertEquals(1, request.headers.values("Authorization").size, request.url.encodedPath)
            }
        }
        val signInHeader = jellyfin.requests("/Users/AuthenticateByName").single().headers["Authorization"]!!
        assertEquals(
            "MediaBrowser Client=\"StationBreak\", Device=\"Living%20room%20TV\", DeviceId=\"$TEST_DEVICE_ID\", " +
                "Version=\"0.0.1\"",
            signInHeader,
        )
        val logoutHeader = jellyfin.requests("/Sessions/Logout").single().headers["Authorization"]!!
        assertEquals(
            "MediaBrowser Client=\"StationBreak\", Device=\"Living%20room%20TV\", DeviceId=\"$TEST_DEVICE_ID\", " +
                "Version=\"0.0.1\", Token=\"$token\"",
            logoutHeader,
        )
        assertNotNull(logoutHeader)
    }
}
