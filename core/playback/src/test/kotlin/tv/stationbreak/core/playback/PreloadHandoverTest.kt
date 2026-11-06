// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Spike #563: a direct-play neighbour's media is preloaded on the player and handed over at the flip. */
class PreloadHandoverTest {
    private val server = FakeMediaServer()
    private val urls = RecordingUrls()
    private val player = FakeVideoPlayer()
    private val item = ItemId("item1")
    private val session = PlaybackTestData.session
    private val resolver = PlaybackResolver(server) { insigniaLike() }
    private val scheduler = TestCoroutineScheduler()
    private val watchScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val controller = controllerWith(enabled = true)

    private fun controllerWith(enabled: Boolean) =
        TuneController(resolver, server, player, watchScope = watchScope, preloadEnabled = { enabled }) { 1_000L }

    private fun runTest(body: suspend TestScope.() -> Unit) =
        kotlinx.coroutines.test.runTest(StandardTestDispatcher(scheduler), testBody = body)

    private fun answer(media: MediaSource) {
        server.playbackInfoResult = ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), listOf(media), urls))
    }

    private fun script(at: Duration = Duration.ZERO) {
        player.script =
            listOf(
                listOf(
                    Step { PlayerEvent.FirstFrame(1_300, it) },
                    Step { PlayerEvent.Playing(1_350, at, it) },
                ),
            )
    }

    @Test
    fun aDirectPlayNeighbourIsPreloadedAndItsTuneNamesTheKeyAndReportsIt() =
        runTest {
            answer(source())
            script(45.seconds)
            val prepared = checkNotNull(controller.preResolve(session, item, 30.seconds))
            val key = player.preloads.keys.single()
            val played = controller.tune(session, prepared, null, 45.seconds) as TuneResult.Played
            assertEquals(key, player.played.single().preloadKey)
            assertEquals(45.seconds, player.played.single().playerStart)
            assertEquals(PreloadUse(preloaded = true, ageMs = 700, setupMs = 300), played.preload)
        }

    @Test
    fun aPreloadStillWorkingIsReportedAsNotPreloaded() =
        runTest {
            answer(source())
            script()
            player.preloadReady = false
            val prepared = checkNotNull(controller.preResolve(session, item, Duration.ZERO))
            val played = controller.tune(session, prepared, null) as TuneResult.Played
            assertEquals(PreloadUse(preloaded = false, ageMs = 700, setupMs = -1), played.preload)
        }

    @Test
    fun aTranscodeNeighbourIsNotPreloaded() =
        runTest {
            answer(source(directPlay = false, directStream = false))
            val prepared = checkNotNull(controller.preResolve(session, item, Duration.ZERO))
            assertTrue(player.preloads.isEmpty())
            controller.discard(session, prepared)
            assertTrue(player.dropped.isEmpty())
        }

    @Test
    fun discardingAHandleDropsItsPreload() =
        runTest {
            answer(source())
            val prepared = checkNotNull(controller.preResolve(session, item, Duration.ZERO))
            val key = player.preloads.keys.single()
            controller.discard(session, prepared)
            assertEquals(listOf(key), player.dropped)
        }

    @Test
    fun aFreshTuneReportsNoPreload() =
        runTest {
            answer(source())
            script()
            val played = controller.tune(session, item, Duration.ZERO, null) as TuneResult.Played
            assertNull(played.preload)
            assertNull(player.played.single().preloadKey)
        }

    @Test
    fun withTheSwitchOffNothingIsPreloaded() =
        runTest {
            answer(source())
            val off = controllerWith(enabled = false)
            checkNotNull(off.preResolve(session, item, Duration.ZERO))
            assertTrue(player.preloads.isEmpty())
        }
}
