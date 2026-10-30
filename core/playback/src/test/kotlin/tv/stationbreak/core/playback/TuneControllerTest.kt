// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.view.SurfaceView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
internal class Step(
    val afterMs: Long = 0,
    val event: (Long) -> PlayerEvent,
)

/** A player that answers each [play] with the steps scripted for that attempt, some of them late. */
internal class FakeVideoPlayer : VideoPlayer {
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

    override fun pause(generation: Long) {
        calls += "pause:$generation"
    }

    override fun resume(generation: Long) {
        calls += "resume:$generation"
    }

    override fun seekTo(
        generation: Long,
        position: Duration,
    ) {
        calls += "seek:$generation:${position.inWholeMilliseconds}"
    }

    override fun position(generation: Long): Duration? = null

    /** What [currentPosition] answers. */
    var live: Duration? = null

    override suspend fun currentPosition(generation: Long): Duration? {
        calls += "live:$generation"
        return live
    }

    override fun seekable(generation: Long): Boolean = false

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
            assertNull(outcome.fallback)
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
            // Direct play lands at 0 when 60 s was asked. The forced transcode is then seeked to 60 s by the player.
            player.script = listOf(playing(1_300, Duration.ZERO), playing(2_000, 60.seconds))
            val outcome = played(60.seconds)
            assertEquals(2, server.playbackInfoCalls.size)
            assertFalse(server.playbackInfoCalls[0].second.forceTranscode)
            assertTrue(server.playbackInfoCalls[1].second.forceTranscode)
            // The route the resolver chose first survives the fallback, with the reason.
            assertEquals(Fallback(Route.DIRECT_PLAY, FallbackCause.SeekMiss), outcome.fallback)
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
            player.script = listOf(playing(1_300, Duration.ZERO), playing(2_000, 70.seconds))
            val outcome = played(60.seconds)
            assertEquals(2, player.played.size)
            assertEquals(2, server.playbackInfoCalls.size)
            assertEquals(Fallback(Route.DIRECT_PLAY, FallbackCause.SeekMiss), outcome.fallback)
            assertEquals(10.seconds, outcome.positionError)
        }

    @Test
    fun aTranscodedFirstAttemptIsStoppedBeforeFallback() =
        runTest {
            answer(source(directPlay = false, directStream = false))
            player.script = listOf(playing(1_300, 120.seconds), playing(2_000, 60.seconds))
            val outcome = played(60.seconds)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
            assertEquals(Fallback(Route.TRANSCODE, FallbackCause.SeekMiss), outcome.fallback)
            assertNull(outcome.positionError)
        }

    @Test
    fun aDirectFirstAttemptStartedNoEncodeToStop() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO), playing(2_000, 60.seconds))
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
            val cause = FallbackCause.LoadFailed(TuneFailure.PLAYER, "ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 500)")
            assertEquals(Fallback(Route.DIRECT_PLAY, cause), outcome.fallback)
            assertEquals(Route.TRANSCODE, outcome.resolved.route)
            assertTrue(server.playbackInfoCalls[1].second.forceTranscode)
        }

    @Test
    fun aDecoderFailureOnDirectPlayIsKeptAsTheFallbackCause() =
        runTest {
            answer(source())
            player.script = listOf(failed("ERROR_CODE_DECODER_INIT_FAILED"), playing(2_000, Duration.ZERO))
            val outcome = played()
            val cause = FallbackCause.LoadFailed(TuneFailure.PLAYER, "ERROR_CODE_DECODER_INIT_FAILED")
            assertEquals(Fallback(Route.DIRECT_PLAY, cause), outcome.fallback)
            assertEquals(Route.TRANSCODE, outcome.resolved.route)
            assertNull(outcome.positionError)
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
            assertNull(outcome.fallback)
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
            assertNull(outcome.fallback)
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
            assertNull(outcome.fallback)
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
            assertNull(outcome.fallback)
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
            assertEquals(Fallback(Route.DIRECT_PLAY, FallbackCause.SeekMiss), outcome.fallback)
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
    fun aTuneCancelledFromOutsideDuringTheFallbacksResolveStopsTheFirstAttempt() =
        runTest {
            answer(transcode)
            // The first attempt plays but lands at zero for a 60 s start, so the fallback resolves a second time.
            player.script = listOf(playing(1_300, Duration.ZERO))
            val gate = CompletableDeferred<Unit>()
            var resolves = 0
            val gated =
                object : MediaServer by server {
                    override suspend fun playbackInfo(
                        session: Session,
                        item: ItemId,
                        request: PlaybackRequest,
                    ): ServerResult<PlaybackInfo> {
                        if (++resolves == 2) gate.await()
                        return server.playbackInfo(session, item, request)
                    }
                }
            val gatedController = TuneController(PlaybackResolver(gated) { insigniaLike() }, gated, player) { 1_000L }
            val tuning = launch { gatedController.tune(session, item, 60.seconds, null) }
            runCurrent()
            assertEquals("the fallback is waiting on its resolve", 2, resolves)
            tuning.cancel()
            runCurrent()
            // The player was still playing the abandoned attempt: the cancel stops it. Its encode was already stopped
            // before the fallback resolved, and is not stopped again.
            assertEquals(listOf("play", "stop"), player.calls)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
        }

    /** A server whose [call]th playback-info request waits for [gate] (1-based), counting the calls in [calls]. */
    private class Gated(
        private val inner: MediaServer,
        private val call: Int,
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : MediaServer by inner {
        var calls = 0

        override suspend fun playbackInfo(
            session: Session,
            item: ItemId,
            request: PlaybackRequest,
        ): ServerResult<PlaybackInfo> {
            if (++calls == call) gate.await()
            return inner.playbackInfo(session, item, request)
        }
    }

    @Test
    fun aTuneAfterCloseNeverPlays() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO))
            controller.close(session)
            assertEquals(TuneResult.Stopped, tune())
            assertTrue("a closed controller never plays", player.played.isEmpty())
            assertTrue(server.playbackInfoCalls.isEmpty())
        }

    @Test
    fun aTuneStartedButNotYetRunningWhenCloseRunsNeverPlays() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO))
            // Created, but not yet registered as the tune in flight: close() cannot see it, so it must check itself.
            val tuning = async { tune() }
            controller.close(session)
            assertEquals(TuneResult.Stopped, tuning.await())
            assertTrue(player.played.isEmpty())
        }

    @Test
    fun closeRunsToTheEndFromACancelledScope() =
        runTest {
            answer(transcode)
            player.script = listOf(playing(1_300, Duration.ZERO), emptyList())
            val gated = Gated(server, call = 2)
            val gatedController = TuneController(PlaybackResolver(gated) { insigniaLike() }, gated, player) { 1_000L }
            gatedController.tune(session, item, Duration.ZERO, null)
            // A second tune holds the lock while it waits on its resolve, so close() has to wait for it.
            val busy = launch { gatedController.tune(session, item, Duration.ZERO, null) }
            runCurrent()
            val host =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        gatedController.close(session)
                    }
                }
            runCurrent()
            host.cancel()
            runCurrent()
            busy.join()
            // The busy tune's own clean-up stops the player once; close()'s release, which waited for the lock in a
            // cancelled scope, stops it a second time.
            assertEquals(listOf("play", "stop", "stop"), player.calls)
        }

    @Test
    fun aTuneSupersededDuringItsResolveDoesNotStopThePlayer() =
        runTest {
            answer(transcode)
            player.script = listOf(playing(1_300, Duration.ZERO), playing(1_300, Duration.ZERO))
            val gated = Gated(server, call = 2)
            val gatedController = TuneController(PlaybackResolver(gated) { insigniaLike() }, gated, player) { 1_000L }
            gatedController.tune(session, item, Duration.ZERO, null)
            val second = async { gatedController.tune(session, item, Duration.ZERO, null) }
            runCurrent()
            val third = async { gatedController.tune(session, item, Duration.ZERO, null) }
            assertEquals(TuneResult.Superseded, second.await())
            assertTrue(third.await() is TuneResult.Played)
            // A fast flip: the screen is not blanked between the plays.
            assertEquals(listOf("play", "play"), player.calls)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
        }

    @Test
    fun aTuneCancelledFromOutsideDuringItsResolveStopsThePlayer() =
        runTest {
            answer(transcode)
            player.script = listOf(playing(1_300, Duration.ZERO))
            val gated = Gated(server, call = 2)
            val gatedController = TuneController(PlaybackResolver(gated) { insigniaLike() }, gated, player) { 1_000L }
            gatedController.tune(session, item, Duration.ZERO, null)
            val second = launch { gatedController.tune(session, item, Duration.ZERO, null) }
            runCurrent()
            second.cancel()
            runCurrent()
            assertEquals(listOf("play", "stop"), player.calls)
        }

    @Test
    fun aNewTuneStopsThePreviousPlaysEncode() =
        runTest {
            answer(transcode)
            player.script = listOf(playing(1_300, Duration.ZERO), playing(1_300, Duration.ZERO))
            played()
            assertTrue(server.stoppedEncodes.isEmpty())
            played()
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
            // The player is not stopped in between: the new play replaces the item.
            assertEquals(listOf("play", "play"), player.calls)
        }

    @Test
    fun aNewTuneAfterDirectPlayStopsNoEncode() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO), playing(1_300, Duration.ZERO))
            played()
            played()
            assertTrue(server.stoppedEncodes.isEmpty())
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
            // The first one's clean-up (its encode) ran before the second one played, never after. The player is not
            // stopped in between: the new play replaces the item.
            assertEquals(listOf("play", "play"), player.calls)
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
            assertNull(outcome.fallback)
            assertNull(outcome.positionError)
            assertEquals(1, player.played.size)
        }

    @Test
    fun theDefaultToleranceIsTwoSecondsAndFallsBackBeyondIt() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, 3.seconds), playing(2_000, 60.seconds))
            val outcome = played()
            assertEquals(Fallback(Route.DIRECT_PLAY, FallbackCause.SeekMiss), outcome.fallback)
            assertEquals(2, player.played.size)
        }

    @Test
    fun aPreparedTuneWithAStartOverridePlaysFromThatStart() =
        runTest {
            for (media in listOf(source(), PlaybackTestData.source(directPlay = false, directStream = false))) {
                player.played.clear()
                answer(media)
                player.script = listOf(playing(1_300, 45.seconds))
                val prepared = checkNotNull(controller.preResolve(session, item, 10.seconds))
                val outcome = controller.tune(session, prepared, null, 45.seconds) as TuneResult.Played
                assertEquals(1, player.played.size)
                assertEquals(45.seconds, player.played.single().playerStart)
                assertEquals(media.supportsDirectPlay, !player.played.single().isHls)
                assertNull(outcome.fallback)
            }
        }

    @Test
    fun aNullStartKeepsTheHandlesStart() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, 10.seconds))
            val prepared = checkNotNull(controller.preResolve(session, item, 10.seconds))
            controller.tune(session, prepared, null, null)
            assertEquals(10.seconds, player.played.single().playerStart)
        }
}
