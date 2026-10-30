// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.Codecs
import tv.stationbreak.core.model.DeviceCapabilities
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSource
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.MediaStreamInfo
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.PlaybackInfo
import tv.stationbreak.core.model.PlaybackRequest
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.ServerInfo
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.StreamKind
import tv.stationbreak.core.model.StreamUrls
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.VideoCodecSupport
import tv.stationbreak.core.playback.LoadStats
import tv.stationbreak.core.playback.PlaybackEnd
import tv.stationbreak.core.playback.PlaybackResolver
import tv.stationbreak.core.playback.PlayerEvent
import tv.stationbreak.core.playback.PlayerSource
import tv.stationbreak.core.playback.TuneController
import tv.stationbreak.core.playback.VideoPlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [TunePortAdapter] over the real [TuneController] (with a scripted server and player): every result the controller can
 * give maps to the one outcome the live machine needs, and `preResolve` never throws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunePortAdapterTest {
    private val item = ItemId("item1")
    private val session =
        Session(
            UserId("u1"),
            AccessToken("tok"),
            ServerInfo("Home", ServerId("abc"), ServerVersion(12, 2, 0)),
            ServerAddress(Scheme.Https, "media.example.com", 443),
            null,
            false,
        )

    /** A player that says nothing until a test tells it to. */
    private class QuietPlayer : VideoPlayer {
        private val flow = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
        override val events: SharedFlow<PlayerEvent> = flow
        var generation = 0L
        var plays = 0
        var stops = 0

        /** Plays that answer at once, as a picture at [position]. */
        var answerAt: Duration? = null

        /** When set, an answering play first reports its tracks at this time, and its first frame with [skipped]. */
        var tracksAt: Long? = null
        var skipped: Long? = null
        var loads: LoadStats? = null

        override fun play(source: PlayerSource): Long {
            plays++
            val mine = ++generation
            answerAt?.let {
                tracksAt?.let { at -> flow.tryEmit(PlayerEvent.TracksReady(at, mine, loads)) }
                flow.tryEmit(PlayerEvent.FirstFrame(950, mine, skipped))
                flow.tryEmit(PlayerEvent.Playing(1_000, it, mine))
            }
            return mine
        }

        fun emit(event: PlayerEvent) {
            flow.tryEmit(event)
        }

        override fun stop() {
            stops++
        }

        override fun pause(generation: Long) = Unit

        override fun resume(generation: Long) = Unit

        override fun seekTo(
            generation: Long,
            position: Duration,
        ) = Unit

        override fun position(generation: Long): Duration? = null

        override fun seekable(generation: Long): Boolean = false

        override fun attach(surface: SurfaceView?) = Unit

        override fun release() = Unit
    }

    private class Urls : StreamUrls {
        override fun original(source: MediaSourceId) = "https://media.example.com/original/${source.value}"

        override fun hls(
            source: MediaSource,
            audioIndex: Int?,
            burnIn: Int?,
            limits: DeviceCapabilities,
            forceTranscode: Boolean,
        ) = "https://media.example.com/hls/${source.id.value}"

        override fun subtitle(
            source: MediaSourceId,
            stream: MediaStreamInfo,
        ): String? = null
    }

    private fun stream(
        index: Int,
        kind: StreamKind,
        codec: String,
    ) = MediaStreamInfo(
        index = index,
        kind = kind,
        codec = codec,
        language = null,
        channels = if (kind == StreamKind.AUDIO) 2 else null,
        width = if (kind == StreamKind.VIDEO) 1920 else null,
        height = if (kind == StreamKind.VIDEO) 1080 else null,
        isExternal = false,
        isDefault = true,
        isForced = false,
        delivery = null,
        positionInKind = 0,
        videoProfile = if (kind == StreamKind.VIDEO) Codecs.HIGH else null,
        videoLevel = if (kind == StreamKind.VIDEO) 41 else null,
        bitDepth = if (kind == StreamKind.VIDEO) 8 else null,
    )

    private val playable =
        MediaSource(
            id = MediaSourceId("src1"),
            name = null,
            container = Codecs.MP4,
            runtime = 90.minutes,
            supportsDirectPlay = true,
            supportsDirectStream = true,
            supportsTranscoding = true,
            streams = listOf(stream(0, StreamKind.VIDEO, Codecs.H264), stream(1, StreamKind.AUDIO, Codecs.AAC)),
            defaultAudioIndex = 1,
            defaultSubtitleIndex = null,
        )

    private val capabilities =
        DeviceCapabilities(
            containers = setOf(Codecs.MP4, Codecs.MKV, Codecs.TS),
            videoCodecs =
                listOf(
                    VideoCodecSupport(
                        Codecs.H264,
                        setOf(Codecs.BASELINE, Codecs.MAIN, Codecs.HIGH),
                        41,
                        1920,
                        1080,
                        8,
                        emptySet(),
                    ),
                ),
            audioDecoders = setOf(Codecs.AAC),
            audioPassthrough = emptySet(),
            maxAudioChannels = 2,
        )

    private inner class Rig(
        test: TestScope,
        val server: MediaServer = FakeMediaServer(),
    ) {
        val player = QuietPlayer()
        private val watchScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        private val resolver = PlaybackResolver(server) { capabilities }
        val controller = TuneController(resolver, server, player, watchScope = watchScope) { 1_000L }
        val port = TunePortAdapter(controller)
        val fake get() = server as FakeMediaServer

        fun answer(source: MediaSource = playable) {
            fake.playbackInfoResult =
                ServerResult.Success(PlaybackInfo(item, PlaySessionId("ps1"), listOf(source), Urls()))
        }
    }

    @Test
    fun aPlayingTuneIsPlayingAndItsEndFlowsThrough() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer()
            rig.player.answerAt = 30.seconds
            val outcome = rig.port.tune(session, item, 30.seconds)
            val playing = outcome as TuneOutcome.Playing
            val end = async { playing.end.first() }
            runCurrent()
            rig.player.emit(PlayerEvent.Ended(rig.player.generation))
            runCurrent()
            assertEquals(PlaybackEnd.Ended, end.await())
        }

    @Test
    fun aPlayingTuneCarriesTheTimeToTracksAndTheSkippedFrames() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer()
            rig.player.answerAt = 30.seconds
            rig.player.tracksAt = 1_400
            rig.player.skipped = 12
            val loads = LoadStats(loads = 2, loadMs = 80, maxLoadMs = 60, loadKb = 12, container = "mp4", opensKb = "0")
            rig.player.loads = loads
            val playing = rig.port.tune(session, item, 30.seconds) as TuneOutcome.Playing
            // The controller's clock reads 1000 when it calls play.
            assertEquals(400L, playing.timings.tracksMs)
            assertEquals(12L, playing.timings.skippedFrames)
            assertEquals(loads, playing.timings.loads)
        }

    @Test
    fun aPlayedTuneCarriesTheControllersControl() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer()
            rig.player.answerAt = 30.seconds
            val playing = rig.port.tune(session, item, 30.seconds) as TuneOutcome.Playing
            assertEquals(rig.player.generation, playing.control.generation)
        }

    @Test
    fun aPlayingTuneCarriesItsTimings() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer()
            rig.player.answerAt = 30.seconds
            val playing = rig.port.tune(session, item, 30.seconds) as TuneOutcome.Playing
            assertEquals(null, playing.timings.fallback)
            assertEquals(null, playing.timings.positionErrorMs)
            assertEquals(true, playing.timings.firstFrameMs != null)
        }

    @Test
    fun aTuneThatMissedItsStartCarriesTheFallbackAndTheError() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer()
            rig.player.answerAt = Duration.ZERO // the picture lands at 0 whatever the request: a miss twice over
            val playing = rig.port.tune(session, item, 30.seconds) as TuneOutcome.Playing
            assertEquals("seekMiss", playing.timings.fallback)
            assertEquals(30_000L, playing.timings.positionErrorMs)
        }

    @Test
    fun aFallbackKeepsTheRouteOfTheFirstAttemptAndANormalTuneIsFromItsOwnRoute() =
        kotlinx.coroutines.test.runTest {
            val missed = Rig(this)
            missed.answer()
            missed.player.answerAt = Duration.ZERO
            val fellBack = (missed.port.tune(session, item, 30.seconds) as TuneOutcome.Playing).timings
            assertEquals("direct", fellBack.from)
            assertEquals("transcode", fellBack.route)

            val clean = Rig(this)
            clean.answer()
            clean.player.answerAt = Duration.ZERO
            val straight = (clean.port.tune(session, item, Duration.ZERO) as TuneOutcome.Playing).timings
            assertEquals("direct", straight.from)
            assertEquals(straight.route, straight.from)
        }

    @Test
    fun theRouteTheServerChoseIsCarriedInTheTimings() =
        kotlinx.coroutines.test.runTest {
            val cases =
                listOf(
                    playable to "direct",
                    playable.copy(supportsDirectPlay = false) to "stream",
                    playable.copy(supportsDirectPlay = false, supportsDirectStream = false) to "transcode",
                )
            for ((source, route) in cases) {
                val rig = Rig(this)
                rig.answer(source)
                rig.player.answerAt = Duration.ZERO
                val playing = rig.port.tune(session, item, Duration.ZERO) as TuneOutcome.Playing
                assertEquals(route, playing.timings.route)
            }
        }

    @Test
    fun aPlayingTuneCarriesWhatTheReporterNeedsFromTheFinalRoute() =
        kotlinx.coroutines.test.runTest {
            val cases =
                listOf(
                    playable to PlayMethod.DirectPlay,
                    playable.copy(supportsDirectPlay = false) to PlayMethod.DirectStream,
                    playable.copy(supportsDirectPlay = false, supportsDirectStream = false) to PlayMethod.Transcode,
                )
            for ((source, method) in cases) {
                val rig = Rig(this)
                rig.answer(source)
                rig.player.answerAt = Duration.ZERO
                val facts = (rig.port.tune(session, item, Duration.ZERO) as TuneOutcome.Playing).report
                assertEquals(method, facts?.playMethod)
                assertEquals(MediaSourceId("src1"), facts?.sourceId)
                assertEquals(90.minutes, facts?.runtime)
            }
        }

    private suspend fun TestScope.loadFailedTiming(
        code: String,
        httpStatus: Int?,
    ): String? {
        val rig = Rig(this)
        rig.answer()
        val outcome = async { rig.port.tune(session, item, Duration.ZERO) }
        runCurrent()
        rig.player.emit(PlayerEvent.Failed(code, httpStatus, rig.player.generation))
        runCurrent()
        rig.player.emit(PlayerEvent.FirstFrame(950, rig.player.generation))
        rig.player.emit(PlayerEvent.Playing(1_000, Duration.ZERO, rig.player.generation))
        runCurrent()
        return (outcome.await() as TuneOutcome.Playing).timings.fallback
    }

    @Test
    fun aLoadFailureFallbackCarriesTheErrorName() =
        kotlinx.coroutines.test.runTest {
            assertEquals(
                "loadFailed:ERROR_CODE_DECODING_FAILED",
                loadFailedTiming("ERROR_CODE_DECODING_FAILED", null),
            )
        }

    @Test
    fun aLoadFailureWithAnHttpStatusIsOneTokenInTheLog() =
        kotlinx.coroutines.test.runTest {
            assertEquals(
                "loadFailed:ERROR_CODE_IO_BAD_HTTP_STATUS_(HTTP_404)",
                loadFailedTiming("ERROR_CODE_IO_BAD_HTTP_STATUS", 404),
            )
        }

    @Test
    fun aFailedResolveIsDeadAndNotATimeout() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.fake.playbackInfoResult = ServerResult.Failure(MediaServerError.NotFound())
            assertEquals(TuneOutcome.Dead(timedOut = false), rig.port.tune(session, item, Duration.ZERO))
        }

    @Test
    fun aPlayerThatNeverPlaysIsDeadByTimeout() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer()
            assertEquals(TuneOutcome.Dead(timedOut = true), rig.port.tune(session, item, Duration.ZERO))
            assertTrue("the timeout was waited out in virtual time", currentTime >= 20_000L)
        }

    @Test
    fun aPlayerErrorIsDeadAndNotATimeout() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer()
            val outcome = async { rig.port.tune(session, item, Duration.ZERO) }
            runCurrent()
            // Both the first route and the forced-transcode fallback break.
            repeat(2) {
                rig.player.emit(PlayerEvent.Failed("ERROR_CODE_DECODER_INIT_FAILED", null, rig.player.generation))
                runCurrent()
            }
            assertEquals(TuneOutcome.Dead(timedOut = false), outcome.await())
        }

    @Test
    fun anExpiredSessionIsExpired() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.fake.playbackInfoResult = ServerResult.Failure(MediaServerError.SessionExpired())
            assertEquals(TuneOutcome.Expired, rig.port.tune(session, item, Duration.ZERO))
        }

    @Test
    fun aTuneReplacedByANewerOneIsCancelled() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer()
            val first = async { rig.port.tune(session, item, Duration.ZERO) }
            runCurrent()
            val second = async { rig.port.tune(session, item, Duration.ZERO) }
            runCurrent()
            assertEquals(TuneOutcome.Cancelled, first.await())
            second.cancelAndJoin()
        }

    @Test
    fun aStoppedTuneIsCancelled() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer()
            val tuning = async { rig.port.tune(session, item, Duration.ZERO) }
            runCurrent()
            rig.port.stop(session)
            assertEquals(TuneOutcome.Cancelled, tuning.await())
        }

    @Test
    fun aTuneAfterCloseIsCancelled() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer()
            rig.port.close(session)
            assertEquals(TuneOutcome.Cancelled, rig.port.tune(session, item, Duration.ZERO))
            assertEquals(0, rig.player.plays)
        }

    @Test
    fun aPreResolvedHandleTunesWithoutAnotherResolve() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer()
            rig.player.answerAt = 30.seconds
            val handle = checkNotNull(rig.port.preResolve(session, item, 30.seconds))
            assertEquals("NextHandle", handle.toString())
            val outcome = rig.port.tune(session, handle)
            assertTrue(outcome is TuneOutcome.Playing)
            assertEquals(1, rig.fake.playbackInfoCalls.size)
        }

    @Test
    fun aDiscardedHandleEndsItsEncode() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.answer(playable.copy(supportsDirectPlay = false, supportsDirectStream = false))
            val handle = checkNotNull(rig.port.preResolve(session, item, Duration.ZERO))
            rig.port.discard(session, handle)
            assertEquals(listOf(PlaySessionId("ps1")), rig.fake.stoppedEncodes)
        }

    @Test
    fun preResolveOfAnItemTheServerCannotGiveIsNull() =
        kotlinx.coroutines.test.runTest {
            val rig = Rig(this)
            rig.fake.playbackInfoResult = ServerResult.Failure(MediaServerError.NotFound())
            assertNull(rig.port.preResolve(session, item, Duration.ZERO))
        }

    @Test
    fun preResolveNeverThrows() =
        kotlinx.coroutines.test.runTest {
            val broken =
                object : MediaServer by FakeMediaServer() {
                    override suspend fun playbackInfo(
                        session: Session,
                        item: ItemId,
                        request: PlaybackRequest,
                    ): ServerResult<PlaybackInfo> = error("boom")
                }
            val rig = Rig(this, broken)
            assertNull(rig.port.preResolve(session, item, Duration.ZERO))
        }

    @Test
    fun preResolveStillPropagatesCancellation() =
        kotlinx.coroutines.test.runTest {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val slow =
                object : MediaServer by FakeMediaServer() {
                    override suspend fun playbackInfo(
                        session: Session,
                        item: ItemId,
                        request: PlaybackRequest,
                    ): ServerResult<PlaybackInfo> {
                        gate.await()
                        error("unreachable")
                    }
                }
            val rig = Rig(this, slow)
            var result: Any? = "not set"
            val job = launch { result = rig.port.preResolve(session, item, Duration.ZERO) }
            runCurrent()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals("a cancelled pre-resolve returns nothing", "not set", result)
            assertNotNull(rig)
        }
}
