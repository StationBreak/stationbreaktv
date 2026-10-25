// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
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

/** Pre-resolving the next item, and how a play ends. */
@OptIn(ExperimentalCoroutinesApi::class)
class TuneControllerPreparedTest {
    private val server = FakeMediaServer()
    private val urls = RecordingUrls()
    private val player = FakeVideoPlayer()
    private val item = ItemId("item1")
    private val resolver = PlaybackResolver(server) { insigniaLike() }
    private val scheduler = TestCoroutineScheduler()
    private val watchScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val controller = TuneController(resolver, server, player, watchScope = watchScope) { 1_000L }

    private fun liveWatchers() = watchScope.coroutineContext[Job]!!.children.count { it.isActive }

    private fun noWatcherIsLeft() = liveWatchers() == 0

    private fun runTest(body: suspend TestScope.() -> Unit) =
        kotlinx.coroutines.test.runTest(StandardTestDispatcher(scheduler), testBody = body)

    private val session = PlaybackTestData.session
    private val transcode get() = source(directPlay = false, directStream = false)

    private fun answer(media: MediaSource) {
        server.playbackInfoResult =
            ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), listOf(media), urls))
    }

    private fun playing(
        atMs: Long,
        position: Duration,
    ) = listOf(Step { PlayerEvent.FirstFrame(atMs - 50, it) }, Step { PlayerEvent.Playing(atMs, position, it) })

    private fun failed(errorCode: String) = listOf(Step { PlayerEvent.Failed(errorCode, null, it) })

    private suspend fun played() = controller.tune(session, item, Duration.ZERO, null) as TuneResult.Played

    /** Holds the [gateInfoCall]th playback-info request and the first stop-encoding request until their gates open. */
    private class Held(
        private val inner: MediaServer,
        private val gateInfoCall: Int = 0,
        val infoGate: CompletableDeferred<Unit> = CompletableDeferred(),
        val stopGate: CompletableDeferred<Unit>? = null,
    ) : MediaServer by inner {
        private var infoCalls = 0
        private var stopCalls = 0

        override suspend fun playbackInfo(
            session: Session,
            item: ItemId,
            request: PlaybackRequest,
        ): ServerResult<PlaybackInfo> {
            if (++infoCalls == gateInfoCall) infoGate.await()
            return inner.playbackInfo(session, item, request)
        }

        override suspend fun stopEncoding(
            session: Session,
            playSession: PlaySessionId,
        ): ServerResult<Unit> {
            if (++stopCalls == 1) stopGate?.await()
            return inner.stopEncoding(session, playSession)
        }
    }

    private fun controllerOver(held: Held) =
        TuneController(PlaybackResolver(held) { insigniaLike() }, held, player, watchScope = watchScope) { 1_000L }

    /** A server whose first playback-info request waits for [gate], so a test can close the controller meanwhile. */
    private class Gated(
        private val inner: MediaServer,
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : MediaServer by inner {
        override suspend fun playbackInfo(
            session: Session,
            item: ItemId,
            request: PlaybackRequest,
        ): ServerResult<PlaybackInfo> {
            gate.await()
            return inner.playbackInfo(session, item, request)
        }
    }

    @Test
    fun aPreparedTuneMakesNoResolveCall() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, 30.seconds))
            val prepared = checkNotNull(controller.preResolve(session, item, 30.seconds))
            val outcome = controller.tune(session, prepared, null) as TuneResult.Played
            assertEquals(1, server.playbackInfoCalls.size)
            assertEquals(Route.DIRECT_PLAY, outcome.resolved.route)
            assertEquals(1, player.played.size)
        }

    @Test
    fun discardStopsThePreparedEncode() =
        runTest {
            answer(transcode)
            val prepared = checkNotNull(controller.preResolve(session, item, Duration.ZERO))
            controller.discard(session, prepared)
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
            controller.discard(session, prepared)
            assertEquals("a second discard is a no-op", 1, server.stoppedEncodes.size)
        }

    @Test
    fun discardAfterTuneIsANoOp() =
        runTest {
            answer(transcode)
            player.script = listOf(playing(1_300, Duration.ZERO))
            val prepared = checkNotNull(controller.preResolve(session, item, Duration.ZERO))
            controller.tune(session, prepared, null)
            controller.discard(session, prepared)
            assertTrue(server.stoppedEncodes.isEmpty())
        }

    @Test
    fun closeDiscardsEveryOutstandingPrepared() =
        runTest {
            answer(transcode)
            checkNotNull(controller.preResolve(session, item, Duration.ZERO))
            checkNotNull(controller.preResolve(session, item, 5.seconds))
            controller.close(session)
            assertEquals(2, server.stoppedEncodes.size)
        }

    @Test
    fun aPreResolveThatFinishesAfterCloseIsDiscarded() =
        runTest {
            answer(transcode)
            val gated = Gated(server)
            val gatedController = TuneController(PlaybackResolver(gated) { insigniaLike() }, gated, player) { 1_000L }
            val preparing = async { gatedController.preResolve(session, item, Duration.ZERO) }
            runCurrent()
            gatedController.close(session)
            gated.gate.complete(Unit)
            assertNull(preparing.await())
            assertEquals(listOf(PlaySessionId("ps1")), server.stoppedEncodes)
        }

    @Test
    fun aPreparedTuneAfterCloseNeverPlaysAndStopsItsEncode() =
        runTest {
            answer(transcode)
            val prepared = checkNotNull(controller.preResolve(session, item, Duration.ZERO))
            controller.close(session)
            assertEquals(TuneResult.Stopped, controller.tune(session, prepared, null))
            assertTrue(player.played.isEmpty())
            assertEquals(1, server.stoppedEncodes.size)
        }

    @Test
    fun aPreparedTuneWithAnOverrideStopsTheStaleEncodeBeforeResolving() =
        runTest {
            answer(transcode)
            val held = Held(server, gateInfoCall = 2)
            val heldController = controllerOver(held)
            player.script = listOf(playing(1_300, Duration.ZERO))
            val prepared = checkNotNull(heldController.preResolve(session, item, Duration.ZERO))
            val override = TrackOverride(audioIndex = 2, subtitleIndex = null)
            val tuning = async { heldController.tune(session, prepared, override) }
            runCurrent()
            assertTrue("the fresh resolve is still waiting", tuning.isActive)
            val before = "stopped before the fresh resolve answers"
            assertEquals(before, listOf(PlaySessionId("ps1")), server.stoppedEncodes)
            held.infoGate.complete(Unit)
            assertTrue(tuning.await() is TuneResult.Played)
            assertEquals("stopped exactly once", 1, server.stoppedEncodes.size)
        }

    @Test
    fun preResolveFailureIsNull() =
        runTest {
            server.playbackInfoResult = ServerResult.Failure(MediaServerError.NotFound())
            assertNull(controller.preResolve(session, item, Duration.ZERO))
        }

    @Test
    fun aPreparedFailureFallsBackWithAFreshResolve() =
        runTest {
            answer(source())
            player.script = listOf(failed("ERROR_CODE_DECODING_FAILED"), playing(2_000, Duration.ZERO))
            val prepared = checkNotNull(controller.preResolve(session, item, Duration.ZERO))
            val outcome = controller.tune(session, prepared, null) as TuneResult.Played
            assertEquals(2, server.playbackInfoCalls.size)
            assertEquals(Route.TRANSCODE, outcome.resolved.route)
        }

    @Test
    fun playedEndReportsEnded() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO) + Step(afterMs = 5_000) { PlayerEvent.Ended(it) })
            player.scope = this
            val outcome = played()
            assertEquals(listOf<PlaybackEnd>(PlaybackEnd.Ended), outcome.end.toList())
        }

    @Test
    fun playedEndReportsAFailureAfterPlaying() =
        runTest {
            answer(source())
            val late = Step(afterMs = 5_000) { PlayerEvent.Failed("ERROR_CODE_IO_UNSPECIFIED", null, it) }
            player.script = listOf(playing(1_300, Duration.ZERO) + late)
            player.scope = this
            val outcome = played()
            assertEquals(listOf<PlaybackEnd>(PlaybackEnd.Failed("ERROR_CODE_IO_UNSPECIFIED")), outcome.end.toList())
        }

    @Test
    fun aLongStallAfterPlayingEndsAsFailed() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO) + Step(afterMs = 1_000) { PlayerEvent.Stalled(it) })
            player.scope = this
            val outcome = played()
            val ends = outcome.end.toList()
            assertTrue("a stall past the playing timeout fails: $ends", ends.single() is PlaybackEnd.Failed)
            assertEquals("the stall timer starts at the stall", 21_000L, currentTime)
        }

    @Test
    fun aStallThatRecoversDoesNotEndThePlay() =
        runTest {
            answer(source())
            val steps =
                playing(1_300, Duration.ZERO) +
                    Step(afterMs = 1_000) { PlayerEvent.Stalled(it) } +
                    Step(afterMs = 5_000) { PlayerEvent.Playing(6_000, 5.seconds, it) } +
                    Step(afterMs = 60_000) { PlayerEvent.Ended(it) }
            player.script = listOf(steps)
            player.scope = this
            val outcome = played()
            assertEquals(listOf<PlaybackEnd>(PlaybackEnd.Ended), outcome.end.toList())
        }

    @Test
    fun endIgnoresEventsOfOtherGenerations() =
        runTest {
            answer(source())
            val other = Step(afterMs = 2_000) { PlayerEvent.Ended(it + 1) }
            val mine = Step(afterMs = 4_000) { PlayerEvent.Failed("ERROR_CODE_IO_UNSPECIFIED", null, it) }
            player.script = listOf(playing(1_300, Duration.ZERO) + other + mine)
            player.scope = this
            val outcome = played()
            assertEquals(listOf<PlaybackEnd>(PlaybackEnd.Failed("ERROR_CODE_IO_UNSPECIFIED")), outcome.end.toList())
        }

    @Test
    fun endIsReportedToALateCollector() =
        runTest {
            answer(source())
            // Ended arrives inside the play, long before anyone collects.
            player.script = listOf(playing(1_300, Duration.ZERO) + Step { PlayerEvent.Ended(it) })
            val outcome = played()
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()
            assertEquals(listOf<PlaybackEnd>(PlaybackEnd.Ended), outcome.end.toList())
            assertEquals("a second collector gets it too", listOf<PlaybackEnd>(PlaybackEnd.Ended), outcome.end.toList())
        }

    @Test
    fun stopCancelsTheWatcherAndEndCompletesWithoutAValue() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO))
            val outcome = played()
            controller.stop(session)
            assertTrue(outcome.end.toList().isEmpty())
            assertTrue("no watcher is left", noWatcherIsLeft())
        }

    @Test
    fun closeCancelsTheWatcher() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO))
            val outcome = played()
            controller.close(session)
            assertTrue(outcome.end.toList().isEmpty())
            assertTrue(noWatcherIsLeft())
        }

    @Test
    fun aNewTuneReplacesTheWatcher() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO), playing(1_400, Duration.ZERO))
            val first = played()
            played()
            assertTrue("the first play's end completes empty", first.end.toList().isEmpty())
            assertEquals(1, liveWatchers())
        }

    @Test
    fun aPreparedTuneStoppedWhileItsPlayWaitsEndsBothEncodes() =
        runTest {
            answer(transcode)
            // The old encode's stop is slow, but the tune does not wait for it: the prepared play starts at once.
            val held = Held(server, stopGate = CompletableDeferred())
            val held2 = controllerOver(held)
            player.script = listOf(playing(1_300, Duration.ZERO), emptyList())
            held2.tune(session, item, Duration.ZERO, null)
            val prepared = checkNotNull(held2.preResolve(session, item, Duration.ZERO))
            val tuning = async { held2.tune(session, prepared, null) }
            runCurrent()
            assertEquals("the prepared play did not wait for the old stop", 2, player.played.size)
            val stopping = async { held2.stop(session) }
            runCurrent()
            assertEquals(TuneResult.Stopped, tuning.await())
            checkNotNull(held.stopGate).complete(Unit)
            stopping.await()
            runCurrent()
            assertEquals("the old encode and the prepared one are both stopped", 2, server.stoppedEncodes.size)
        }

    @Test
    fun aPreparedTuneCancelledBeforeItTakesTheLockStopsItsEncodeOnce() =
        runTest {
            answer(transcode)
            val prepared = checkNotNull(controller.preResolve(session, item, Duration.ZERO))
            // Runs up to the wait for its job, which has not got the lock yet; the cancel comes before it does.
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) { controller.tune(session, prepared, null) }
            waiting.cancel()
            runCurrent()
            assertEquals("the handle's encode is stopped once", 1, server.stoppedEncodes.size)
            assertTrue("it never played", player.played.isEmpty())
        }

    @Test
    fun aPreparedTuneCancelledWhileItsPlayWaitsStopsItsEncode() =
        runTest {
            answer(transcode)
            val held2 = controllerOver(Held(server))
            player.script = listOf(playing(1_300, Duration.ZERO), emptyList())
            held2.tune(session, item, Duration.ZERO, null)
            val first = checkNotNull(held2.preResolve(session, item, Duration.ZERO))
            val waiting = launch { held2.tune(session, first, null) }
            runCurrent()
            waiting.cancel()
            runCurrent()
            assertEquals("the old encode and the cancelled tune's own are both stopped", 2, server.stoppedEncodes.size)
        }

    @Test
    fun aNewerTuneSilencesTheOldEndBeforeItResolves() =
        runTest {
            answer(source())
            val held = Held(server, gateInfoCall = 2)
            val held2 = controllerOver(held)
            val late = Step(afterMs = 5_000) { PlayerEvent.Ended(it) }
            player.script = listOf(playing(1_300, Duration.ZERO) + late, playing(7_000, Duration.ZERO))
            player.scope = this
            val old = held2.tune(session, item, Duration.ZERO, null) as TuneResult.Played
            val newer = async { held2.tune(session, item, Duration.ZERO, null) }
            runCurrent()
            testScheduler.advanceTimeBy(6_000)
            testScheduler.runCurrent()
            assertTrue("the old end is silent once a newer tune has the lock", old.end.toList().isEmpty())
            held.infoGate.complete(Unit)
            newer.await()
        }

    @Test
    fun aStallFailureLeavesNoWatcher() =
        runTest {
            answer(source())
            player.script = listOf(playing(1_300, Duration.ZERO) + Step(afterMs = 1_000) { PlayerEvent.Stalled(it) })
            player.scope = this
            val outcome = played()
            assertTrue(outcome.end.toList().single() is PlaybackEnd.Failed)
            testScheduler.runCurrent()
            assertTrue("the watcher ended with the stall", noWatcherIsLeft())
        }

    @Test
    fun aPlayerThatThrowsOnPlayLeavesNoWatcher() =
        runTest {
            answer(source())
            player.onPlay = { error("boom") }
            val outcome = runCatching { played() }
            assertTrue(outcome.isFailure)
            testScheduler.runCurrent()
            assertTrue(noWatcherIsLeft())
        }

    @Test
    fun aFailureAfterPlayingCarriesItsNumbers() =
        runTest {
            answer(source())
            val late =
                Step(afterMs = 12_000) {
                    PlayerEvent.Failed("ERROR_CODE_IO_UNSPECIFIED", 503, it, 13_300, 11_900, 0)
                }
            player.script = listOf(playing(1_300, Duration.ZERO) + late)
            player.scope = this
            val outcome = played()
            assertEquals(
                listOf<PlaybackEnd>(PlaybackEnd.Failed("ERROR_CODE_IO_UNSPECIFIED", 503, 12_000, 11_900, 0, 0)),
                outcome.end.toList(),
            )
        }

    @Test
    fun aStallTimeoutCarriesTheStallsNumbers() =
        runTest {
            answer(source())
            val stall = Step(afterMs = 1_000) { PlayerEvent.Stalled(it, 6_300, positionMs = 5_000, bufferedMs = 40) }
            player.script = listOf(playing(1_300, Duration.ZERO) + stall)
            player.scope = this
            val outcome = played()
            assertEquals(
                listOf<PlaybackEnd>(PlaybackEnd.Failed("StallTimeout", -1, 25_000, 5_000, 40, 1)),
                outcome.end.toList(),
            )
        }

    @Test
    fun aStallAndItsResumeAreNoted() =
        runTest {
            answer(source())
            val steps =
                playing(1_300, Duration.ZERO) +
                    Step(afterMs = 1_000) { PlayerEvent.Stalled(it, 4_300, positionMs = 3_000, bufferedMs = 10) } +
                    Step(afterMs = 3_000) { PlayerEvent.Playing(6_800, 3.seconds, it) } +
                    Step(afterMs = 4_000) { PlayerEvent.Ended(it) }
            player.script = listOf(steps)
            player.scope = this
            val outcome = played()
            assertEquals(listOf<PlaybackEnd>(PlaybackEnd.Ended), outcome.end.toList())
            assertEquals(
                listOf(PlayNote.Stall(3_000, 10, 3_000), PlayNote.Resumed(2_500)),
                outcome.notes.toList(),
            )
        }

    @Test
    fun onlyTwentyStallsPerPlayAreNoted() =
        runTest {
            answer(source())
            val stalls =
                (1..25).flatMap { n ->
                    listOf(
                        Step(afterMs = 100) { PlayerEvent.Stalled(it, 2_000L + n * 1_000, n * 10L, 0) },
                        Step(afterMs = 100) { PlayerEvent.Playing(2_500L + n * 1_000, n.seconds, it) },
                    )
                }
            val over = Step(afterMs = 100) { PlayerEvent.Ended(it) }
            player.script = listOf(playing(1_300, Duration.ZERO) + stalls + over)
            player.scope = this
            val outcome = played()
            outcome.end.toList()
            val notes = outcome.notes.toList()
            assertEquals(20, notes.filterIsInstance<PlayNote.Stall>().size)
            assertEquals(20, notes.filterIsInstance<PlayNote.Resumed>().size)
        }
}
