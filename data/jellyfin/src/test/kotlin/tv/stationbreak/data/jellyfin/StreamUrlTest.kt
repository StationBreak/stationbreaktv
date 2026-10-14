// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.errorOrNull
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlaybackInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R2: stream URLs are built by the adapter from IDs and carry no credential. A server's own URLs (a transcoding URL
 * with `ApiKey=<token>`, a direct-stream URL) and its file `Path` never reach the port's output, `toString`, the log
 * seam or an error.
 */
class StreamUrlTest {
    private val jellyfin = FakeJellyfin()
    private val serverToken = fake("server-token")
    private val path = "/srv/media/private-share/Heat (1995).mkv"

    @AfterTest
    fun tearDown() = jellyfin.close()

    private val hostile =
        "{\"MediaSources\":[{\"Id\":\"src1\",\"Name\":\"Heat\",\"Container\":\"mkv\",\"RunTimeTicks\":36000000000," +
            "\"Path\":\"$path\",\"SupportsDirectPlay\":true,\"SupportsDirectStream\":true," +
            "\"SupportsTranscoding\":true," +
            "\"TranscodingUrl\":\"/videos/i1/master.m3u8?MediaSourceId=src1&ApiKey=$serverToken&DeviceId=d\"," +
            "\"DirectStreamUrl\":\"/videos/i1/stream.mkv?Static=true&api_key=$serverToken\"," +
            "\"MediaStreams\":[{\"Path\":\"$path\",\"DeliveryUrl\":\"/s?ApiKey=$serverToken\"}]}]," +
            "\"PlaySessionId\":\"play-1\"}"

    private fun info(
        sessionToken: String = fake("token"),
        subpath: String = "",
    ): Pair<PlaybackInfo?, List<String>> {
        val lines = mutableListOf<String>()
        var result: PlaybackInfo? = null
        runBlocking {
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json(hostile) }
            val address = jellyfin.address.copy(subpath = subpath)
            val adapter = testAdapter(log = AdapterLog { lines += it })
            result = adapter.playbackInfo(testSession(address, sessionToken), ItemId("i1")).getOrNull()
        }
        return result to lines
    }

    @Test
    fun nothingTheServerSuppliedAsAUrlOrPathReachesTheOutput() {
        val (info, logged) = info()

        val everything =
            listOf(
                info.toString(),
                info?.sources.toString(),
                info?.streams?.directStream(MediaSourceId("src1")).orEmpty(),
                logged.joinToString("\n"),
            ).joinToString("\n")
        for (forbidden in listOf(serverToken, "ApiKey", "api_key", "private-share", "master.m3u8", "TranscodingUrl")) {
            assertFalse(forbidden in everything, "'$forbidden' leaked: $everything")
        }
        assertEquals(1, info?.sources?.size)
    }

    @Test
    fun theErrorsOfACallThatSawAHostileAnswerCarryNoneOfItEither() {
        runBlocking {
            val withError = hostile.replace("\"PlaySessionId\"", "\"ErrorCode\":\"X\",\"PlaySessionId\"")
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.json(withError) }

            val error = testAdapter().playbackInfo(testSession(jellyfin.address), ItemId("i1")).errorOrNull()

            assertTrue(error is MediaServerError)
            val text = error.toString()
            assertFalse(serverToken in text || "private-share" in text, text)
        }
    }

    @Test
    fun theUrlIsBuiltFromIdsAndTheBaseAddressWithNoCredential() {
        val token = fake("token")
        val (info, _) = info(token)

        val url = info!!.streams.directStream(MediaSourceId("src1")).toHttpUrl()

        assertEquals("/Videos/i1/stream", url.encodedPath)
        assertEquals("true", url.queryParameter("static"))
        assertEquals("src1", url.queryParameter("mediaSourceId"))
        assertEquals(setOf("static", "mediaSourceId", "playSessionId"), url.queryParameterNames)
        assertEquals("", url.username)
        assertEquals("", url.password)
        assertEquals("127.0.0.1", url.host)
        assertEquals(jellyfin.server.port, url.port)
        assertFalse(token in url.toString() || serverToken in url.toString())
    }

    @Test
    fun theUrlKeepsTheSubpath() {
        val (info, _) = info(subpath = "/media/jf")

        val url = info!!.streams.directStream(MediaSourceId("src1")).toHttpUrl()

        assertEquals("/media/jf/Videos/i1/stream", url.encodedPath)
    }

    @Test
    fun aSourceIdIsEncodedNotInterpreted() {
        val (info, _) = info()

        val url = info!!.streams.directStream(MediaSourceId("a&b=c/../d")).toHttpUrl()

        assertEquals("a&b=c/../d", url.queryParameter("mediaSourceId"))
        assertEquals("/Videos/i1/stream", url.encodedPath)
        assertNull(url.queryParameter("b"))
    }

    @Test
    fun theSessionIdTravelsOnlyAsAPlayerHint() {
        val (info, _) = info()

        val url = info!!.streams.directStream(MediaSourceId("src1")).toHttpUrl()

        assertEquals("play-1", url.queryParameter("playSessionId"))
        assertTrue(
            info.playSessionId
                .toString()
                .contains("play-1")
                .not(),
            "a play session ID isn't printed",
        )
    }

    @Test
    fun aFailureIsAFailure() {
        runBlocking {
            jellyfin.on("POST", "/PlaybackInfo") { jellyfin.status(500) }

            val result = testAdapter().playbackInfo(testSession(jellyfin.address), ItemId("i1"))

            assertTrue(result is ServerResult.Failure)
        }
    }
}
