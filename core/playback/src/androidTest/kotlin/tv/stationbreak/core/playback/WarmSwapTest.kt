// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.content.ComponentCallbacks2
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Spec 24 on a real Media3 player: a hidden, muted standby prepares the next channel and a play with its key hands the
 * surface over to it. Needs a device class whose decoder runs without a surface (not the Insignia). The front draws on
 * an [ImageReader] surface, drained as it goes, so a surface really moves at the swap.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // One test per spec 24 player behaviour, with their shared waits.
class WarmSwapTest {
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
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val config = WarmConfig()
    private val drain = HandlerThread("warm-swap-test-surface").apply { start() }
    private val reader: ImageReader =
        ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.PRIVATE, MAX_IMAGES).apply {
            setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(drain.looper))
        }
    private lateinit var player: Media3VideoPlayer

    @Before
    fun setUp() {
        WarmLog.sink = { lines.trySend(it) }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = serve(request.headers["Range"])
            }
        server.start()
        onMain {
            player =
                Media3VideoPlayer(
                    ApplicationProvider.getApplicationContext(),
                    DefaultHttpDataSource.Factory(),
                    SystemClock::elapsedRealtime,
                    warm = config,
                )
            player.attachSurface(reader.surface)
        }
        scope.launch { player.events.collect { seen.trySend(it) } }
    }

    @After
    fun tearDown() {
        onMain { player.release() }
        WarmLog.sink = null
        scope.cancel()
        server.close()
        reader.close()
        drain.quitSafely()
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

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

    private fun sourceAt(
        start: Duration,
        key: String? = null,
        path: String = "/clip.mp4",
    ) = PlayerSource(
        url = server.url(path).toString(),
        isHls = false,
        playerStart = start,
        subtitles = emptyList(),
        audioIndex = null,
        subtitleIndex = null,
        directPlayTracks = true,
        warmKey = key,
    )

    private inline fun <reified T : PlayerEvent> await(crossinline match: (T) -> Boolean = { true }): T =
        runBlocking {
            withTimeout(WAIT) {
                var event = seen.receive()
                while (!(event is T && match(event))) event = seen.receive()
                event
            }
        }

    /** The next SBWARM line that starts with [prefix] and contains every one of [parts]. */
    private fun awaitLine(
        prefix: String,
        vararg parts: String,
    ): String =
        runBlocking {
            withTimeout(WAIT) {
                var line = lines.receive()
                while (!(line.startsWith(prefix) && parts.all { line.contains(it) })) line = lines.receive()
                line
            }
        }

    private fun eventsWithin(window: Duration): List<PlayerEvent> =
        runBlocking {
            val out = mutableListOf<PlayerEvent>()
            withTimeoutOrNull(window) { while (true) out += seen.receive() }
            out
        }

    /** Plays [start] the ordinary way and waits for it to play. */
    private fun coldPlaying(start: Duration = Duration.ZERO): Long {
        val generation = player.play(sourceAt(start))
        await<PlayerEvent.Playing> { it.generation == generation }
        return generation
    }

    /** Warms [key] at [start] and waits for its `ready` line; returns when the warm was asked for. */
    private fun warmReady(
        key: String,
        start: Duration,
    ): Long {
        val askedAt = SystemClock.elapsedRealtime()
        player.warm(key, sourceAt(start, key))
        awaitLine("ready ", "key=$key")
        return askedAt
    }

    private fun <T> onMainValue(read: () -> T): T {
        var value: T? = null
        onMain { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun front(): ExoPlayer = onMainValue { player.player }

    private fun standby(): ExoPlayer? = onMainValue { player.standbyPlayer }

    @Test
    fun standbyReachesReadySurfaceless() {
        player.warm("w1", sourceAt(2.seconds, "w1"))
        // No play has been made: there is no outcome to read yet.
        assertNull(player.warmOutcome(1L))
        val ready = awaitLine("ready ", "key=w1")
        assertTrue(ready, Regex("""readyMs=\d+ decoderInitMs=\d+""").containsMatchIn(ready))
        assertTrue(ready, ready.contains("framesDecoded="))
        assertTrue(ready, ready.contains("standbyBufKb="))
        assertNotNull(standby())
        assertNotSame("the standby is not the front", front(), standby())
        // The standby is hidden: none of its events reach the player's stream.
        assertTrue(eventsWithin(1.seconds).isEmpty())
    }

    @Test
    fun swapShowsFrameAtLivePosition() {
        coldPlaying()
        val askedAt = warmReady("w1", 2.seconds)
        val elapsed = (SystemClock.elapsedRealtime() - askedAt).milliseconds
        val start = 2.seconds + elapsed
        val generation = player.play(sourceAt(start, "w1"))
        // The frame on the surface and Playing may come in either order.
        var playing: PlayerEvent.Playing? = null
        var framed = false
        runBlocking {
            withTimeout(WAIT) {
                while (playing == null || !framed) {
                    when (val event = seen.receive()) {
                        is PlayerEvent.Playing -> if (event.generation == generation) playing = playing ?: event
                        is PlayerEvent.FirstFrame -> if (event.generation == generation) framed = true
                        else -> Unit
                    }
                }
            }
        }
        val outcome = player.warmOutcome(generation)
        assertNotNull(outcome)
        assertTrue("not swapped: $outcome", outcome!!.swapped)
        assertEquals(WarmReason.None, outcome.reason)
        val slack = config.warmSeekToleranceMs.milliseconds + FIRST_PLAY_SLACK
        val landed = playing!!.position
        assertTrue("landed $landed, live was $start", (landed - start).absoluteValue <= slack)
        awaitLine("swap shown", "playToFrameMs=")
    }

    @Test
    fun noSeekWithinTolerance() {
        coldPlaying()
        warmReady("w1", 3.seconds)
        // The standby sits paused at 3 s: a play from there is within the tolerance and is not seeked.
        val generation = player.play(sourceAt(3.seconds, "w1"))
        val swap = awaitLine("swap ", "key=w1")
        assertTrue(swap, swap.contains("seek=false"))
        val playing = await<PlayerEvent.Playing> { it.generation == generation }
        val off = (playing.position - 3.seconds).absoluteValue
        assertTrue("landed ${playing.position}", off <= config.warmSeekToleranceMs.milliseconds + FIRST_PLAY_SLACK)
    }

    @Test
    fun oldFrontIsNextStandby() {
        coldPlaying()
        val formerFront = front()
        warmReady("w1", 2.seconds)
        val warmed = standby()
        val generation = player.play(sourceAt(2.seconds, "w1"))
        await<PlayerEvent.Playing> { it.generation == generation }
        assertSame("the standby is the front now", warmed, front())
        assertSame("the old front is the standby now", formerFront, standby())
        warmReady("w2", 4.seconds)
        assertSame("w2 warmed on the former front", formerFront, standby())
        val third = player.play(sourceAt(4.seconds, "w2"))
        await<PlayerEvent.Playing> { it.generation == third }
        assertTrue(player.warmOutcome(third)!!.swapped)
        assertSame(formerFront, front())
    }

    @Test
    fun transportFollowsFront() {
        val old = coldPlaying()
        warmReady("w1", 1.seconds)
        val generation = player.play(sourceAt(1.seconds, "w1"))
        await<PlayerEvent.Playing> { it.generation == generation }
        val newFront = front()
        val oldFront = standby()!!
        player.pause(old)
        assertTrue("an old generation paused the new front", onMainValue { newFront.playWhenReady })
        player.pause(generation)
        await<PlayerEvent.Paused> { it.generation == generation }
        assertFalse(onMainValue { newFront.playWhenReady })
        player.resume(generation)
        await<PlayerEvent.Resumed> { it.generation == generation }
        await<PlayerEvent.Playing> { it.generation == generation }
        assertTrue(onMainValue { newFront.playWhenReady })
        player.seekTo(generation, 6.seconds)
        val done = await<PlayerEvent.SeekDone> { it.generation == generation }
        assertTrue("seek landed ${done.positionMs}", done.positionMs in 5_500L..6_500L)
        val position = onMainValue { player.position(generation) }
        assertNotNull(position)
        assertTrue("position $position", (position!! - 6.seconds).absoluteValue < 1.seconds)
        assertTrue(player.seekable(generation))
        assertFalse("the old front plays on", onMainValue { oldFront.playWhenReady && oldFront.isPlaying })
        assertNull(player.position(old))
    }

    @Test
    fun aspectFollowsFront() {
        // No front play: the only picture is the standby's, and while hidden its size is not the screen's.
        warmReady("w1", 2.seconds)
        Thread.sleep(SETTLE_MS)
        assertNull("a hidden standby set the shape", player.videoAspect.value)
        val generation = player.play(sourceAt(2.seconds, "w1"))
        await<PlayerEvent.Playing> { it.generation == generation }
        val expected = WIDTH.toFloat() / HEIGHT
        assertEquals(expected, player.videoAspect.value!!, ASPECT_DELTA)
        // The old front's stop reports no size the screen takes.
        Thread.sleep(SETTLE_MS)
        assertEquals(expected, player.videoAspect.value!!, ASPECT_DELTA)
    }

    @Test
    fun mismatchIsCold() {
        coldPlaying()
        val before = front()
        warmReady("w1", 2.seconds)
        val generation = player.play(sourceAt(2.seconds, "w9"))
        await<PlayerEvent.Playing> { it.generation == generation }
        val outcome = player.warmOutcome(generation)
        assertNotNull(outcome)
        assertFalse(outcome!!.swapped)
        assertEquals(WarmReason.Mismatch, outcome.reason)
        assertSame("a cold play stays on the front", before, front())
        awaitLine("dropped ", "key=w1")
    }

    @Test
    fun releaseOnStopLeavesNoCodec() {
        coldPlaying()
        warmReady("w1", 2.seconds)
        val counters: List<DecoderCounters> =
            onMainValue {
                listOfNotNull(player.player.videoDecoderCounters, player.standbyPlayer?.videoDecoderCounters)
            }
        assertEquals("a decoder on each player", 2, counters.size)
        onMain { player.release() }
        awaitLine("standby released", "reason=released")
        counters.forEach {
            it.ensureUpdated()
            assertTrue("a codec is still held: $it", it.decoderReleaseCount >= it.decoderInitCount)
        }
        assertNull(onMainValue { player.standbyPlayer })
    }

    @Test
    @Suppress("DEPRECATION") // The platform's running-moderate level (5), the threshold spec 24 names.
    fun trimReleasesStandby() {
        coldPlaying()
        warmReady("w1", 2.seconds)
        val held = standby()!!
        val counters = onMainValue { held.videoDecoderCounters }
        player.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        awaitLine("standby released", "reason=trim")
        assertNull(standby())
        counters?.let {
            it.ensureUpdated()
            assertTrue("the standby's codec is still held", it.decoderReleaseCount >= it.decoderInitCount)
        }
        player.warm("w2", sourceAt(4.seconds, "w2"))
        awaitLine("skipped ", "key=w2", "reason=disabled")
        assertNull("warming is paused for the visit", standby())
        val generation = player.play(sourceAt(4.seconds, "w2"))
        await<PlayerEvent.Playing> { it.generation == generation }
        assertEquals(WarmReason.Disabled, player.warmOutcome(generation)!!.reason)
    }

    private companion object {
        val WAIT = 30.seconds

        /** How far a play may run between its start and the Playing event, beyond the swap tolerance. */
        val FIRST_PLAY_SLACK = 500.milliseconds
        const val SETTLE_MS = 1_000L
        const val WIDTH = 320
        const val HEIGHT = 240
        const val MAX_IMAGES = 3
        const val ASPECT_DELTA = 0.01f
    }
}
