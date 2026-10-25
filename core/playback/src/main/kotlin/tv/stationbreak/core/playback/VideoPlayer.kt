// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.view.SurfaceView
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Duration

/**
 * What the player reports, as a hot stream for the player's lifetime (not per play). Times in `atMs` come from the
 * clock the player was given. Nothing here carries a URI, a URL or an exception message: they may hold a token.
 */
sealed interface PlayerEvent {
    /** The number [VideoPlayer.play] returned for the play that produced this event. */
    val generation: Long

    /**
     * The first picture of a play. [skippedFrames] is how many video frames the decoder decoded and dropped since this
     * play began, to reach an exact seek position; null when the player cannot say.
     */
    data class FirstFrame(
        val atMs: Long,
        override val generation: Long,
        val skippedFrames: Long? = null,
    ) : PlayerEvent

    /**
     * The container is parsed: Media3 reported the play's tracks for the first time. Sent once per play. [loads] is
     * what the media loads up to now cost (diagnostics only); null when the player cannot say.
     */
    data class TracksReady(
        val atMs: Long,
        override val generation: Long,
        val loads: LoadStats? = null,
    ) : PlayerEvent

    /** Playback is running at [position] within the stream. */
    data class Playing(
        val atMs: Long,
        val position: Duration,
        override val generation: Long,
    ) : PlayerEvent

    data class Stalled(
        override val generation: Long,
    ) : PlayerEvent

    /** The media played to its end. Sent once, when the player reaches the end of the stream. */
    data class Ended(
        override val generation: Long,
    ) : PlayerEvent

    data class Cues(
        val count: Int,
        override val generation: Long,
    ) : PlayerEvent

    /**
     * A sample of the audio clock. One when audio first advances, and one more at stop if it did. The playing position
     * follows the audio clock, so the two samples bracket how far the audio ran.
     */
    data class AudioAdvanced(
        val position: Duration,
        override val generation: Long,
    ) : PlayerEvent

    /**
     * [errorCode] is the stable name of the Media3 error code (`ERROR_CODE_...`), never a class name or a message.
     * [httpStatus] is set when a media load was answered with an HTTP error.
     */
    data class Failed(
        val errorCode: String,
        val httpStatus: Int?,
        override val generation: Long,
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

    /** Ends what is playing. A second call is a no-op, and implementations must keep it so: teardown calls it twice. */
    fun stop()

    /**
     * Shows the video on [surface], replacing any earlier one; null detaches it. Runs on the player's looper, so it
     * may be called from any thread, and it works before the first [play]. The app attaches through this, so Media3
     * types stay inside this module.
     */
    fun attach(surface: SurfaceView?)

    fun release()
}
