// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.view.SurfaceView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The spec 24 calls have no-op defaults, so a player that cannot warm (every fake) needs no change. */
class VideoPlayerDefaultsTest {
    /** Implements only what a player had before spec 24. */
    private class BarePlayer : VideoPlayer {
        override val events: SharedFlow<PlayerEvent> = MutableSharedFlow()
        override val videoAspect: StateFlow<Float?> = MutableStateFlow(null)

        override fun play(source: PlayerSource): Long = 1L

        override fun stop() = Unit

        override fun attach(surface: SurfaceView?) = Unit

        override fun release() = Unit

        override fun pause(generation: Long) = Unit

        override fun resume(generation: Long) = Unit

        override fun seekTo(
            generation: Long,
            position: Duration,
        ) = Unit

        override fun position(generation: Long): Duration? = null

        override suspend fun currentPosition(generation: Long): Duration? = null

        override fun seekable(generation: Long): Boolean = false
    }

    private val source =
        PlayerSource(
            url = "https://server.example/secret?api_key=t0ken",
            isHls = false,
            playerStart = 5.seconds,
            subtitles = emptyList(),
            audioIndex = null,
            subtitleIndex = null,
            directPlayTracks = true,
            warmKey = "w7",
        )

    @Test
    fun aBarePlayerHasNoWarmOutcome() {
        val player = BarePlayer()
        val generation = player.play(source)
        assertNull(player.warmOutcome(generation))
    }

    @Test
    fun aBarePlayerIgnoresTheWarmCalls() {
        val player = BarePlayer()
        player.warm("w7", source)
        player.dropWarm("w7")
        player.trimMemory(TRIM_RUNNING_MODERATE)
        assertNull(player.warmOutcome(1L))
    }

    @Test
    fun theWarmKeyDefaultsToNone() {
        assertNull(source.copy(warmKey = null).warmKey)
        assertNull(
            PlayerSource(
                url = "u",
                isHls = true,
                playerStart = Duration.ZERO,
                subtitles = emptyList(),
                audioIndex = null,
                subtitleIndex = null,
                directPlayTracks = false,
            ).warmKey,
        )
    }

    @Test
    fun toStringShowsNoKeyAndNoUrl() {
        val text = source.toString()
        assertFalse("key shown: $text", text.contains("w7"))
        assertFalse("url shown: $text", text.contains("t0ken"))
        assertTrue(text.startsWith("PlayerSource("))
    }

    private companion object {
        /** `ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE`. */
        const val TRIM_RUNNING_MODERATE = 5
    }
}
