// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.playback.AvTimings
import tv.stationbreak.core.playback.LoadStats
import tv.stationbreak.core.playback.PlayControl
import tv.stationbreak.core.playback.PlayNote
import tv.stationbreak.core.playback.PlaybackEnd
import tv.stationbreak.core.playback.StreamFacts
import tv.stationbreak.core.playback.TrackChoice
import tv.stationbreak.core.playback.WarmUse
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
 * @property from the route of the first attempt, the same names: it differs from [route] only after a [fallback]. The
 * flip gate counts direct-play flips by this, so a direct attempt that fell back to a transcode still counts.
 * @property tracksMs from the play call to Media3's first tracks report (the container parsed), null if unknown.
 * @property skippedFrames video frames decoded and dropped to reach an exact seek position, at the first picture; null
 * if unknown.
 * @property loads what the media loads before the tracks cost (count, time, bytes, container type), null if unknown.
 * @property av when the audio and video start-up steps happened, ms since the play call, null if unknown.
 * @property warm what became of the warm standby for this tune (spec 24), null if the port did not say. A hand-over
 *   that then fell back still reports warm, so a flip counts as warm only with `fallback=none`.
 */
data class TuneTimings(
    val firstFrameMs: Long? = null,
    val playingMs: Long = 0,
    val positionErrorMs: Long? = null,
    val fallback: String? = null,
    val route: String? = null,
    val from: String? = null,
    val tracksMs: Long? = null,
    val skippedFrames: Long? = null,
    val loads: LoadStats? = null,
    val av: AvTimings? = null,
    val warm: WarmUse? = null,
)

/**
 * What the reporter needs from the route that played, as the adapter learned it: the final media source, play session
 * and delivery method after any fallback, and the runtime (null when the server did not say). The item is the
 * sitting's own, so it is not here. Ids stay out of logs.
 */
data class ReportFacts(
    val sourceId: MediaSourceId,
    val playSessionId: PlaySessionId?,
    val playMethod: PlayMethod,
    val runtime: Duration?,
)

/** How one tune went, as the machine needs it. */
sealed interface TuneOutcome {
    /**
     * The picture plays. [end] emits how the play ends, or completes empty when it is stopped or replaced. [control]
     * is the transport handle of this play alone (pause, seek, position). [timings]
     * is what the controller measured. [notes] are the play's stalls and their ends (diagnostics, numbers only).
     * [report] is what the server should be told about the play (spec 16), null when the adapter had nothing.
     * [facts] is what the banner may say about the route and the tracks that play, and the versions on offer (null
     * when the adapter had nothing to say, which shows no stream line).
     */
    data class Playing(
        val end: Flow<PlaybackEnd>,
        val control: PlayControl,
        val timings: TuneTimings = TuneTimings(),
        val notes: Flow<PlayNote> = emptyFlow(),
        val report: ReportFacts? = null,
        val facts: StreamFacts? = null,
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
    /**
     * Tunes [item] from [start]. A viewer's version and tracks travel as one [choice] (an override's indices are one
     * version's, so the two cannot be given apart: ADR-0016, update 2026-11-09). Null is no choice: the carried
     * language preferences, then the server's own defaults.
     */
    suspend fun tune(
        session: Session,
        item: ItemId,
        start: Duration,
        choice: TrackChoice? = null,
    ): TuneOutcome

    suspend fun preResolve(
        session: Session,
        item: ItemId,
        start: Duration,
    ): NextHandle?

    /**
     * Plays [next] from [start] (the schedule's position now, as a handle held a while has gone stale), or from the
     * start it was resolved with when null. A [choice] of tracks, or of another version than the handle's, discards the
     * handle (it was resolved without it) and tunes the ordinary way.
     */
    suspend fun tune(
        session: Session,
        next: NextHandle,
        start: Duration? = null,
        choice: TrackChoice? = null,
    ): TuneOutcome

    suspend fun discard(
        session: Session,
        next: NextHandle,
    )

    /**
     * Asks the player to warm a standby for [next] (spec 24). True means a standby was requested, not that one is held
     * (it may still fail to build). A handle that cannot be warmed (a transcode, warming off) answers false and nothing
     * else changes. Abstract on purpose: a port must say what it does, so none silently never warms. Not suspending,
     * and it never throws. Call it from the player's looper (the main thread): a warm and a play posted from any thread
     * run in order, but only the looper can reuse the standby already built for the same handle, so a call from
     * elsewhere builds a new standby each time. The controller does no thread switch.
     */
    fun warmNext(
        session: Session,
        next: NextHandle,
    ): Boolean

    suspend fun stop(session: Session)

    suspend fun close(session: Session)
}
