// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Spike #563 (warm decoder): a warm request goes to the player's standby instead of the source preload. */
class WarmRequestTest {
    private val server = FakeMediaServer()
    private val player = FakeVideoPlayer()
    private val item = ItemId("item1")
    private val session = PlaybackTestData.session
    private val resolver = PlaybackResolver(server) { insigniaLike() }
    private val scheduler = TestCoroutineScheduler()
    private val watchScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    private fun controller(
        warm: Boolean,
        preload: Boolean = true,
    ) = TuneController(
        resolver,
        server,
        player,
        watchScope = watchScope,
        preloadEnabled = { preload },
        warmEnabled = { warm },
    ) { 1_000L }

    private fun runTest(body: suspend TestScope.() -> Unit) =
        kotlinx.coroutines.test.runTest(StandardTestDispatcher(scheduler), testBody = body)

    init {
        server.playbackInfoResult =
            ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), listOf(source()), RecordingUrls()))
    }

    @Test
    fun aWarmRequestGoesToTheStandbyNotThePreloadAndItsTuneSaysWarm() =
        runTest {
            player.script =
                listOf(
                    listOf(
                        Step { PlayerEvent.FirstFrame(1_300, it) },
                        Step { PlayerEvent.Playing(1_350, 45.seconds, it) },
                    ),
                )
            val c = controller(warm = true)
            val prepared = checkNotNull(c.preResolve(session, item, 30.seconds, warm = true))
            val key = player.warms.keys.single()
            assertTrue(player.preloads.isEmpty())
            val played = c.tune(session, prepared, null, 45.seconds) as TuneResult.Played
            assertEquals(key, player.played.single().preloadKey)
            assertEquals(PreloadUse(preloaded = true, ageMs = 900, setupMs = 400, warm = true), played.preload)
        }

    @Test
    fun withTheWarmSwitchOffAWarmRequestIsAnOrdinaryPreload() =
        runTest {
            checkNotNull(controller(warm = false).preResolve(session, item, Duration.ZERO, warm = true))
            assertTrue(player.warms.isEmpty())
            assertEquals(1, player.preloads.size)
        }

    @Test
    fun withTheWarmSwitchOnARequestWithoutWarmIsStillPreloaded() =
        runTest {
            checkNotNull(controller(warm = true).preResolve(session, item, Duration.ZERO))
            assertTrue(player.warms.isEmpty())
            assertEquals(1, player.preloads.size)
        }

    @Test
    fun aWarmRequestIsHonouredWithThePreloadSwitchOff() =
        runTest {
            checkNotNull(controller(warm = true, preload = false).preResolve(session, item, Duration.ZERO, warm = true))
            assertEquals(1, player.warms.size)
        }

    @Test
    fun discardingAWarmHandleDropsTheStandby() =
        runTest {
            val c = controller(warm = true)
            val prepared = checkNotNull(c.preResolve(session, item, Duration.ZERO, warm = true))
            val key = player.warms.keys.single()
            c.discard(session, prepared)
            assertEquals(listOf(key), player.dropped)
        }
}
