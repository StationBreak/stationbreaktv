// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Duration

/**
 * What the player reports, as a hot stream for the player's lifetime (not per play). Times in `atMs` come from the
 * clock the player was given. Nothing here carries a URI, a URL or an exception message: they may hold a token.
 */
sealed interface PlayerEvent {
    /** The number [VideoPlayer.play] returned for the play that produced this event. */
    val generation: Long

    data class FirstFrame(
        val atMs: Long,
        override val generation: Long,
    ) : PlayerEvent

    /** Playback is running at [position] within the stream (not counting the server's start offset). */
    data class Playing(
        val atMs: Long,
        val position: Duration,
        override val generation: Long,
    ) : PlayerEvent

    data class Stalled(
        override val generation: Long,
    ) : PlayerEvent

    data class Cues(
        val count: Int,
        override val generation: Long,
    ) : PlayerEvent

    data class AudioAdvanced(
        val position: Duration,
        override val generation: Long,
    ) : PlayerEvent

    /** [errorClass] is a class name only. [httpStatus] is set when a media load was answered with an HTTP error. */
    data class Failed(
        val errorClass: String,
        val httpStatus: Int? = null,
        override val generation: Long = 0,
    ) : PlayerEvent
}

/** One player for the host's lifetime: [play] replaces what is playing, [stop] ends it, [release] ends the player. */
interface VideoPlayer {
    val events: SharedFlow<PlayerEvent>

    /**
     * Starts [source], replacing what is playing, and returns the generation of this play. Every event the play
     * produces carries it, so a caller can ignore the events of an earlier play. Generations only increase.
     */
    fun play(source: PlayerSource): Long

    fun stop()

    fun release()
}
