// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.playback.PlaybackTestData.audio
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import tv.stationbreak.core.playback.PlaybackTestData.subtitle
import tv.stationbreak.core.playback.PlaybackTestData.video
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** #735: direct play refuses an MP4 whose header would not fit the heap budget. */
class PlaybackResolverHeaderGuardTest {
    private val server = FakeMediaServer()
    private val item = ItemId("item1")

    /** A 192 MiB heap's budget, as on the Insignia and the Firestick. */
    private val resolver =
        PlaybackResolver(server, Mp4HeaderCost.budgetFor(192L * 1_024 * 1_024)) { insigniaLike() }

    private fun tracks(audioTracks: Int) =
        listOf(video().copy(frameRate = 24.0)) +
            (1..audioTracks).map { audio(it).copy(sampleRate = 48_000) }

    private fun film(
        audioTracks: Int,
        container: String = Codecs.MP4,
        directStream: Boolean = true,
    ): MediaSource =
        source(container = container, streams = tracks(audioTracks), directStream = directStream)
            .copy(runtime = 2.hours, sizeBytes = 4_000L * 1_024 * 1_024)

    private suspend fun resolve(media: MediaSource): ResolvedPlayback {
        server.playbackInfoResult =
            ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), listOf(media), RecordingUrls()))
        val result = resolver.resolve(PlaybackTestData.session, item, Duration.ZERO, null)
        return assertIs<ServerResult.Success<ResolvedPlayback>>(result).value
    }

    @Test
    fun anMp4WhoseHeaderFitsStillDirectPlays() =
        runTest {
            assertEquals(Route.DIRECT_PLAY, resolve(film(audioTracks = 1)).route)
        }

    @Test
    fun anMp4WhoseHeaderDoesNotFitIsDirectStreamed() =
        runTest {
            val played = resolve(film(audioTracks = 4))
            assertEquals(Route.DIRECT_STREAM, played.route)
            assertTrue(played.source.isHls)
            assertFalse(played.source.directPlayTracks)
        }

    @Test
    fun withNoDirectStreamTheOversizedMp4IsTranscoded() =
        runTest {
            assertEquals(Route.TRANSCODE, resolve(film(audioTracks = 4, directStream = false)).route)
        }

    @Test
    fun theSameTracksInMatroskaStillDirectPlay() =
        runTest {
            assertEquals(Route.DIRECT_PLAY, resolve(film(audioTracks = 4, container = Codecs.MKV)).route)
        }

    @Test
    fun theSourceCarriesTheFilesFactsForTheErrorLine() =
        runTest {
            val media = film(audioTracks = 2).copy(streams = tracks(2) + subtitle(5))
            assertEquals(
                "container=mp4 sizeMb=4000 runtimeMin=120 codecs=h264,aac,aac subs=1 samples=847800 headerEstMb=51",
                resolve(media).source.fileFacts,
            )
        }
}
