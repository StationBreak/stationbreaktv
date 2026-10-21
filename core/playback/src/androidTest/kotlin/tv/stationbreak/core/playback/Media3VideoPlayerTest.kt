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
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
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
            serverOffset = Duration.ZERO,
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

    private companion object {
        val WAIT = 30.seconds
    }
}
