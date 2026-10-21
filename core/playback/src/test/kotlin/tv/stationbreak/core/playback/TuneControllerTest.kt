// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.view.SurfaceView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.PlaybackTestData.insigniaLike
import tv.stationbreak.core.playback.PlaybackTestData.source
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One scripted event: emitted [afterMs] after the play (0 means inside `play`), given the play's generation. */
private class Step(
    val afterMs: Long = 0,
    val event: (Long) -> PlayerEvent,
)

/** A player that answers each [play] with the steps scripted for that attempt, some of them late. */
private class FakeVideoPlayer : VideoPlayer {
    private val flow = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PlayerEvent> = flow
    val played = mutableListOf<PlayerSource>()
    var stops = 0
    var generation = 0L

    /** Runs the late steps; set by a test that has any. */
    var scope: CoroutineScope? = null

    /** Called inside `play` with the 0-based attempt number. */
    var onPlay: (Int) -> Unit = {}

    /** One list of steps per `play` call, in order. An empty list means the player says nothing. */
    var script: List<List<Step>> = emptyList()

    override fun play(source: PlayerSource): Long {
        val attempt = played.size
        calls += "play"
        played += source
        val mine = ++generation
        onPlay(attempt)
        script.getOrNull(attempt).orEmpty().forEach { step ->
            if (step.afterMs == 0L) {
                flow.tryEmit(step.event(mine))
            } else {
                checkNotNull(scope).launch {
                    delay(step.afterMs)
                    flow.tryEmit(step.event(mine))
                }
            }
        }
        return mine
    }

    /** Every `play` and `stop`, in order. */
    val calls = mutableListOf<String>()

    override fun stop() {
        calls += "stop"
        stops++
    }

    override fun attach(surface: SurfaceView?) = Unit

