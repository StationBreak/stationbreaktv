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
import tv.stationbreak.core.model.MediaSourceId
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

    private fun answer(vararg media: MediaSource) {
        server.playbackInfoResult =
            ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), media.toList(), urls))
    }

    private suspend fun resolve(
        start: Duration = Duration.ZERO,
        override: TrackOverride? = null,
        force: Boolean = false,
        prefer: MediaSourceId? = null,
    ): ResolvedPlayback {
        val result = resolver.resolve(PlaybackTestData.session, item, start, override, force, prefer)
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
            assertEquals(30.seconds, played.source.playerStart)
            assertEquals(30.seconds, played.requested)
            assertEquals(PlaySessionId("ps1"), played.playSession)
            assertTrue(urls.hlsCalls.isEmpty())
        }

    @Test
    fun directPlayNamesTheTracksByPositionInKind() =
        runTest {
            val second = audio(index = 2, codec = Codecs.AC3).copy(language = "fra", positionInKind = 1)
            answer(source(streams = listOf(video(), audio(), second)))
            val played = resolve(override = TrackOverride(audioIndex = 2, subtitleIndex = null))
            assertEquals(TrackPick(1, "fra", Codecs.AC3), played.source.audioPick)
            assertNull(played.source.subtitlePick)
        }

    @Test
    fun directStreamUsesHls() =
        runTest {
            answer(source(directPlay = false))
            val played = resolve(start = 30.seconds)
            assertEquals(Route.DIRECT_STREAM, played.route)
            assertTrue(played.source.isHls)
            assertFalse(played.source.directPlayTracks)
            assertEquals(30.seconds, played.source.playerStart)
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
            assertEquals(Route.TRANSCODE, played.route)
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
    fun aForcedTranscodeStartsAtTheRequestedPosition() =
        runTest {
            answer(source())
            val played = resolve(start = 30.seconds, force = true)
            assertEquals(Route.TRANSCODE, played.route)
            assertTrue(played.source.isHls)
            assertEquals(30.seconds, played.source.playerStart)
            assertEquals(30.seconds, played.requested)
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
    fun anEmbeddedTextSubtitleOnAnHlsRouteIsDeliveredAsASeparateVtt() =
        runTest {
            answer(
                source(
                    streams = listOf(video(), audio(1, Codecs.DTS, 6), subtitle(3)),
                    defaultSubtitle = 3,
                ),
            )
            val played = resolve()
            assertTrue(played.source.isHls)
            assertEquals(SubtitleDelivery.SEPARATE, played.chosenSubtitle?.delivery)
            assertEquals(3, played.chosenSubtitle?.index)
            assertEquals(
                "https://media.example.com/sub/src1/3",
                played.source.subtitles
                    .single()
                    .url,
            )
            assertNull(played.source.subtitlePick)
            assertNull(urls.hlsCalls.single().burnIn)
        }

    @Test
    fun anEmbeddedTextSubtitleOnDirectPlayStaysInTheFile() =
        runTest {
            answer(source(streams = listOf(video(), audio(1), subtitle(3)), defaultSubtitle = 3))
            val played = resolve()
            assertEquals(Route.DIRECT_PLAY, played.route)
            assertEquals(SubtitleDelivery.IN_STREAM, played.chosenSubtitle?.delivery)
            assertTrue(played.source.subtitles.isEmpty())
            assertEquals(3, played.chosenSubtitle?.index)
            assertTrue(played.source.subtitlePick != null)
        }

    @Test
    fun aBurnedInDvbSubtitleTranscodesAndTheOracleAgrees() =
        runTest {
            val dvb = subtitle(3, "dvb_subtitle", SubtitleDelivery.BURNED_IN)
            answer(source(streams = listOf(video(), audio(1), dvb), defaultSubtitle = 3))
            val played = resolve()
            assertEquals(Route.TRANSCODE, played.route)
            assertEquals(3, urls.hlsCalls.single().burnIn)
            assertEquals(played.route, ExpectedRoute.of(caps, played.media, played.chosenSubtitle, played.chosenAudio))
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
    fun anOverrideIndexThatIsNotInTheSourceFallsBackToTheDefault() =
        runTest {
            answer(source(directPlay = false, streams = listOf(video(), audio(1), audio(2)), defaultAudio = 2))
            val played = resolve(override = TrackOverride(audioIndex = 9, subtitleIndex = null))
            assertEquals(2, played.chosenAudio?.index)
            assertEquals(2, played.source.audioIndex)
            assertEquals(2, urls.hlsCalls.single().audioIndex)
        }

    @Test
    fun anOverrideIndexThatIsNotAudioFallsBackToTheDefault() =
        runTest {
            answer(source(directPlay = false, streams = listOf(video(), audio(1), subtitle(3)), defaultAudio = 1))
            val played = resolve(override = TrackOverride(audioIndex = 3, subtitleIndex = null))
            assertEquals(1, played.chosenAudio?.index)
            assertEquals(1, urls.hlsCalls.single().audioIndex)
        }

    @Test
    fun withNoUsableDefaultTheFirstAudioStreamIsChosen() =
        runTest {
            answer(source(directPlay = false, streams = listOf(video(), audio(4), audio(5)), defaultAudio = null))
            val played = resolve(override = TrackOverride(audioIndex = 9, subtitleIndex = null))
            assertEquals(4, played.chosenAudio?.index)
            assertEquals(4, urls.hlsCalls.single().audioIndex)
        }

    @Test
    fun theAudioCheckStillAppliesAfterTheFallback() =
        runTest {
            answer(source(streams = listOf(video(), audio(1, Codecs.DTS, 6)), defaultAudio = 1))
            val played = resolve(override = TrackOverride(audioIndex = 9, subtitleIndex = null))
            assertTrue(played.source.isHls)
            assertNotEquals(Route.DIRECT_PLAY, played.route)
        }

    @Test
    fun aSeparateSubtitleWithNoUrlIsNotReportedAsChosen() =
        runTest {
            urls.hasSubtitleUrls = false
            answer(
                source(
                    streams = listOf(video(), audio(1), subtitle(3, "srt", SubtitleDelivery.SEPARATE)),
                    defaultSubtitle = 3,
                ),
            )
            val played = resolve()
            assertNull(played.chosenSubtitle)
            assertNull(played.source.subtitleIndex)
            assertTrue(played.source.subtitles.isEmpty())
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

    private fun sized(
        id: String,
        width: Int?,
        height: Int?,
    ) = source(streams = listOf(video(width = 1, height = 1).copy(width = width, height = height), audio()))
        .copy(id = MediaSourceId(id))

    private fun version(
        id: String,
        directPlay: Boolean = true,
        width: Int = 1920,
        height: Int = 1080,
        codec: String = Codecs.H264,
    ) = source(
        directPlay = directPlay,
        streams = listOf(video(codec = codec, width = width, height = height), audio()),
    ).copy(id = MediaSourceId(id))

    @Test
    fun theVersionThatDirectPlaysIsChosenOverAFirstOneThatDoesNot() =
        runTest {
            answer(version("a", directPlay = false), version("b"))
            val played = resolve()
            assertEquals(MediaSourceId("b"), played.media.id)
            assertEquals(Route.DIRECT_PLAY, played.route)
            assertEquals("https://media.example.com/original/b", played.source.url)
        }

    @Test
    fun aVersionTooBigForTheDecoderLosesToOneThatFits() =
        runTest {
            // The first is 4K H.264, past the 1080p decoder limit; the second is 1080p.
            answer(version("uhd", width = 3840, height = 2160), version("hd"))
            val played = resolve()
            assertEquals(MediaSourceId("hd"), played.media.id)
        }

    @Test
    fun theBiggestPictureThatFitsIsChosen() =
        runTest {
            answer(version("sd", width = 1280, height = 720), version("hd"), version("hd2"))
            assertEquals(MediaSourceId("hd"), resolve().media.id)
        }

    @Test
    fun withNoDirectPlayVersionTheFirstIsTranscodedByTheUsualRoute() =
        runTest {
            answer(version("a", directPlay = false), version("b", directPlay = false))
            val played = resolve()
            assertEquals(MediaSourceId("a"), played.media.id)
            assertEquals(Route.DIRECT_STREAM, played.route)
            assertEquals("https://media.example.com/hls/a", played.source.url)
        }

    @Test
    fun aForcedTranscodeKeepsJellyfinsOrder() =
        runTest {
            answer(version("a", directPlay = false), version("b"))
            val played = resolve(force = true)
            assertEquals(MediaSourceId("a"), played.media.id)
            assertEquals(Route.TRANSCODE, played.route)
        }

    @Test
    fun aVersionWhoseAudioThisDeviceCannotPlayIsNotADirectPlayVersion() =
        runTest {
            val dts = source(streams = listOf(video(), audio(codec = Codecs.DTS))).copy(id = MediaSourceId("dts"))
            answer(dts, version("aac"))
            assertEquals(MediaSourceId("aac"), resolve().media.id)
        }

    @Test
    fun anItemWithNoVersionIsNotFound() =
        runTest {
            answer()
            assertTrue(resolver.resolve(PlaybackTestData.session, item, Duration.ZERO, null) is ServerResult.Failure)
        }

    @Test
    fun aPreferredVersionIsChosenEvenWhenAnotherDirectPlays() =
        runTest {
            answer(version("a"), version("b", directPlay = false))
            assertEquals(MediaSourceId("b"), resolve(prefer = MediaSourceId("b")).media.id)
        }

    @Test
    fun aPreferredVersionThatIsNotInTheAnswerFallsBackToTheNormalChoice() =
        runTest {
            answer(version("a", directPlay = false), version("b"))
            assertEquals(MediaSourceId("b"), resolve(prefer = MediaSourceId("x")).media.id)
        }

    @Test
    fun aSingleVersionIsChosen() =
        runTest {
            answer(version("only", directPlay = false))
            assertEquals(MediaSourceId("only"), resolve().media.id)
        }

    @Test
    fun aVersionWithNoSizeFitsButLosesToAKnownSize() =
        runTest {
            answer(sized("unknown", null, null), version("hd"))
            assertEquals(MediaSourceId("hd"), resolve().media.id)
            answer(sized("unknown", null, null))
            assertEquals(MediaSourceId("unknown"), resolve().media.id)
        }

    @Test
    fun aBurnedInDefaultSubtitleMakesEveryVersionTranscodeSoTheFirstWins() =
        runTest {
            fun burned(id: String) =
                source(
                    streams = listOf(video(), audio(1), subtitle(3, "pgssub", SubtitleDelivery.BURNED_IN)),
                    defaultSubtitle = 3,
                ).copy(id = MediaSourceId(id))
            answer(burned("a"), burned("b"))
            val played = resolve()
            assertEquals(MediaSourceId("a"), played.media.id)
            assertEquals(Route.TRANSCODE, played.route)
        }

    @Test
    fun aForcedTranscodeWithNoPriorChoiceUsesTheFirstVersion() =
        runTest {
            answer(version("uhd", directPlay = false), version("hd"))
            assertEquals(MediaSourceId("uhd"), resolve(force = true).media.id)
        }
}
