// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import kotlin.time.Duration

/** The two diagnostics numbers of a play: the time to its tracks and the frames skipped before its first picture. */
class TuneControllerDiagnosticsTest {
    private val server = FakeMediaServer()
    private val player = FakeVideoPlayer()
    private val item = ItemId("item1")
    private val session = PlaybackTestData.session

    // The tune call happens at 1000 ms on the fake clock; the events carry their own times.
    private val controller = TuneController(PlaybackResolver(server) { insigniaLike() }, server, player) { 1_000L }

    private fun answer() {
        server.playbackInfoResult =
            ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), listOf(source()), RecordingUrls()))
    }

    private suspend fun played() = controller.tune(session, item, Duration.ZERO, null) as TuneResult.Played

    @Test
    fun theTimeToTracksAndTheSkippedFramesAreReported() =
        runTest {
            answer()
            player.script =
                listOf(
                    listOf(
                        Step { PlayerEvent.TracksReady(1_120, it) },
                        Step { PlayerEvent.FirstFrame(1_250, it, skippedFrames = 37) },
                        Step { PlayerEvent.Playing(1_300, Duration.ZERO, it) },
                    ),
                )
            val outcome = played()
            assertEquals(120L, outcome.tracksMs)
            assertEquals(37L, outcome.skippedFrames)
        }

    @Test
    fun theLoadNumbersOfTheTracksEventAreReported() =
        runTest {
            answer()
            val loads = LoadStats(3, 410, 250, 96, "mkv", "0")
            player.script =
                listOf(
                    listOf(
                        Step { PlayerEvent.TracksReady(1_120, it, loads) },
                        Step { PlayerEvent.FirstFrame(1_250, it) },
                        Step { PlayerEvent.Playing(1_300, Duration.ZERO, it) },
                    ),
                )
            assertEquals(loads, played().loads)
        }

    @Test
    fun anUnknownTracksTimeAndSkippedCountStayNull() =
        runTest {
            answer()
            player.script =
                listOf(
                    listOf(
                        Step { PlayerEvent.FirstFrame(1_250, it) },
                        Step { PlayerEvent.Playing(1_300, Duration.ZERO, it) },
                    ),
                )
            val outcome = played()
            assertNull(outcome.tracksMs)
            assertNull(outcome.skippedFrames)
            assertNull(outcome.loads)
        }
}
