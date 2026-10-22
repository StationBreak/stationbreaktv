// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber", "LongParameterList") // Scripted times and sample values; detekt skips only src/test.

package tv.stationbreak.app.bench

import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.StreamUrls
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.PlayerEvent
import tv.stationbreak.core.playback.PlayerSource
import tv.stationbreak.core.playback.TuneController
import tv.stationbreak.core.playback.VideoPlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private class ScriptedPlayer(
    private val scope: CoroutineScope,
) : VideoPlayer {
    private val flow = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<PlayerEvent> = flow
    private var generation = 0L
    var stops = 0

    /** The closing sample a real player takes as it stops, sent shortly after stop returns; null for none. */
    var closing: ((Long) -> PlayerEvent)? = null

    /** Late events of the next play, as (milliseconds after the play, event of the generation). */
    var late: List<Pair<Long, (Long) -> PlayerEvent>> = emptyList()

    override fun play(source: PlayerSource): Long {
        val mine = ++generation
        flow.tryEmit(PlayerEvent.FirstFrame(1_050, mine))
        flow.tryEmit(PlayerEvent.Playing(1_100, Duration.ZERO, mine))
        late.forEach { (after, event) ->
            scope.launch {
                delay(after)
                flow.tryEmit(event(mine))
            }
        }
        return mine
    }

    override fun stop() {
        stops++
        val event = closing ?: return
        val mine = generation
        scope.launch {
            delay(50)
            flow.tryEmit(event(mine))
        }
    }

    override fun attach(surface: SurfaceView?) = Unit

    override fun release() = Unit
}

private class NoUrls : StreamUrls {
    override fun original(source: MediaSourceId) = "https://media.example.com/original"

    override fun hls(
        source: MediaSource,
        audioIndex: Int?,
        burnIn: Int?,
        limits: DeviceCapabilities,
        start: Duration,
        forceTranscode: Boolean,
    ) = "https://media.example.com/hls"

    override fun subtitle(
        source: MediaSourceId,
        stream: MediaStreamInfo,
    ): String? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRunTest {
    private val item = ItemId("item1")
    private val capabilities =
        DeviceCapabilities(
            containers = setOf("mkv", "mp4"),
            videoCodecs = emptyList(),
            audioDecoders = emptySet(),
            audioPassthrough = emptySet(),
            maxAudioChannels = 2,
        )

    private suspend fun TestScope.run(
        late: List<Pair<Long, (Long) -> PlayerEvent>>,
        media: MediaSource,
        closing: ((Long) -> PlayerEvent)? = null,
    ): Pair<PlaybackResult, ScriptedPlayer> {
        val server = FakeMediaServer()
        server.playbackInfoResult =
            ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), listOf(media), NoUrls()))
        val player =
            ScriptedPlayer(this).also {
                it.late = late
                it.closing = closing
            }
        val controller = TuneController(PlaybackResolver(server) { capabilities }, server, player) { 1_000L }
        // The 15 s window runs on the test's virtual clock, so it costs nothing.
        val result = PlaybackRun(controller, player).run(BenchTestData.session, "video", Play("item1", 0, null), null)
        return result to player
    }

    private fun playableMedia(subtitle: MediaStreamInfo? = null) =
        BenchTestData.media(
            container = "mkv",
            streams = listOfNotNull(BenchTestData.video, BenchTestData.audio, subtitle),
            defaultSubtitle = subtitle?.index,
        )

    @Test
    fun stallsAndCuesAreCounted() =
        runTest {
            val late: List<Pair<Long, (Long) -> PlayerEvent>> =
                listOf(
                    1_000L to { g -> PlayerEvent.AudioAdvanced(1.seconds, g) },
                    2_000L to { g -> PlayerEvent.Stalled(g) },
                    3_000L to { g -> PlayerEvent.Cues(1, g) },
                    4_000L to { g -> PlayerEvent.Stalled(g) },
                    5_000L to { g -> PlayerEvent.Cues(2, g) },
                    14_000L to { g -> PlayerEvent.AudioAdvanced(13.seconds, g) },
                )
            val (result, player) = run(late, playableMedia(BenchTestData.textSubtitle()))
            assertEquals(2, result.stalls)
            assertEquals(true, result.subtitleOk)
            assertEquals(true, result.audioOk)
            assertEquals("text-in-stream", result.subtitle)
            assertEquals(1, player.stops)
        }

    @Test
    fun silentAudioFailsAudioOk() =
        runTest {
            val late: List<Pair<Long, (Long) -> PlayerEvent>> =
                listOf(1_000L to { g -> PlayerEvent.AudioAdvanced(1.seconds, g) })
            val (result, _) = run(late, playableMedia())
            assertEquals(false, result.audioOk)
            assertFalse(result.pass)
        }

    @Test
    fun theClosingSampleAtStopCountsTowardTheAudioAdvance() =
        runTest {
            val late: List<Pair<Long, (Long) -> PlayerEvent>> =
                listOf(1_000L to { g -> PlayerEvent.AudioAdvanced(1.seconds, g) })
            val (result, _) =
                run(late, playableMedia(), closing = { g -> PlayerEvent.AudioAdvanced(14.seconds, g) })
            assertEquals(true, result.audioOk)
        }

    @Test
    fun noWaitForAClosingSampleWhenAudioNeverStarted() =
        runTest {
            val before = testScheduler.currentTime
            val (result, _) = run(emptyList(), playableMedia(), closing = { g -> PlayerEvent.AudioAdvanced(14.seconds, g) })
            // Audio never started, so the run does not wait and does not count a sample that arrives after it ends.
            assertEquals(15_000L, testScheduler.currentTime - before)
            assertEquals(false, result.audioOk)
        }
}
