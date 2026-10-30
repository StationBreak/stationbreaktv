// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import kotlin.time.Duration

/** Which version a fallback transcodes when an item has several (issue #496). */
class TuneControllerSourceChoiceTest {
    private val server = FakeMediaServer()
    private val urls = RecordingUrls()
    private val player = FakeVideoPlayer()
    private val item = ItemId("item1")
    private val session = PlaybackTestData.session
    private val resolver = PlaybackResolver(server) { insigniaLike() }
    private val controller = TuneController(resolver, server, player) { 1_000L }

    init {
        // The first version cannot direct-play, so the second is chosen.
        val first = source(directPlay = false).copy(id = MediaSourceId("uhd"))
        val second = source().copy(id = MediaSourceId("hd"))
        server.playbackInfoResult =
            ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), listOf(first, second), urls))
    }

    @Test
    fun aFailedDirectPlayOfTheSecondVersionFallsBackToTranscodeThatSameVersion() =
        runTest {
            player.script =
                listOf(
                    listOf(Step { PlayerEvent.Failed("ERROR_CODE_IO_BAD_HTTP_STATUS", 500, it) }),
                    listOf(
                        Step { PlayerEvent.FirstFrame(1_950, it) },
                        Step { PlayerEvent.Playing(2_000, Duration.ZERO, it) },
                    ),
                )
            val outcome = controller.tune(session, item, Duration.ZERO, null) as TuneResult.Played
            assertEquals(Route.DIRECT_PLAY, outcome.fallback?.from)
            assertEquals(Route.TRANSCODE, outcome.resolved.route)
            assertEquals(MediaSourceId("hd"), outcome.resolved.media.id)
            assertEquals("https://media.example.com/hls/hd", player.played.last().url)
        }
}
