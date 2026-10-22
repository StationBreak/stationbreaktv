// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.SubtitleDelivery
import tv.stationbreak.core.playback.PlaybackTestData.audio
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import tv.stationbreak.core.playback.PlaybackTestData.subtitle
import tv.stationbreak.core.playback.PlaybackTestData.video

class ExpectedRouteTest {
    private val caps = insigniaLike()

    private fun route(vararg streams: tv.stationbreak.core.model.MediaStreamInfo) =
        ExpectedRoute.of(caps, source(streams = streams.toList()), null)

    @Test
    fun h264AacMp4DirectPlaysOnAnInsigniaLikeDevice() {
        assertEquals(Route.DIRECT_PLAY, ExpectedRoute.of(caps, source(), null))
    }

    @Test
    fun hevcTranscodesWithoutAnHevcDecoder() {
        assertEquals(Route.TRANSCODE, route(video(Codecs.HEVC, Codecs.MAIN, 120), audio()))
    }

    @Test
    fun level51H264Transcodes() {
        assertEquals(Route.TRANSCODE, route(video(level = 51), audio()))
    }

    @Test
    fun anOversizedOrTenBitOrUnsupportedProfileVideoTranscodes() {
        assertEquals(Route.TRANSCODE, route(video(width = 3840, height = 2160), audio()))
        assertEquals(Route.TRANSCODE, route(video(bitDepth = 10), audio()))
        assertEquals(Route.TRANSCODE, route(video(profile = Codecs.HIGH_10), audio()))
    }

    @Test
    fun aLimitTheServerDidNotReportIsNotHeldAgainstTheFile() {
        assertEquals(Route.DIRECT_PLAY, route(video(profile = null, level = null, bitDepth = null), audio()))
    }

    @Test
    fun dtsWithoutPassthroughDirectStreams() {
        assertEquals(Route.DIRECT_STREAM, route(video(), audio(1, Codecs.DTS, 6)))
    }

    @Test
    fun tooManyDecodedChannelsDirectStreams() {
        assertEquals(Route.DIRECT_STREAM, route(video(), audio(1, Codecs.AC3, 6)))
    }

    @Test
    fun videoThatDoesNotFitTranscodesWhateverTheAudio() {
        assertEquals(Route.TRANSCODE, route(video(level = 51), audio(1, Codecs.DTS, 6)))
    }

    @Test
    fun ac3WithPassthroughDirectPlays() {
        val withPassthrough = insigniaLike(audioDecoders = setOf(Codecs.AAC), passthrough = setOf(Codecs.AC3))
        val s = source(streams = listOf(video(), audio(1, Codecs.AC3, 6)))
        assertEquals(Route.DIRECT_PLAY, ExpectedRoute.of(withPassthrough, s, null))
    }

    @Test
    fun anImageSubtitleTranscodes() {
        val sub = subtitle(3, "pgssub", SubtitleDelivery.BURNED_IN)
        assertEquals(Route.TRANSCODE, ExpectedRoute.of(caps, source(), sub))
    }

    @Test
    fun aTextSubtitleDoesNotChangeTheRoute() {
        assertEquals(Route.DIRECT_PLAY, ExpectedRoute.of(caps, source(), subtitle(3)))
    }

    @Test
    fun aContainerTheDeviceCannotOpenDirectStreams() {
        assertEquals(Route.DIRECT_STREAM, ExpectedRoute.of(caps, source(container = Codecs.AVI), null))
    }

    @Test
    fun aCodecWithNoSupportEntryIsNotPlayable() {
        val none = insigniaLike(videoCodecs = emptyList())
        assertEquals(Route.TRANSCODE, ExpectedRoute.of(none, source(), null))
    }
}
