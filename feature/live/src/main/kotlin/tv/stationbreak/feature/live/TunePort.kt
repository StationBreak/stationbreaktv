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

/** How one tune went, as the machine needs it. */
sealed interface TuneOutcome {
    /** The picture plays. [end] emits how the play ends, or completes empty when it is stopped or replaced. */
    data class Playing(
        val end: Flow<PlaybackEnd>,
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

    suspend fun tune(
        session: Session,
        next: NextHandle,
    ): TuneOutcome

    suspend fun discard(
        session: Session,
        next: NextHandle,
    )

    suspend fun stop(session: Session)

    suspend fun close(session: Session)
}
