// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.SubtitleDelivery
import tv.stationbreak.core.playback.PlaybackTestData.audio
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import tv.stationbreak.core.playback.PlaybackTestData.subtitle
import tv.stationbreak.core.playback.PlaybackTestData.video
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PlaybackResolverTest {
    private val server = FakeMediaServer()
    private val urls = RecordingUrls()
    private val item = ItemId("item1")
    private var caps: DeviceCapabilities = insigniaLike()
    private val resolver = PlaybackResolver(server) { caps }

    private fun answer(media: MediaSource) {
        server.playbackInfoResult =
            ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), listOf(media), urls))
    }

    private suspend fun resolve(
        start: Duration = Duration.ZERO,
        override: TrackOverride? = null,
        force: Boolean = false,
    ): ResolvedPlayback {
        val result = resolver.resolve(PlaybackTestData.session, item, start, override, force)
        return assertIs<ServerResult.Success<ResolvedPlayback>>(result).value
    }

    @Test
    fun nullIndicesLetTheServerChoose() =
        runTest {
            answer(source())
            resolve()
            val request = server.playbackInfoCalls.single().second
            assertNull(request.audioIndex)
            assertNull(request.subtitleIndex)
            assertEquals(caps, request.capabilities)
        }

    @Test
    fun anOverrideIsSentAsExplicitIndices() =
        runTest {
            answer(source(streams = listOf(video(), audio(1), audio(2), subtitle(3))))
            val played = resolve(override = TrackOverride(audioIndex = 2, subtitleIndex = 3))
            val request = server.playbackInfoCalls.single().second
            assertEquals(2, request.audioIndex)
            assertEquals(3, request.subtitleIndex)
            assertEquals(2, played.chosenAudio?.index)
            assertEquals(3, played.chosenSubtitle?.index)
        }

    @Test
    fun theServerDefaultsAreTheChosenTracksAndReachTheUrl() =
        runTest {
            answer(
                source(
                    directPlay = false,
                    streams = listOf(video(), audio(1), audio(2), subtitle(3)),
                    defaultAudio = 2,
                    defaultSubtitle = 3,
                ),
            )
            val played = resolve()
            assertEquals(2, played.chosenAudio?.index)
            assertEquals(3, played.chosenSubtitle?.index)
            assertEquals(2, urls.hlsCalls.single().audioIndex)
            assertEquals(2, played.source.audioIndex)
            assertEquals(3, played.source.subtitleIndex)
        }

    @Test
    fun anOverrideWithSubtitlesOffOverridesTheServerDefault() =
        runTest {
            answer(source(streams = listOf(video(), audio(1), subtitle(3)), defaultSubtitle = 3))
            val played = resolve(override = TrackOverride(audioIndex = null, subtitleIndex = null))
            assertNull(played.chosenSubtitle)
            assertEquals(1, played.chosenAudio?.index)
        }

    @Test
    fun directPlayUsesTheOriginal() =
        runTest {
            answer(source())
            val played = resolve(start = 30.seconds)
            assertEquals(Route.DIRECT_PLAY, played.route)
            assertEquals("https://media.example.com/original/src1", played.source.url)
            assertFalse(played.source.isHls)
            assertTrue(played.source.directPlayTracks)
            assertEquals(Duration.ZERO, played.source.serverOffset)
            assertEquals(30.seconds, played.source.playerStart)
            assertEquals(30.seconds, played.requested)
            assertEquals(PlaySessionId("ps1"), played.playSession)
            assertTrue(urls.hlsCalls.isEmpty())
        }

    @Test
    fun directStreamUsesHls() =
        runTest {
            answer(source(directPlay = false))
            val played = resolve(start = 30.seconds)
            assertEquals(Route.DIRECT_STREAM, played.route)
            assertTrue(played.source.isHls)
            assertFalse(played.source.directPlayTracks)
            assertEquals(30.seconds, played.source.serverOffset)
            assertEquals(Duration.ZERO, played.source.playerStart)
            assertEquals(30.seconds, urls.hlsCalls.single().start)
        }

    @Test
    fun transcodeUsesHls() =
        runTest {
            answer(source(directPlay = false, directStream = false))
            val played = resolve()
            assertEquals(Route.TRANSCODE, played.route)
            assertTrue(played.source.isHls)
            assertEquals("https://media.example.com/hls/src1", played.source.url)
        }

    @Test
    fun aBurnedInSubtitleForcesHlsWithBurnIn() =
        runTest {
            // The server says the file direct plays; the burned-in picture subtitle needs an encode (ADR-0016, 8).
            answer(
                source(
                    streams = listOf(video(), audio(1), subtitle(3, "pgssub", SubtitleDelivery.BURNED_IN)),
                    defaultSubtitle = 3,
                ),
            )
            val played = resolve()
            assertTrue(played.source.isHls)
            assertNotEquals(Route.DIRECT_PLAY, played.route)
            assertEquals(3, urls.hlsCalls.single().burnIn)
        }

    @Test
    fun aSeparateSubtitleIsPassedToThePlayer() =
        runTest {
            answer(
                source(
                    streams = listOf(video(), audio(1), subtitle(3, "srt", SubtitleDelivery.SEPARATE, "fra")),
                    defaultSubtitle = 3,
                ),
            )
            val played = resolve()
            assertEquals(Route.DIRECT_PLAY, played.route)
            val sub = played.source.subtitles.single()
            assertEquals("https://media.example.com/sub/src1/3", sub.url)
            assertEquals("fra", sub.language)
        }

    @Test
    fun forceTranscodeIsPassedThrough() =
        runTest {
            answer(source())
            val played = resolve(force = true)
            assertTrue(
                server.playbackInfoCalls
                    .single()
                    .second.forceTranscode,
            )
            assertTrue(played.source.isHls)
            assertTrue(urls.hlsCalls.single().forceTranscode)
            assertEquals(Route.TRANSCODE, played.route)
        }

    @Test
    fun aDirectPlayAnswerForUndecodableAudioUsesHls() =
        runTest {
            answer(source(streams = listOf(video(), audio(1, Codecs.DTS, 6))))
            val played = resolve()
            assertEquals(Route.DIRECT_STREAM, played.route)
            assertTrue(played.source.isHls)
        }

    @Test
    fun undecodableAudioWithoutDirectStreamTranscodes() =
        runTest {
            answer(source(directStream = false, streams = listOf(video(), audio(1, Codecs.DTS, 6))))
            assertEquals(Route.TRANSCODE, resolve().route)
        }

    @Test
    fun tooManyChannelsForTheDecoderUsesHls() =
        runTest {
            answer(source(streams = listOf(video(), audio(1, Codecs.AC3, 6))))
            val played = resolve()
            assertTrue(played.source.isHls)
            assertNotEquals(Route.DIRECT_PLAY, played.route)
        }

    @Test
    fun passthroughAudioIsNotHeldToTheDecoderChannelLimit() =
        runTest {
            caps = insigniaLike(passthrough = setOf(Codecs.AC3), audioDecoders = setOf(Codecs.AAC))
            answer(source(streams = listOf(video(), audio(1, Codecs.AC3, 6))))
            assertEquals(Route.DIRECT_PLAY, resolve().route)
        }

    @Test
    fun serverReportedCodecNamesAreFoldedBeforeComparing() =
        runTest {
            answer(source(streams = listOf(video(), audio(1, "AAC", 2))))
            assertEquals(Route.DIRECT_PLAY, resolve().route)
        }

    @Test
    fun anOverriddenAudioTrackIsTheOneChecked() =
        runTest {
            answer(source(streams = listOf(video(), audio(1, Codecs.AAC), audio(2, Codecs.DTS, 6))))
            val played = resolve(override = TrackOverride(audioIndex = 2, subtitleIndex = null))
            assertTrue(played.source.isHls)
            assertEquals(2, urls.hlsCalls.single().audioIndex)
        }

    @Test
    fun aServerFailureIsReturnedNotThrown() =
        runTest {
            server.playbackInfoResult = ServerResult.Failure(MediaServerError.Timeout())
            val result = resolver.resolve(PlaybackTestData.session, item, Duration.ZERO, null)
            assertEquals(MediaServerError.Timeout(), assertIs<ServerResult.Failure>(result).error)
        }

    @Test
    fun anAnswerWithNoSourceIsAFailure() =
        runTest {
            server.playbackInfoResult = ServerResult.Success(PlaybackInfo(item, null, emptyList(), urls))
            val result = resolver.resolve(PlaybackTestData.session, item, 1.minutes, null)
            assertIs<MediaServerError.NotFound>(assertIs<ServerResult.Failure>(result).error)
        }
}
