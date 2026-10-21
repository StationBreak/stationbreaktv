// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.StreamUrls
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class HlsUrlTest {
    private val jellyfin = FakeJellyfin()

    @AfterTest
    fun tearDown() = jellyfin.close()

    private fun streams(subpath: String): StreamUrls =
        runBlocking {
            jellyfin.on("POST", "/PlaybackInfo") {
                jellyfin.json("{\"MediaSources\":[{\"Id\":\"src1\"}],\"PlaySessionId\":\"play-1\"}")
            }
            val session = testSession(jellyfin.address.copy(subpath = subpath))
            testAdapter().playbackInfo(session, ItemId("i1")).getOrNull()!!.streams
        }

    private fun hls(
        request: PlaybackRequest = requestFor(),
        burnIn: Int? = null,
        subpath: String = "",
    ) = streams(subpath).hls(MediaSourceId("src1"), request, burnIn).toHttpUrl()

    @Test
    fun theUrlCarriesTheDeviceLimits() {
        val url = hls(requestFor(insigniaCapabilities(setOf("ac3"), 6), start = 90.seconds, audioIndex = 3))

        assertEquals("/Videos/i1/master.m3u8", url.encodedPath)
        val expected =
            mapOf(
                "MediaSourceId" to "src1",
                "PlaySessionId" to "play-1",
                "VideoCodec" to "h264",
                "AudioCodec" to "aac,ac3",
                "MaxAudioChannels" to "6",
                "TranscodingMaxAudioChannels" to "6",
                "MaxWidth" to "1920",
                "MaxHeight" to "1080",
                "h264-level" to "41",
                // The server's own spelling in its transcoding URL has no space in "constrainedbaseline" (12.1).
                "h264-profile" to "baseline,constrainedbaseline,main,high",
                "h264-videobitdepth" to "8",
                "MaxStreamingBitrate" to "20000000",
                // The maximum less the 192 kb/s the server reserves for stereo AAC, as its own URL computes it (12.1).
                "VideoBitrate" to "19808000",
                // The same 192 kb/s the video budget leaves for the audio; it assumes stereo AAC.
                "AudioBitrate" to "192000",
                "SegmentContainer" to "ts",
                "BreakOnNonKeyFrames" to "True",
                "StartTimeTicks" to "900000000",
                "AllowVideoStreamCopy" to "true",
                "AllowAudioStreamCopy" to "true",
                "AudioStreamIndex" to "3",
            )
        assertEquals(expected, url.queryParameterNames.associateWith { url.queryParameter(it) })
    }

    @Test
    fun theUrlHasNoCredential() {
        val url = hls(requestFor(subtitleIndex = 4), burnIn = 4)

        val text = url.toString()
        for (forbidden in listOf("api_key", "ApiKey", "Token", "token")) assertFalse(forbidden in text, text)
        assertEquals("", url.username)
        assertEquals("", url.password)
    }

    @Test
    fun forceTranscodeStopsVideoCopy() {
        assertEquals("false", hls(requestFor(forceTranscode = true)).queryParameter("AllowVideoStreamCopy"))
    }

    @Test
    fun burnInAddsSubtitleMethodEncode() {
        val burned = hls(burnIn = 7)
        val plain = hls()

        assertEquals("7", burned.queryParameter("SubtitleStreamIndex"))
        assertEquals("Encode", burned.queryParameter("SubtitleMethod"))
        assertNull(plain.queryParameter("SubtitleStreamIndex"))
        assertNull(plain.queryParameter("SubtitleMethod"))
    }

    @Test
    fun aSubpathServerKeepsItsSubpath() {
        assertEquals("/media/jf/Videos/i1/master.m3u8", hls(subpath = "/media/jf").encodedPath)
    }
}
