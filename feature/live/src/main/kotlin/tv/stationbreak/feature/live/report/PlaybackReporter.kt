// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
 * server call is abandoned after [ReportingConfig.callTimeout].
 *
 * Policy (the numbers are [config]'s):
 * - A visit is one airing ([AiringKey], R3). Watched time lives in a [WatchLedger] for the life of the reporter.
 * - On a fresh sitting the item is reported after `startAfter`; on a sitting older than that, at once (R4). Reporting
 *   starts with a user-data read (at most `userDataAttempts` per visit, one per tick), then Playing at the safe
 *   position: the item's resume position, or 0 when it is played or the app has marked it played (R7). Then a Progress
 *   at that same position every `progressEvery`, and Stopped(failed) at the same position on leave. The real playing
 *   position is never sent.
 * - Once watched time reaches `playedThreshold` of the runtime, the airing is marked played, once, whatever the
 *   user-data read did (R5, R6). A 401 stops all reporting for the run (R9).
 *
 * A retry of the same airing in the same sitting while the visit is open continues it: one Playing, with the newest
 * target used from then on. Stall notes beyond the tune controller's cap of 20 per play over-count watched time.
 *
 * Log lines hold only labels and numbers: `report start|progress|stop|played|error kind=<label>`.
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

    init {
        val worker =
            ReportWorker(scope, server, session, config, log) {
                events.trySend(ReportEvent.Tick(it, clock.now()))
            }
        scope.launch {
            for (event in events) {
                if (!worker.handle(event)) break
            }
        }
    }

    /** A new sitting began (a tune or a flip lands). Older than `startAfter`, it reports each item at once. */
    fun sittingStarted(sittingId: Long) {
        events.trySend(ReportEvent.Sitting(sittingId, clock.now()))
    }

    /** The viewer is now on [airing] playing [target]. The same airing again in the same sitting continues it. */
    fun visit(
        sittingId: Long,
        airing: AiringKey,
        target: ReportTarget,
    ) {
        events.trySend(ReportEvent.Visit(sittingId, airing, target, clock.now()))
    }

    /** The picture stalled; the time until [resumed] (or [leave]) is not watched time. */
    fun stall() {
        events.trySend(ReportEvent.Stall(clock.now()))
    }

    fun resumed() {
        events.trySend(ReportEvent.Resumed(clock.now()))
    }

    /** The viewer left the item (boundary, flip, exit, failure). Sends Stopped if it was reported as playing. */
    fun leave() {
        events.trySend(ReportEvent.Leave(clock.now()))
    }

    /** The run ended: leaves any open item, sends what is queued, then the reporter ignores everything. */
    fun close() {
        events.trySend(ReportEvent.Close(clock.now()))
        events.close()
    }
}
