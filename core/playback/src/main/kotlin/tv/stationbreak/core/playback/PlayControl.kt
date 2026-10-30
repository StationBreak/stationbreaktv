// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlin.time.Duration

/**
 * Transport for one play: every call acts on that play's generation only, so a stale handle cannot touch a newer
 * play.
 */
interface PlayControl {
    val generation: Long

    /** This play's own player events (no replay: collect before acting). */
    val events: Flow<PlayerEvent>

    fun pause()

    fun resume()

    fun seekTo(position: Duration)

    /** As [VideoPlayer.position]: null when the play is not on the player; off-looper, the last published value. */
    fun position(): Duration?

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

                override fun position(): Duration? = null

                override fun seekable(): Boolean = false
            }
    }
}

/** The [PlayControl] of the play that [player] gave [generation]. */
internal class GenerationControl(
    private val player: VideoPlayer,
    override val generation: Long,
) : PlayControl {
    override val events: Flow<PlayerEvent> = player.events.filter { it.generation == generation }

    override fun pause() = player.pause(generation)

    override fun resume() = player.resume(generation)

    override fun seekTo(position: Duration) = player.seekTo(generation, position)

    override fun position(): Duration? = player.position(generation)

    override fun seekable(): Boolean = player.seekable(generation)
}
