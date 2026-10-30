// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlin.time.Duration

/**
 * Transport for one play: every call acts on that play's generation only, so a stale handle cannot touch a newer
 * play. [position] is always exact, whatever the caller's thread.
 */
interface PlayControl {
    val generation: Long

    /**
     * This play's own player events (no replay: collect before acting). The flow never completes, even when the play
     * ends: the caller scopes the collection to the play.
     */
    val events: Flow<PlayerEvent>

    fun pause()

    fun resume()

    fun seekTo(position: Duration)

    /**
     * The live, exact position (see [VideoPlayer.currentPosition]): null when the play is not on the player. Always
     * exact, whatever the caller's thread; it suspends while the player's looper reads it.
     */
    suspend fun position(): Duration?

    fun seekable(): Boolean

    companion object {
        /** No play: calls do nothing, position is null, not seekable, no events. For tests and `None` cases only. */
        val None: PlayControl =
            object : PlayControl {
                override val generation: Long = 0L
                override val events: Flow<PlayerEvent> = emptyFlow()

                override fun pause() = Unit

                override fun resume() = Unit

                override fun seekTo(position: Duration) = Unit

                override suspend fun position(): Duration? = null

                override fun seekable(): Boolean = false
            }
    }
}

/** The [PlayControl] of the play that [player] gave [generation]; equal when both are the same. */
internal class GenerationControl(
    private val player: VideoPlayer,
    override val generation: Long,
) : PlayControl {
    override fun equals(other: Any?): Boolean {
        if (other !is GenerationControl) return false
        return other.player === player && other.generation == generation
    }

    override fun hashCode(): Int = 31 * System.identityHashCode(player) + generation.hashCode()

    override val events: Flow<PlayerEvent> = player.events.filter { it.generation == generation }

    override fun pause() = player.pause(generation)

    override fun resume() = player.resume(generation)

    override fun seekTo(position: Duration) = player.seekTo(generation, position)

    override suspend fun position(): Duration? = player.currentPosition(generation)

    override fun seekable(): Boolean = player.seekable(generation)
}
