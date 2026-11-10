// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `VideoPlayer.switchTracks` on the real Media3 player, direct play, API 25 emulator: a clip with two audio tracks
 * (`eng`, `spa`) and an embedded text subtitle. Each switch must change the track the player has selected, and the
 * picture must go on playing through it (state READY, playing, the position still advancing, no failure or end).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Media3SwitchTracksTest {
    private val clip: ByteArray =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("h264-aac-2audio-sub-10s.mp4")
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
                ApplicationProvider.getApplicationContext<Context>().let { context ->
                    Media3VideoPlayer(
                        context,
                        DefaultHttpDataSource.Factory(),
                        SystemClock::elapsedRealtime,
                        PlaybackBuffering(),
                        WarmConfig(warmNextDecoder = true),
                        WarmSwitches.None,
                        DeviceWarmth.fresh(context),
                    )
                }
        }
        scope.launch { player.events.collect { seen.trySend(it) } }
    }

    @After
    fun tearDown() {
        player.release()
        scope.cancel()
        server.close()
    }

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

    private fun source() =
        PlayerSource(
            url = server.url("/clip.mp4").toString(),
            isHls = false,
            playerStart = Duration.ZERO,
            subtitles = emptyList(),
            audioIndex = null,
            subtitleIndex = null,
            directPlayTracks = true,
        )

    /** What the player has selected, read on its own looper: the selected audio and text group (null: none). */
    private data class Selected(
        val audioGroups: Int,
        val textGroups: Int,
        val audio: Int?,
        val text: Int?,
        val textDisabled: Boolean,
        val state: Int,
        val playing: Boolean,
    )

    private fun selected(): Selected {
        var out: Selected? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val exo = player.player
            val groups = exo.currentTracks.groups
            val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val text = groups.filter { it.type == C.TRACK_TYPE_TEXT }
            out =
                Selected(
                    audioGroups = audio.size,
                    textGroups = text.size,
                    audio = audio.indexOfFirst { it.isSelected }.takeIf { it >= 0 },
                    text = text.indexOfFirst { it.isSelected }.takeIf { it >= 0 },
                    textDisabled = C.TRACK_TYPE_TEXT in exo.trackSelectionParameters.disabledTrackTypes,
                    state = exo.playbackState,
                    playing = exo.isPlaying,
                )
        }
        return checkNotNull(out)
    }

    /** Polls [selected] until [done] holds, failing with the last reading after [WAIT]. */
    private fun awaitSelected(
        why: String,
        done: (Selected) -> Boolean,
    ): Selected {
        val deadline = SystemClock.uptimeMillis() + WAIT.inWholeMilliseconds
        var now = selected()
        while (!done(now) && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            now = selected()
        }
        assertTrue("$why: $now", done(now))
        return now
    }

    /** Plays the clip until Playing, with both audio tracks and the text track reported. Returns the generation. */
    private fun playing(): Long {
        val generation = player.play(source())
        runBlocking {
            withTimeout(WAIT) {
                var event = seen.receive()
                while (event !is PlayerEvent.Playing) event = seen.receive()
            }
        }
        awaitSelected("both audio tracks and the subtitle are reported") { it.audioGroups == 2 && it.textGroups == 1 }
        return generation
    }

    /** The player is READY and playing, and its position moves over a short while: the switch did not stop it. */
    private fun assertStillPlaying(generation: Long) {
        val state = awaitSelected("playing") { it.state == Player.STATE_READY && it.playing }
        val before = runBlocking { player.currentPosition(generation) }
        Thread.sleep(PLAY_WINDOW_MS)
        val after = runBlocking { player.currentPosition(generation) }
        assertTrue("position moved from $before to $after ($state)", checkNotNull(after) > checkNotNull(before))
        val events = generateSequence { seen.tryReceive().getOrNull() }.toList()
        val broken = events.filter { it is PlayerEvent.Failed || it is PlayerEvent.Ended }
        assertEquals("no failure or end through the switch", emptyList<PlayerEvent>(), broken)
    }

    private fun switchTo(
        generation: Long,
        switch: TrackSwitch,
    ): Boolean = runBlocking { player.switchTracks(generation, switch) }

    @Test
    fun anAudioSwitchChangesTheSelectedTrackAndThePictureKeepsPlaying() {
        val generation = playing()
        val start = awaitSelected("audio selected") { it.audio != null }
        assertEquals("the first audio track plays at the start", 0, start.audio)
        assertTrue(switchTo(generation, TrackSwitch(audio = TrackPick(1, "spa", "aac"))))
        awaitSelected("the second audio track is selected") { it.audio == 1 }
        assertStillPlaying(generation)
        // And back again.
        assertTrue(switchTo(generation, TrackSwitch(audio = TrackPick(0, "eng", "aac"))))
        awaitSelected("the first audio track is selected again") { it.audio == 0 }
        assertStillPlaying(generation)
    }

    @Test
    fun anAudioPickThatIsNotTheTrackTheServerNamedChangesNothing() {
        val generation = playing()
        awaitSelected("audio selected") { it.audio != null }
        // Position 1 is Spanish, not French: refused, so the caller re-tunes.
        assertFalse(switchTo(generation, TrackSwitch(audio = TrackPick(1, "fra", "aac"))))
        // No track at position 5.
        assertFalse(switchTo(generation, TrackSwitch(audio = TrackPick(5, "eng", "aac"))))
        assertEquals(0, selected().audio)
        assertStillPlaying(generation)
    }

    @Test
    fun anEmbeddedTextSubtitleCanBeShownAndTurnedOffWhilePlaying() {
        val generation = playing()
        assertTrue(switchTo(generation, TrackSwitch(subtitles = SubtitleSwitch.To(TrackPick(0, "eng", "mov_text")))))
        val shown = awaitSelected("the text track is selected") { it.text == 0 }
        assertFalse("text is on", shown.textDisabled)
        assertStillPlaying(generation)
        assertTrue(switchTo(generation, TrackSwitch(subtitles = SubtitleSwitch.Off)))
        awaitSelected("no text track is selected") { it.text == null && it.textDisabled }
        assertStillPlaying(generation)
    }

    @Test
    fun aTextPickBeyondTheFilesTracksChangesNothing() {
        val generation = playing()
        assertFalse(switchTo(generation, TrackSwitch(subtitles = SubtitleSwitch.To(TrackPick(3, "eng", "mov_text")))))
        assertStillPlaying(generation)
    }

    @Test
    fun aSwitchForAnotherGenerationIsRefused() {
        val generation = playing()
        assertFalse(switchTo(generation + 1, TrackSwitch(audio = TrackPick(1, "spa", "aac"))))
        assertEquals(0, selected().audio)
    }

    private companion object {
        val WAIT = 15.seconds
        const val POLL_MS = 50L
        const val PLAY_WINDOW_MS = 700L
    }
}