    override fun release() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class TuneControllerTest {
    private val server = FakeMediaServer()
    private val urls = RecordingUrls()
    private val player = FakeVideoPlayer()
    private val item = ItemId("item1")
    private val resolver = PlaybackResolver(server) { insigniaLike() }

    // The tune call happens at 1000 ms on the fake clock; the events carry their own times.
    private val controller = TuneController(resolver, server, player) { 1_000L }

    private fun answer(media: MediaSource) {
        server.playbackInfoResult =
            ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), listOf(media), urls))
    }

    private fun playing(
        atMs: Long,
        position: Duration,
    ) = listOf(Step { PlayerEvent.FirstFrame(atMs - 50, it) }, Step { PlayerEvent.Playing(atMs, position, it) })

    private fun failed(
        errorCode: String,
        status: Int? = null,
    ) = listOf(Step { PlayerEvent.Failed(errorCode, status, it) })

    private val session = PlaybackTestData.session

    private suspend fun tune(start: Duration = Duration.ZERO) = controller.tune(session, item, start, null)

    private suspend fun played(start: Duration = Duration.ZERO) = tune(start) as TuneResult.Played

    private fun failure(result: TuneResult) = result as TuneResult.Failed

    @Test
    fun aDirectPlayableItemPlaysDirect() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_400, 30.seconds))
            val outcome = played(30.seconds)
            assertEquals(Route.DIRECT_PLAY, outcome.resolved.route)
            assertFalse(outcome.fallback)
            assertNull(outcome.positionError)
            assertEquals(350L, outcome.firstFrameMs)
            assertEquals(400L, outcome.playingMs)
            assertEquals(1, player.played.size)
            assertEquals(1, server.playbackInfoCalls.size)
        }

    @Test
    fun aFileThatLandsAtZeroFallsBackToTranscodeOnce() =
        runTest {
            answer(source())
            // Direct play lands at 0 when 60 s was asked. The forced transcode starts at 60 s on the server.
            player.script = listOf(playing(1_300, Duration.ZERO), playing(2_000, Duration.ZERO))
            val outcome = played(60.seconds)
            assertEquals(2, server.playbackInfoCalls.size)
            assertFalse(server.playbackInfoCalls[0].second.forceTranscode)
            assertTrue(server.playbackInfoCalls[1].second.forceTranscode)
            assertTrue(outcome.fallback)
            assertEquals(Route.TRANSCODE, outcome.resolved.route)
            assertNull(outcome.positionError)
            // Timings run from the tune call, fallback included.
            assertEquals(1_000L, outcome.playingMs)
            assertEquals(950L, outcome.firstFrameMs)
        }

    @Test
    fun aSecondMissIsReportedWithoutAThirdAttempt() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO), playing(2_000, 10.seconds))
            val outcome = played(60.seconds)
            assertEquals(2, player.played.size)
            assertEquals(2, server.playbackInfoCalls.size)
            assertTrue(outcome.fallback)
            assertEquals(10.seconds, outcome.positionError)
        }

    @Test
    fun aTranscodedFirstAttemptIsStoppedBeforeFallback() =
        runTest {
            answer(source(directPlay = false, directStream = false))
            player.script = listOf(playing(1_300, 120.seconds), playing(2_000, Duration.ZERO))
            val outcome = played(60.seconds)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
            assertTrue(outcome.fallback)
            assertNull(outcome.positionError)
        }

    @Test
    fun aDirectFirstAttemptStartedNoEncodeToStop() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO), playing(2_000, Duration.ZERO))
            tune(60.seconds)
            assertTrue(server.stoppedEncodes.isEmpty())
        }

    @Test
    fun stopStopsTheEncode() =
        runTest {
            answer(source(directPlay = false, directStream = false))
            player.script = listOf(playing(1_300, Duration.ZERO))
            tune()
            controller.stop(session)
            assertEquals(1, player.stops)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
        }

    @Test
    fun stopAfterADirectPlayStopsOnlyThePlayer() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO))
            tune()
            controller.stop(session)
            assertEquals(1, player.stops)
            assertTrue(server.stoppedEncodes.isEmpty())
        }

    @Test
    fun aPlayerErrorIsReportedNotThrown() =
        runTest {
            answer(source(directPlay = false, directStream = false))
            player.script = listOf(failed("ERROR_CODE_DECODING_FAILED"))
            val outcome = failure(tune())
            assertEquals(TuneFailure.PLAYER, outcome.reason)
            assertEquals("ERROR_CODE_DECODING_FAILED", outcome.diagnostic)
            assertNotNull(outcome.resolved)
            // A transcode already was the full fallback: no second attempt.
            assertEquals(1, player.played.size)
        }

    @Test
    fun aMediaLoad401IsSessionExpiredWithoutFallback() =
        runTest {
            answer(source())
            player.script = listOf(failed("ERROR_CODE_IO_BAD_HTTP_STATUS", 401))
            val outcome = tune() as TuneResult.SessionExpired
            assertNotNull(outcome.resolved)
            assertEquals(1, server.playbackInfoCalls.size)
            assertEquals(1, player.played.size)
        }

    @Test
    fun aMediaLoad403IsAlsoSessionExpired() =
        runTest {
            answer(source())
            player.script = listOf(failed("ERROR_CODE_IO_BAD_HTTP_STATUS", 403))
            assertTrue(tune() is TuneResult.SessionExpired)
            assertEquals(1, player.played.size)
        }

    @Test
    fun anOtherDirectPlayErrorFallsBackOnceToATranscode() =
        runTest {
            answer(source())
            player.script =
                listOf(
                    failed("ERROR_CODE_IO_BAD_HTTP_STATUS", 500),
                    playing(2_000, Duration.ZERO),
                )
            val outcome = played()
            assertTrue(outcome.fallback)
            assertTrue(server.playbackInfoCalls[1].second.forceTranscode)
        }

    @Test
    fun aFailedResolveIsReportedNotThrown() =
        runTest {
            server.playbackInfoResult = ServerResult.Failure(MediaServerError.NotFound())
            val outcome = failure(tune())
            assertEquals(TuneFailure.SERVER, outcome.reason)
            assertEquals("NotFound", outcome.diagnostic)
            assertNull(outcome.resolved)
            assertTrue(player.played.isEmpty())
        }

    @Test
    fun noPlayingWithinTwentySecondsIsATimeout() =
        runTest {
            answer(source())
            player.script = listOf(emptyList())
            val outcome = failure(tune())
            assertEquals(TuneFailure.TIMEOUT, outcome.reason)
            assertEquals(20_000L, currentTime)
        }

    @Test
    fun aSessionErrorFromTheServerIsSessionExpired() =
        runTest {
            server.playbackInfoResult = ServerResult.Failure(MediaServerError.SessionExpired())
            val outcome = tune() as TuneResult.SessionExpired
            assertNull(outcome.resolved)
            assertTrue(player.played.isEmpty())
        }

    @Test
    fun diagnosticsAreStableNamesNotClassNames() =
        runTest {
            val cause = listOf("java.net.SocketTimeoutException")
            server.playbackInfoResult = ServerResult.Failure(MediaServerError.Timeout(cause))
            val outcome = failure(tune())
            assertEquals("Timeout", outcome.diagnostic)
        }

    @Test
    fun anUnexpectedExceptionIsNamedUnexpectedNotByItsClass() =
        runTest {
            val throwing = PlaybackResolver(server) { throw IllegalStateException("http://x/?api_key=secret") }
            val thrown = TuneController(throwing, server, player) { 1_000L }
            val outcome = failure(thrown.tune(session, item, Duration.ZERO, null))
            assertEquals(TuneFailure.SERVER, outcome.reason)
            assertEquals("Unexpected", outcome.diagnostic)
        }

    @Test
    fun aPlayerFailureWithAStatusKeepsItInTheDiagnostic() =
        runTest {
            answer(source(directPlay = false, directStream = false))
            player.script = listOf(failed("ERROR_CODE_IO_BAD_HTTP_STATUS", 500))
            assertEquals("ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 500)", failure(tune()).diagnostic)
        }

    @Test
    fun unknownRuntimeStartsAtZero() =
        runTest {
            answer(source().copy(runtime = null))
            player.script = listOf(playing(1_300, Duration.ZERO))
            val outcome = played()
            assertEquals(
                Duration.ZERO,
                server.playbackInfoCalls
                    .single()
                    .second.start,
            )
            assertFalse(outcome.fallback)
            assertNull(outcome.positionError)
        }

    @Test
    fun aStalePlayingFromThePreviousTuneIsIgnored() =
        runTest {
            player.scope = this
            answer(source())
            player.script =
                listOf(
                    playing(1_300, Duration.ZERO),
                    // The previous play's Playing arrives after the new play, then the new one's own.
                    listOf(
                        Step(100) { PlayerEvent.Playing(1_150, 99.seconds, it - 1) },
                        Step(500) { PlayerEvent.Playing(1_600, Duration.ZERO, it) },
                    ),
                )
            tune()
            val outcome = played()
            assertEquals(600L, outcome.playingMs)
            assertFalse(outcome.fallback)
            assertNull(outcome.positionError)
        }

    @Test
    fun aStaleFailedIsIgnored() =
        runTest {
            player.scope = this
            answer(source())
            player.script =
                listOf(
                    playing(1_300, Duration.ZERO),
                    listOf(
                        Step(100) { PlayerEvent.Failed("StaleError", null, it - 1) },
                        Step(500) { PlayerEvent.Playing(1_600, Duration.ZERO, it) },
                    ),
                )
            tune()
            val outcome = played()
            assertFalse(outcome.fallback)
            assertEquals(600L, outcome.playingMs)
        }

    @Test
    fun aNewTuneCancelsTheOneInFlight() =
        runTest {
            answer(source())
            player.script = listOf(emptyList(), playing(1_300, Duration.ZERO))
            val first = async { tune() }
            runCurrent()
            val second = async { tune() }
            assertEquals(TuneResult.Superseded, first.await())
            assertTrue(second.await() is TuneResult.Played)
            assertEquals(2, player.played.size)
            assertTrue("the first did not wait out its timeout", currentTime < 20_000L)
        }

    @Test
    fun aFirstFrameAfterPlayingIsStillMeasured() =
        runTest {
            player.scope = this
            answer(source())
            player.script =
                listOf(
                    listOf(
                        Step { PlayerEvent.Playing(1_300, Duration.ZERO, it) },
                        Step(500) { PlayerEvent.FirstFrame(1_350, it) },
                    ),
                )
            val outcome = played()
            assertEquals(350L, outcome.firstFrameMs)
            assertEquals(300L, outcome.playingMs)
        }

    @Test
    fun noFirstFrameWithinTwoSecondsLeavesItNullAndTheTuneFine() =
        runTest {
            answer(source())
            player.script = listOf(listOf(Step { PlayerEvent.Playing(1_300, Duration.ZERO, it) }))
            val outcome = played()
            assertNull(outcome.firstFrameMs)
            assertEquals(300L, outcome.playingMs)
            assertFalse(outcome.fallback)
            assertEquals(2_000L, currentTime)
        }

    @Test
    fun aTimeoutStopsThePlayerAndTheEncode() =
        runTest {
            answer(source(directPlay = false, directStream = false))
            player.script = listOf(emptyList())
            assertEquals(TuneFailure.TIMEOUT, failure(tune()).reason)
            assertEquals(1, player.stops)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
        }

    @Test
    fun aTimeoutOnDirectPlayStopsOnlyThePlayer() =
        runTest {
            answer(source())
            player.script = listOf(emptyList())
            tune()
            assertEquals(1, player.stops)
            assertTrue(server.stoppedEncodes.isEmpty())
        }

    @Test
    fun aFailedFallbackResolveKeepsTheFirstAttempt() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO))
            player.onPlay = { server.playbackInfoResult = ServerResult.Failure(MediaServerError.NotFound()) }
            val outcome = failure(tune(60.seconds))
            // The first resolve succeeded (before the hook ran); the fallback's resolve then failed.
            assertTrue(outcome.fallback)
            assertEquals("NotFound", outcome.diagnostic)
            assertEquals(TuneFailure.SERVER, outcome.reason)
            assertEquals(Route.DIRECT_PLAY, outcome.resolved?.route)
        }

    // --- stop() and cancellation -------------------------------------------------------------------------

    private val transcode get() = source(directPlay = false, directStream = false)

    @Test
    fun stopDuringResolveNeverPlays() =
        runTest {
            answer(source())
            val gate = CompletableDeferred<Unit>()
            val gated =
                object : MediaServer by server {
                    override suspend fun playbackInfo(
                        session: Session,
                        item: ItemId,
                        request: PlaybackRequest,
                    ): ServerResult<PlaybackInfo> {
                        gate.await()
                        return server.playbackInfo(session, item, request)
                    }
                }
            val gatedController = TuneController(PlaybackResolver(gated) { insigniaLike() }, gated, player) { 1_000L }
            val tuning = async { gatedController.tune(session, item, Duration.ZERO, null) }
            runCurrent()
            gatedController.stop(session)
            gate.complete(Unit)
            assertEquals(TuneResult.Stopped, tuning.await())
            assertTrue("a stopped tune never plays", player.played.isEmpty())
        }

    @Test
    fun stopDuringWaitEndsTheTuneAtOnce() =
        runTest {
            answer(source())
            player.script = listOf(emptyList())
            val tuning = async { tune() }
            runCurrent()
            controller.stop(session)
            assertEquals(TuneResult.Stopped, tuning.await())
            assertEquals("did not wait out the timeout", 0L, currentTime)
            assertEquals(listOf("play", "stop", "stop"), player.calls)
        }

    @Test
    fun stopStopsTheEncodeOfATuneInFlight() =
        runTest {
            answer(transcode)
            player.script = listOf(emptyList())
            val tuning = async { tune() }
            runCurrent()
            controller.stop(session)
            assertEquals(TuneResult.Stopped, tuning.await())
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
        }

    @Test
    fun aTuneCancelledFromOutsideStopsThePlayerAndItsEncode() =
        runTest {
            answer(transcode)
            player.script = listOf(emptyList())
            val tuning = launch { tune() }
            runCurrent()
            tuning.cancel()
            runCurrent()
            assertEquals(listOf("play", "stop"), player.calls)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
            // `current` was cleared: a later stop() has no encode left to stop.
            controller.stop(session)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
        }

    @Test
    fun aSupersedingPlayIsNotDisturbedByTheCancelledOnesCleanUp() =
        runTest {
            answer(transcode)
            player.script = listOf(emptyList(), playing(1_300, Duration.ZERO))
            val first = async { tune() }
            runCurrent()
            val second = async { tune() }
            assertEquals(TuneResult.Superseded, first.await())
            assertTrue(second.await() is TuneResult.Played)
            // The first one's clean-up (stop, its encode) ran before the second one played, never after.
            assertEquals(listOf("play", "stop", "play"), player.calls)
            // The second play's attempt is still current, so stop() ends its encode too.
            controller.stop(session)
            assertEquals(2, server.stoppedEncodes.size)
        }

    // --- no loose ends after a result that is not Played ---------------------------------------------------

    @Test
    fun aPlayerFailureLeavesNothingRunning() =
        runTest {
            answer(transcode)
            player.script = listOf(failed("ERROR_CODE_DECODING_FAILED"))
            failure(tune())
            assertEquals(1, player.stops)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
        }

    @Test
    fun anExpiredSessionLeavesNothingRunning() =
        runTest {
            answer(source(directPlay = false, directStream = true))
            player.script = listOf(failed("ERROR_CODE_IO_BAD_HTTP_STATUS", 401))
            assertTrue(tune() is TuneResult.SessionExpired)
            assertEquals(1, player.stops)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
        }

    @Test
    fun aFallbackThatCouldNotResolveLeavesNothingRunning() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO))
            player.onPlay = { server.playbackInfoResult = ServerResult.Failure(MediaServerError.NotFound()) }
            failure(tune(60.seconds))
            // The missed first attempt was still playing; a result that is not Played leaves it stopped.
            assertEquals(1, player.stops)
        }

    @Test
    fun aPlayedResultLeavesThePlayerRunning() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO))
            played()
            assertEquals(0, player.stops)
        }

    // --- the landing tolerance ----------------------------------------------------------------------------

    @Test
    fun theLandingToleranceIsConfigurable() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, 5.seconds))
            val lenient = TuneController(resolver, server, player, seekTolerance = 10.seconds) { 1_000L }
            val outcome = lenient.tune(session, item, Duration.ZERO, null) as TuneResult.Played
            assertFalse(outcome.fallback)
            assertNull(outcome.positionError)
            assertEquals(1, player.played.size)
        }

    @Test
    fun theDefaultToleranceIsTwoSecondsAndFallsBackBeyondIt() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, 3.seconds), playing(2_000, Duration.ZERO))
            val outcome = played()
            assertTrue(outcome.fallback)
            assertEquals(2, player.played.size)
        }
}
