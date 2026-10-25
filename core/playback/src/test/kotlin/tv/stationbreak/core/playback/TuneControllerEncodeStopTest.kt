// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The server's encode stop runs in the background: a flip does not wait for it, and close does (issue #392). */
@OptIn(ExperimentalCoroutinesApi::class)
class TuneControllerEncodeStopTest {
    private val server = FakeMediaServer()
    private val urls = RecordingUrls()
    private val player = FakeVideoPlayer()
    private val item = ItemId("item1")
    private val session = PlaybackTestData.session
    private val transcode = source(directPlay = false, directStream = false)

    /** Answers each playback-info request with the next play session id (ps1, ps2, ...), so tunes can be told apart. */
    private val numbering =
        object : MediaServer by server {
            private var count = 0

            override suspend fun playbackInfo(
                session: Session,
                item: ItemId,
                request: PlaybackRequest,
            ): ServerResult<PlaybackInfo> {
                val playSession = PlaySessionId("ps${++count}")
                return ServerResult.Success(PlaybackInfo(item, playSession, listOf(transcode), urls))
            }
        }

    // Not backgroundScope: advanceUntilIdle does not run a background scope's delayed work.
    private fun TestScope.controller() =
        TuneController(
            PlaybackResolver(numbering) { insigniaLike() },
            numbering,
            player,
            watchScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        ) { 1_000L }

    private fun playing() =
        listOf(
            Step { PlayerEvent.FirstFrame(1_300, it) },
            Step { PlayerEvent.Playing(1_350, Duration.ZERO, it) },
        )

    @Test
    fun aFlipAwayFromATranscodeDoesNotWaitForTheEncodeStop() =
        runTest {
            server.stopEncodingDelay = 5.seconds
            player.script = listOf(playing(), playing())
            var secondPlayAt = -1L
            player.onPlay = { if (it == 1) secondPlayAt = currentTime }
            val controller = controller()
            assertTrue(controller.tune(session, item, Duration.ZERO, null) is TuneResult.Played)
            assertTrue(controller.tune(session, item, Duration.ZERO, null) is TuneResult.Played)
            assertEquals("the second play did not wait for the first encode's stop", 0L, secondPlayAt)
            assertTrue("the stop is still running", server.stoppedEncodes.isEmpty())
            advanceUntilIdle()
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
            controller.close(session)
        }

    @Test
    fun closeWaitsForPendingEncodeStops() =
        runTest {
            server.stopEncodingDelay = 5.seconds
            player.script = listOf(playing())
            val controller = controller()
            assertTrue(controller.tune(session, item, Duration.ZERO, null) is TuneResult.Played)
            var closed = false
            val closing =
                async {
                    controller.close(session)
                    closed = true
                }
            runCurrent()
            assertFalse("close returned while the encode stop was running", closed)
            assertTrue(server.stoppedEncodes.isEmpty())
            closing.await()
            assertEquals(5_000L, currentTime)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
        }

    @Test
    fun everyEncodeIsStoppedOnceAfterRapidFlips() =
        runTest {
            server.stopEncodingDelay = 5.seconds
            player.script = List(4) { playing() }
            val controller = controller()
            repeat(4) { assertTrue(controller.tune(session, item, Duration.ZERO, null) is TuneResult.Played) }
            controller.close(session)
            assertEquals(
                listOf("ps1", "ps2", "ps3", "ps4").map(::PlaySessionId).toSet(),
                server.stoppedEncodes.toSet(),
            )
            assertEquals("each encode is stopped once", 4, server.stoppedEncodes.size)
        }
}
