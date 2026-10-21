// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import tv.stationbreak.core.domain.getOrNull
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.StreamUrls
import tv.stationbreak.core.model.SubtitleDelivery
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
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

    private fun source(defaultAudioIndex: Int? = null) =
        MediaSource(MediaSourceId("src1"), null, "mkv", null, true, true, defaultAudioIndex = defaultAudioIndex)

    private fun hls(
        limits: DeviceCapabilities = insigniaCapabilities(),
        audioIndex: Int? = null,
        burnIn: Int? = null,
        start: Duration = Duration.ZERO,
        forceTranscode: Boolean = false,
        source: MediaSource = source(),
        subpath: String = "",
    ) = streams(subpath).hls(source, audioIndex, burnIn, limits, start, forceTranscode).toHttpUrl()

    @Test
    fun theUrlCarriesTheDeviceLimits() {
        val url = hls(insigniaCapabilities(setOf("ac3"), 6), start = 90.seconds, audioIndex = 3)

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
        val url = hls(burnIn = 4)

        val text = url.toString()
        for (forbidden in listOf("api_key", "ApiKey", "Token", "token")) assertFalse(forbidden in text, text)
        assertEquals("", url.username)
        assertEquals("", url.password)
    }

    @Test
    fun forceTranscodeStopsVideoCopy() {
        assertEquals("false", hls(forceTranscode = true).queryParameter("AllowVideoStreamCopy"))
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
    fun theServersResolvedAudioChoiceReachesTheUrl() {
        // No explicit choice: the track PlaybackInfo resolved (the user's preference) is sent, not the file's first.
        val resolved = source(defaultAudioIndex = 2)
        assertEquals("2", hls(source = resolved).queryParameter("AudioStreamIndex"))
        assertEquals("3", hls(audioIndex = 3, source = resolved).queryParameter("AudioStreamIndex"))
        assertNull(hls().queryParameter("AudioStreamIndex"))
    }

    @Test
    fun anIdThatIsADotSegmentIsRefused() {
        val urls = streams("")
        for (id in listOf(".", "..")) {
            val dotted = MediaSource(MediaSourceId(id), null, null, null, true, true)
            assertFailsWith<IllegalArgumentException> { urls.original(dotted.id) }
            assertFailsWith<IllegalArgumentException> {
                urls.hls(dotted, null, null, insigniaCapabilities(), Duration.ZERO, false)
            }
        }
    }

    @Test
    fun aSourceIdWithSeparatorsStaysOneSegment() {
        val stream =
            MediaStreamInfo(
                index = 3,
                kind = StreamKind.SUBTITLE,
                codec = "subrip",
                language = null,
                channels = null,
                width = null,
                height = null,
                isExternal = false,
                isDefault = false,
                isForced = false,
                delivery = SubtitleDelivery.SEPARATE,
            )

        val url = streams("").subtitle(MediaSourceId("a/b"), stream)!!

        assertEquals("/Videos/i1/a%2Fb/Subtitles/3/0/Stream.vtt", url.toHttpUrl().encodedPath)
        assertEquals("/Videos/i1/stream", streams("").original(MediaSourceId("a/b")).toHttpUrl().encodedPath)
    }

    @Test
    fun aSubpathServerKeepsItsSubpath() {
        assertEquals("/media/jf/Videos/i1/master.m3u8", hls(subpath = "/media/jf").encodedPath)
    }
}
