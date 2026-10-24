// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.flow.Flow
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.PlaybackEnd
import kotlin.time.Duration

/** An item resolved ahead of its tune. Opaque to the machine: the adapter maps it to core:playback's handle. */
class NextHandle(
    val token: Any,
) {
    override fun toString(): String = "NextHandle"
}

/**
 * How long a tune that played took, as the controller measured it: numbers and a cause name only, safe to log. The
 * times run from the start of the tune call (a fresh tune's include its resolve; a prepared one's do not).
 *
 * @property firstFrameMs until the first picture, null if the player gave none.
 * @property playingMs until the player said it was playing.
 * @property positionErrorMs how far from the requested start the last attempt landed, set only when it missed.
 * @property fallback why the first attempt was abandoned for a forced transcode (`seekMiss`, or `loadFailed:<name>`
 * with the player's stable error name, spaces as `_`), or null.
 * @property route the route that played: `direct`, `stream` or `transcode`.
 */
data class TuneTimings(
    val firstFrameMs: Long? = null,
    val playingMs: Long = 0,
    val positionErrorMs: Long? = null,
    val fallback: String? = null,
    val route: String? = null,
)

/** How one tune went, as the machine needs it. */
sealed interface TuneOutcome {
    /**
     * The picture plays. [end] emits how the play ends, or completes empty when it is stopped or replaced. [timings]
     * is what the controller measured.
     */
    data class Playing(
        val end: Flow<PlaybackEnd>,
        val timings: TuneTimings = TuneTimings(),
    ) : TuneOutcome

    /** Nothing plays: every route failed. [timedOut] is true when the failure was a timeout (it is not retried). */
    data class Dead(
        val timedOut: Boolean,
    ) : TuneOutcome

    /** The server refused the session: sign in again. */
    data object Expired : TuneOutcome

    /** The tune was replaced, stopped, or the controller closed: the machine stops. */
    data object Cancelled : TuneOutcome
}

/**
 * The narrow seam over the tune controller, so the machine is tested without a player. The handle from [preResolve]
 * is consumed by [tune] whatever the outcome (its encode is stopped if it did not play), so the machine [discard]s a
 * handle only when it decides not to use it.
 */
interface TunePort {
    suspend fun tune(
        session: Session,
        item: ItemId,
        start: Duration,
    ): TuneOutcome

    suspend fun preResolve(
        session: Session,
        item: ItemId,
        start: Duration,
    ): NextHandle?

    /** Plays [next] from [start] (the schedule's position now, as the handle may have waited), or null for its own. */
    suspend fun tune(
        session: Session,
        next: NextHandle,
        start: Duration?,
    ): TuneOutcome

    /** Plays [next] from the start it was resolved with. */
    suspend fun tune(
        session: Session,
        next: NextHandle,
    ): TuneOutcome = tune(session, next, null)

    suspend fun discard(
        session: Session,
        next: NextHandle,
    )

    suspend fun stop(session: Session)

    suspend fun close(session: Session)
}
