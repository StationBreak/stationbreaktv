// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The real Media3 player on the API 25 emulator, reading a 10 s local clip over HTTP from a MockWebServer. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Media3VideoPlayerTest {
    private val clip: ByteArray =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("h264-aac-10s.mp4")
            .use { it.readBytes() }
    private val server = MockWebServer()

    @Suppress("InjectDispatcher") // A test: Unconfined subscribes to the events before the first play.
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val seen = Channel<PlayerEvent>(Channel.UNLIMITED)
    private lateinit var player: Media3VideoPlayer

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = serve(request.headers["Range"])
            }
        server.start()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player =
                Media3VideoPlayer(
                    ApplicationProvider.getApplicationContext(),
                    DefaultHttpDataSource.Factory(),
                    SystemClock::elapsedRealtime,
                )
        }
        // Listening starts before any play, so no event is missed.
        scope.launch { player.events.collect { seen.trySend(it) } }
    }

    @After
    fun tearDown() {
        player.release()
        scope.cancel()
        server.close()
    }

    /** A whole-file answer, or the part of the file a `Range: bytes=a-b` header asks for. */
    private fun serve(range: String?): MockResponse {
        val total = clip.size
        val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val from = match?.groupValues?.get(1)?.toInt() ?: 0
        val to =
            match
                ?.groupValues
                ?.get(2)
                ?.toIntOrNull()
                ?.coerceAtMost(total - 1) ?: (total - 1)
        val builder =
            MockResponse
                .Builder()
                .code(if (match == null) 200 else 206)
                .addHeader("Accept-Ranges", "bytes")
                .addHeader("Content-Type", "video/mp4")
                .body(Buffer().write(clip, from, to - from + 1))
        if (match != null) builder.addHeader("Content-Range", "bytes $from-$to/$total")
        return builder.build()
    }

    private fun sourceAt(start: Duration) =
        PlayerSource(
            url = server.url("/clip.mp4").toString(),
            isHls = false,
            playerStart = start,
            subtitles = emptyList(),
            audioIndex = null,
            subtitleIndex = null,
            directPlayTracks = true,
        )

    /** The next Playing event at or after [from], skipping anything an earlier play left behind. */
    private fun awaitPlaying(from: Duration = Duration.ZERO): PlayerEvent.Playing =
        runBlocking {
            withTimeout(WAIT) {
                var event = seen.receive()
                while (!(event is PlayerEvent.Playing && event.position >= from)) event = seen.receive()
                event
            }
        }

    @Test
    fun aLocalClipReachesPlayingNearTheStart() {
        player.play(sourceAt(3.seconds))
        val playing = awaitPlaying()
        val error = (playing.position - 3.seconds).absoluteValue
        assertTrue("landed ${playing.position}, asked for 3 s", error <= SeekCheck.TOLERANCE)
    }

    @Test
    fun thePlayerIsReused() {
        val exo = player.player
        player.play(sourceAt(Duration.ZERO))
        awaitPlaying()
        player.stop()
        player.play(sourceAt(5.seconds))
        val second = awaitPlaying(from = 3.seconds)
        assertSame("one ExoPlayer for the player's lifetime", exo, player.player)
        val error = (second.position - 5.seconds).absoluteValue
        assertTrue("landed ${second.position}, asked for 5 s", error <= SeekCheck.TOLERANCE)
    }

    @Test
    fun aSurfaceCanBeDetachedThroughTheInterfaceBeforeAnyPlay() {
        // The app attaches through VideoPlayer, never through the Media3 player.
        val video: VideoPlayer = player
        video.attach(null)
        video.play(sourceAt(Duration.ZERO))
        awaitPlaying()
    }

    /** The next audio sample of [generation], skipping every other event. */
    private fun awaitAudio(generation: Long): PlayerEvent.AudioAdvanced =
        runBlocking {
            withTimeout(WAIT) {
                var event = seen.receive()
                while (!(event is PlayerEvent.AudioAdvanced && event.generation == generation)) event = seen.receive()
                event
            }
        }

    @Test
    fun stopTakesAClosingAudioSample() {
        val generation = player.play(sourceAt(Duration.ZERO))
        val first = awaitAudio(generation)
        player.stop()
        val closing = awaitAudio(generation)
        assertTrue(
            "closing ${closing.position} is before the first ${first.position}",
            closing.position >= first.position,
        )
    }

    /** The next Ended event of [generation], skipping every other event. */
    private fun awaitEnded(generation: Long): PlayerEvent.Ended =
        runBlocking {
            withTimeout(WAIT) {
                var event = seen.receive()
                while (!(event is PlayerEvent.Ended && event.generation == generation)) event = seen.receive()
                event
            }
        }

    @Test
    fun aClipThatRunsOutEmitsEnded() {
        // Start 2 s before the end of the 10 s clip, so it runs out inside the wait.
        val generation = player.play(sourceAt(8.seconds))
        assertEquals(generation, awaitEnded(generation).generation)
    }

    /** Every event that arrives within [window], in order. */
    private fun eventsWithin(window: Duration): List<PlayerEvent> =
        runBlocking {
            val out = mutableListOf<PlayerEvent>()
            withTimeoutOrNull(window) { while (true) out += seen.receive() }
            out
        }

    /** The next event of type [T] for which [match] holds, skipping every other event. */
    private inline fun <reified T : PlayerEvent> await(crossinline match: (T) -> Boolean = { true }): T =
        runBlocking {
            withTimeout(WAIT) {
                var event = seen.receive()
                while (!(event is T && match(event))) event = seen.receive()
                event
            }
        }

    /** Plays from [start], waits for Playing, and returns the generation. */
    private fun playingAt(start: Duration = Duration.ZERO): Long {
        val generation = player.play(sourceAt(start))
        await<PlayerEvent.Playing> { it.generation == generation }
        return generation
    }

    /** The position of [generation] read on the main looper, so it is the live value, not the published one. */
    private fun livePosition(generation: Long): Duration? {
        var read: Duration? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { read = player.position(generation) }
        return read
    }

    @Test
    fun pauseEmitsPausedAndHoldsThePosition() {
        val generation = playingAt()
        player.pause(generation)
        val paused = await<PlayerEvent.Paused> { it.generation == generation }
        val first = livePosition(generation)
        assertNotNull(first)
        Thread.sleep(1_000)
        val second = livePosition(generation)
        assertNotNull(second)
        assertTrue("moved ${second!! - first!!} while paused", (second - first).absoluteValue < 100.milliseconds)
        assertTrue(paused.positionMs >= 0)
    }

    @Test
    fun resumeEmitsResumedThenPlaying() {
        val generation = playingAt()
        player.pause(generation)
        await<PlayerEvent.Paused> { it.generation == generation }
        val before = livePosition(generation)!!
        player.resume(generation)
        await<PlayerEvent.Resumed> { it.generation == generation }
        await<PlayerEvent.Playing> { it.generation == generation }
        Thread.sleep(1_000)
        assertTrue("position did not advance", livePosition(generation)!! > before)
    }

    @Test
    fun aSeekEmitsSeekingThenSeekDoneAndNoStalled() {
        val generation = playingAt(1.seconds)
        player.seekTo(generation, 5.seconds)
        val between = mutableListOf<PlayerEvent>()
        runBlocking {
            withTimeout(WAIT) {
                var event = seen.receive()
                while (!(event is PlayerEvent.Seeking && event.generation == generation)) event = seen.receive()
                assertEquals(5_000L, (event as PlayerEvent.Seeking).toMs)
                event = seen.receive()
                while (!(event is PlayerEvent.SeekDone && event.generation == generation)) {
                    between += event
                    event = seen.receive()
                }
                assertTrue((event as PlayerEvent.SeekDone).positionMs in 4_500L..5_500L)
            }
        }
        assertTrue("stalled during the seek", between.none { it is PlayerEvent.Stalled && it.generation == generation })
        val after = eventsWithin(1.seconds)
        assertTrue("a second SeekDone", after.none { it is PlayerEvent.SeekDone })
    }

    @Test
    fun aSeekWhilePausedStaysPaused() {
        val generation = playingAt()
        player.pause(generation)
        await<PlayerEvent.Paused> { it.generation == generation }
        player.seekTo(generation, 5.seconds)
        await<PlayerEvent.SeekDone> { it.generation == generation }
        val after = eventsWithin(1.seconds)
        assertTrue("played on after a paused seek", after.none { it is PlayerEvent.Playing })
    }

    @Test
    fun aSeekToTheCurrentPositionStillCompletes() {
        val generation = playingAt()
        player.pause(generation)
        await<PlayerEvent.Paused> { it.generation == generation }
        player.seekTo(generation, livePosition(generation)!!)
        val events = eventsWithin(2.seconds)
        assertEquals(1, events.count { it is PlayerEvent.SeekDone && it.generation == generation })
    }

    @Test
    fun aSeekInsideTheBufferWhilePausedCompletes() {
        val generation = playingAt()
        Thread.sleep(3_000)
        player.pause(generation)
        await<PlayerEvent.Paused> { it.generation == generation }
        player.seekTo(generation, 1.seconds)
        val events = eventsWithin(2.seconds)
        assertEquals(1, events.count { it is PlayerEvent.SeekDone && it.generation == generation })
        assertTrue("stalled", events.none { it is PlayerEvent.Stalled })
    }

    @Test
    fun overlappingSeeksGiveOneSeekingAndOneSeekDone() {
        val generation = playingAt()
        player.seekTo(generation, 4.seconds)
        player.seekTo(generation, 6.seconds)
        val events = eventsWithin(3.seconds).filter { it.generation == generation }
        assertEquals(1, events.count { it is PlayerEvent.Seeking })
        val done = events.filterIsInstance<PlayerEvent.SeekDone>()
        assertEquals(1, done.size)
        assertTrue("landed ${done.single().positionMs}", done.single().positionMs in 5_500L..6_500L)
    }

    @Test
    fun aSeekToTheEndCompletesWithSeekDone() {
        val generation = playingAt()
        player.seekTo(generation, 60.seconds)
        await<PlayerEvent.SeekDone> { it.generation == generation }
    }

    @Test
    fun callsForAnOldGenerationDoNothing() {
        val first = playingAt()
        val second = playingAt()
        eventsWithin(500.milliseconds)
        player.pause(first)
        val events = eventsWithin(1.seconds)
        assertTrue("an old pause emitted", events.none { it is PlayerEvent.Paused })
        assertNull(player.position(first))
        assertNotNull(player.position(second))
        assertFalse(player.seekable(first))
    }

    @Test
    fun afterStopPositionIsNullAndNotSeekable() {
        val generation = playingAt()
        player.stop()
        eventsWithin(500.milliseconds)
        assertNull(player.position(generation))
        assertFalse(player.seekable(generation))
    }

    @Test
    fun currentPositionFromAnotherThreadIsLive() {
        val generation = playingAt()
        val earlier = runBlocking { player.currentPosition(generation) }
        assertNotNull(earlier)
        Thread.sleep(2_000)
        val later = runBlocking { player.currentPosition(generation) }
        assertNotNull(later)
        assertTrue("did not advance: $earlier then $later", later!! - earlier!! > 1.seconds)
    }

    @Test
    fun currentPositionOfAnOldOrNoGenerationIsNull() {
        val first = playingAt()
        val second = playingAt()
        assertNull(runBlocking { player.currentPosition(first) })
        assertNotNull(runBlocking { player.currentPosition(second) })
        assertNull(runBlocking { player.currentPosition(0) })
    }

    @Test
    fun generationZeroTransportAfterAStopEmitsNothing() {
        val generation = playingAt()
        player.stop()
        eventsWithin(500.milliseconds)
        player.pause(0)
        player.seekTo(0, 5.seconds)
        player.resume(0)
        val events = eventsWithin(1.seconds)
        assertTrue(
            "generation 0 emitted $events",
            events.none {
                it is PlayerEvent.Paused || it is PlayerEvent.Seeking ||
                    it is PlayerEvent.Resumed
            },
        )
        assertNull(player.position(0))
        assertNull(player.position(generation))
    }

    @Test
    fun aLocalClipIsSeekable() {
        val generation = playingAt()
        assertTrue(player.seekable(generation))
    }

    private companion object {
        val WAIT = 30.seconds
    }
}
