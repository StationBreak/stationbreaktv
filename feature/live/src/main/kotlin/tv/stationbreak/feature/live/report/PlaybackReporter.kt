// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.PlayMethod
import tv.stationbreak.core.model.PlaySessionId
import tv.stationbreak.core.model.Session
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * What the reporter needs to know about the item that is playing: the final media source, play session and delivery
 * method after any fallback, and the [runtime] (null when the server did not say, which means never marked played).
 */
data class ReportTarget(
    val itemId: ItemId,
    val sourceId: MediaSourceId,
    val playSessionId: PlaySessionId?,
    val playMethod: PlayMethod,
    val runtime: Duration?,
)

/**
 * Tells the server what the viewer is watching (ADR-0018, spec 16) without ever slowing a tune, hold, cut or flip.
 *
 * Every method only queues an event and returns: none suspends or waits on the network (R1). One worker, launched in
 * [scope], takes the events one at a time, so a Stopped for one airing always goes out before the Playing for the next.
 * [scope] belongs to the caller and must outlive the sittings (a supervisor scope owned by `LiveChannel.run`); each
 * server call is abandoned after [ReportingConfig.callTimeout]. Callers must [closeAndJoin] before cancelling [scope],
 * or the final Stopped is lost with the worker.
 *
 * Policy (the numbers are [config]'s):
 * - A visit is one airing ([AiringKey], R3). Watched time lives in a [WatchLedger] for the life of the reporter.
 * - On a fresh sitting the item is reported after `startAfter`; on a sitting older than that, at once (R4). Reporting
 *   starts with a user-data read (at most `userDataAttempts` per visit, one per tick), then Playing at the safe
 *   position: the item's resume position, or 0 when it is played or the app has marked it played (R7). Then a Progress
 *   at that same position every `progressEvery`, and Stopped(failed) at the same position on leave. The real playing
 *   position is never sent.
 * - While paused, check-ins carry `IsPaused` (still at the safe position) for `pauseReportMax`, then nothing is sent;
 *   the next play sends a fresh Playing at once. A leave still sends Stopped (ADR-0021 decision 7).
 * - Once watched time reaches `playedThreshold` of the runtime, the airing is marked played, once, whatever the
 *   user-data read did (R5, R6). A 401 stops all reporting for the run (R9).
 *
 * The caller owns what counts as one visit: a retry of a failed play stays inside the open visit (a [visit] of the
 * open airing takes the newest target, a stall/[resumed] pair marks the gap), and [leave] comes only when the block is
 * left for good, which sends Stopped. Stall notes beyond the tune controller's cap of 20 per play over-count
 * watched time.
 *
 * Log lines hold only labels and numbers: `report start|progress|stop|played|silent afterMs=<n>|error kind=<label>`.
 */
class PlaybackReporter(
    scope: CoroutineScope,
    server: MediaServer,
    session: Session,
    private val clock: Clock,
    config: ReportingConfig = ReportingConfig(),
    log: (String) -> Unit,
) {
    private val events = Channel<ReportEvent>(Channel.UNLIMITED)
    private val limitDefault = config.callTimeout * ReportingConfig.DRAIN_CALLS
    private val worker: Job

    init {
        val calls = ReportCalls(config, log)
        val reportWorker =
            ReportWorker(scope, server, session, clock, config, calls, log) { events.trySend(it) }
        worker =
            scope.launch {
                for (event in events) {
                    if (!reportWorker.handle(event)) break
                }
            }
    }

    /** A new sitting began (a tune or a flip lands). Older than `startAfter`, it reports each item at once. */
    fun sittingStarted(sittingId: Long) {
        events.trySend(ReportEvent.Sitting(sittingId, clock.now()))
    }

    /**
     * The viewer is now on [airing] playing [target] from media position [fromMs]. The same airing again in the same
     * sitting continues it.
     */
    fun visit(
        sittingId: Long,
        airing: AiringKey,
        target: ReportTarget,
        fromMs: Long,
    ) {
        events.trySend(ReportEvent.Visit(sittingId, airing, target, clock.now(), fromMs))
    }

    /**
     * The picture stalled at [positionMs] when known; the time until [resumed] (or [leave]) is not watched time.
     */
    fun stall(positionMs: Long? = null) {
        events.trySend(ReportEvent.Stall(clock.now(), positionMs))
    }

    /** The picture runs again from where it stopped, or from [fromMs] (a retry at the place). */
    fun resumed(fromMs: Long? = null) {
        events.trySend(ReportEvent.Resumed(clock.now(), fromMs))
    }

    /** The viewer paused, at [positionMs] when known. Check-ins say so for `pauseReportMax`, then go silent. */
    fun paused(positionMs: Long? = null) {
        events.trySend(ReportEvent.Paused(clock.now(), positionMs))
    }

    /** The viewer played on after a pause. After the silence, a fresh Playing goes out at once. */
    fun unpaused() {
        events.trySend(ReportEvent.Unpaused(clock.now()))
    }

    /** A seek began from [positionMs] when known: the time until [seeked] is not watched time. */
    fun seeking(positionMs: Long? = null) {
        events.trySend(ReportEvent.Seeking(clock.now(), positionMs))
    }

    /** The seek is done at media position [toMs]: watched time runs on from there. */
    fun seeked(toMs: Long) {
        events.trySend(ReportEvent.Seeked(clock.now(), toMs))
    }

    /**
     * The viewer left the item for good (boundary, flip, exit, or a dead item). Stopped follows, queued behind what is
     * already waiting, if it was reported as playing.
     */
    fun leave() {
        events.trySend(ReportEvent.Leave(clock.now()))
    }

    /** The run ended: leaves any open item, sends what is queued, then the reporter ignores everything. */
    fun close() {
        events.trySend(ReportEvent.Close(clock.now()))
        events.close()
    }

    /**
     * Like [close], then waits for the worker to send the final Stopped and finish, for at most [limit] (a hung call is
     * cut there), and stops the worker. Safe to call from a `finally` of a cancelled coroutine. Call it before
     * cancelling the reporter's scope.
     */
    suspend fun closeAndJoin(limit: Duration = limitDefault) {
        withContext(NonCancellable) {
            close()
            withTimeoutOrNull(limit) { worker.join() }
            worker.cancel()
        }
    }
}
