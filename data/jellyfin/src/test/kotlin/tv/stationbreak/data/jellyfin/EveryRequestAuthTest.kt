// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Request
import okio.Buffer
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemLogo
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.SubtitleDelivery
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #214 (ADR-0006: Jellyfin 12.2 serves a static stream to a request with no `Authorization`, so the container
 * suite can't see a missing header). Every request the app makes for media goes out through one of two paths, and both
 * are driven here with the URLs the app really builds, against a local server that records what arrived:
 *  - streams (and subtitles, playlists, segments): [mediaCallFactory], which `PlayerModule` hands to Media3's OkHttp
 *    data source, with the URLs from `HlsUrls` (direct stream and HLS) and a playlist URL that names an `api_key`;
 *  - images: `logo`, the only image request (there is no image loader).
 * Each must carry the header, and no URL may carry a credential. Messages name the path, never a URL or a token.
 */
class EveryRequestAuthTest {
    private val jellyfin = FakeJellyfin()
    private val token = fake("token")

    @AfterTest
    fun tearDown() = jellyfin.close()

    /** Mixed-case token names beyond `api_key`, as a server-supplied URL might carry them. */
    private val other = "x-mediabrowser-TOKEN=$token&x-emby-authorization"

    private val credentialNames =
        listOf(
            "api_key",
            "apikey",
            "token",
            "access_token",
            "x-emby-token",
            "x-mediabrowser-token",
            "x-emby-authorization",
            "password",
            "pw",
        )

    @Test
    fun everyStreamAndImageRequestCarriesTheHeaderAndNoUrlCarriesACredential() {
        val adapter = testAdapter()
        jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.json(authResult(token)) }
        jellyfin.on("POST", "/PlaybackInfo") {
            jellyfin.json("{\"MediaSources\":[{\"Id\":\"src1\"}],\"PlaySessionId\":\"play-1\"}")
        }
        jellyfin.on("GET", "/Images/Logo") {
            MockResponse
                .Builder()
                .addHeader("Content-Type", "image/png")
                .body(Buffer().write(byteArrayOf(1, 2, 3)))
                .build()
        }
        jellyfin.on("GET", "/stream") { jellyfin.json("{}") }
        jellyfin.on("GET", "/master.m3u8") { jellyfin.json("{}") }
        jellyfin.on("GET", "/seg.ts") { jellyfin.json("{}") }
        jellyfin.on("GET", "/Stream.vtt") { jellyfin.json("{}") }
        val session =
            runBlocking {
                adapter.signIn(SignInRequest(jellyfin.address, "alice", "pw".toCharArray(), null)).getOrNull()!!
            }
        val streams = runBlocking { adapter.playbackInfo(session, ItemId("i1")).getOrNull()!!.streams }
        val source = MediaSource(MediaSourceId("src1"), null, "mkv", null, true, true)
        val vtt =
            MediaStreamInfo(
                2,
                StreamKind.SUBTITLE,
                "subrip",
                null,
                null,
                null,
                null,
                true,
                false,
                false,
                SubtitleDelivery.SEPARATE,
            )
        val media = adapter.mediaCallFactory { session }
        val origin = "http://127.0.0.1:${jellyfin.server.port}"
        jellyfin.log.clear()

        val streamUrls =
            mapOf(
                "direct" to streams.original(source.id),
                "hls" to streams.hls(source, null, null, insigniaCapabilities(), false),
                "subtitle" to streams.subtitle(source.id, vtt)!!,
                // What a server's own playlist can list: its credential must not leave.
                "segment" to "$origin/Videos/i1/seg.ts?api_key=$token&ApiKey=$token&keep=1",
                "segment, other names" to "$origin/Videos/i1/seg.ts?X-EMBY-token=$token&$other=$token",
            )
        for (url in streamUrls.values) media.newCall(Request.Builder().url(url).build()).execute().close()
        runBlocking { adapter.logo(session, ItemLogo(ItemId("i1"), "tag1"), 192) }

        val paths = streamUrls.keys + "image"
        assertEquals(paths.size, jellyfin.log.size, "one request per path")
        for ((path, request) in paths.zip(jellyfin.log)) {
            val header = request.headers["Authorization"]
            assertNotNull(header, "$path: no Authorization header")
            assertTrue(header.contains("Token=\"$token\""), "$path: header lacks the session token")
            assertNoCredentialInUrl(path, request)
        }
    }

    private fun assertNoCredentialInUrl(
        path: String,
        request: RecordedRequest,
    ) {
        val url = request.url
        assertEquals("", url.username, "$path: userinfo")
        assertEquals("", url.password, "$path: userinfo")
        for (name in url.queryParameterNames) {
            assertTrue(credentialNames.none { it.equals(name, ignoreCase = true) }, "$path: query parameter $name")
        }
        assertTrue(token !in url.toString(), "$path: token in the URL")
    }
}
