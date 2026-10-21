// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Duration

/**
 * What the player reports, as a hot stream for the player's lifetime (not per play). Times in `atMs` come from the
 * clock the player was given. Nothing here carries a URI, a URL or an exception message: they may hold a token.
 */
sealed interface PlayerEvent {
    data class FirstFrame(
        val atMs: Long,
    ) : PlayerEvent

    /** Playback is running at [position] within the stream (not counting the server's start offset). */
    data class Playing(
        val atMs: Long,
        val position: Duration,
    ) : PlayerEvent

    data object Stalled : PlayerEvent

    data class Cues(
        val count: Int,
    ) : PlayerEvent

    data class AudioAdvanced(
        val position: Duration,
    ) : PlayerEvent

    /** [errorClass] is a class name only. [httpStatus] is set when a media load was answered with an HTTP error. */
    data class Failed(
        val errorClass: String,
        val httpStatus: Int? = null,
    ) : PlayerEvent
}

/** One player for the host's lifetime: [play] replaces what is playing, [stop] ends it, [release] ends the player. */
interface VideoPlayer {
    val events: SharedFlow<PlayerEvent>

    fun play(source: PlayerSource)

    fun stop()

    fun release()
}
