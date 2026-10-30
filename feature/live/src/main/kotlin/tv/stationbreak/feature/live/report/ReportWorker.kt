// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.Session
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** What the [PlaybackReporter]'s methods queue for its worker. Each carries the time it happened. */
internal sealed interface ReportEvent {
    data class Sitting(
        val id: Long,
        val at: Instant,
    ) : ReportEvent

    data class Visit(
        val sittingId: Long,
        val key: AiringKey,
        val target: ReportTarget,
        val at: Instant,
        val fromMs: Long,
    ) : ReportEvent

    data class Stall(
        val at: Instant,
        val positionMs: Long?,
    ) : ReportEvent

    data class Resumed(
        val at: Instant,
        val fromMs: Long?,
    ) : ReportEvent

    data class Paused(
        val at: Instant,
        val positionMs: Long?,
    ) : ReportEvent

    data class Unpaused(
        val at: Instant,
    ) : ReportEvent

    data class Seeking(
        val at: Instant,
    ) : ReportEvent

    data class Seeked(
        val at: Instant,
        val toMs: Long,
    ) : ReportEvent

    data class Leave(
        val at: Instant,
    ) : ReportEvent

    data class Tick(
        val visitId: Long,
        val at: Instant,
    ) : ReportEvent

    data class Close(
        val at: Instant,
    ) : ReportEvent
}

/**
 * The I/O half of the reporter: handles one [ReportEvent] at a time (so calls are strictly ordered), keeps the visit
 * state, and talks to the server through [calls]. Touched from one coroutine only. [post] queues a timer's event back
 * onto the same event queue.
 *
 * A visit ends on a leave, on a visit to another airing, on a new sitting and on close; a visit to the airing that is
 * already open (same sitting) just takes the newest target. A Start that timed out may still have reached the server;
 * the next check-in sends Start again, and if the viewer leaves first no Stopped is sent, so the server ends that
 * session itself at the safe position (ADR-0018).
 */
@Suppress("LongParameterList") // The reporter's collaborators, each used.
internal class ReportWorker(
    private val scope: CoroutineScope,
    private val server: MediaServer,
    private val session: Session,
    private val clock: Clock,
    private val config: ReportingConfig,
    private val calls: ReportCalls,
    private val log: (String) -> Unit,
    private val post: (ReportEvent) -> Unit,
) {
    private class OpenVisit(
        val id: Long,
        val sittingId: Long,
        val key: AiringKey,
        var target: ReportTarget,
        val timer: Job?,
    ) {
        var userDataTries = 0
        var userRead = false
        var resume: Duration = Duration.ZERO
        var userPlayed = false

        /** True once a Playing was ever sent in this visit, so a leave sends Stopped. */
        var started = false

        /** True after a silence ended in a Play: the next check-in is a fresh Playing, not a Progress. */
        var needsFreshStart = false

        /** When the viewer paused, while paused. */
        var pausedAt: Instant? = null

        /** True once the pause has outlasted `pauseReportMax` and check-ins have stopped. */
        var silent = false

        fun report(
            position: Duration,
            paused: Boolean = false,
        ) = PlaybackReport(
            itemId = key.itemId,
            sourceId = target.sourceId,
            playSessionId = target.playSessionId,
            position = position,
            playMethod = target.playMethod,
            isPaused = paused,
            canSeek = false,
        )

        /** The item's resume position, or 0 once it is played or the app has marked it played (R7). */
        fun safePosition(marked: Boolean): Duration = if (userPlayed || marked) Duration.ZERO else resume
    }

    private val ledger = WatchLedger(config)
    private var visit: OpenVisit? = null
    private var visitCounter = 0L

    /** Handles [event], never letting a fault in it end the worker. Returns false once the worker should end. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // One bad event must not silence the run.
    suspend fun handle(event: ReportEvent): Boolean {
        try {
            dispatch(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("report error kind=exception")
        }
        return event !is ReportEvent.Close
    }

    private suspend fun dispatch(event: ReportEvent) {
        when (event) {
            is ReportEvent.Sitting -> {
                endVisit(event.at)
                ledger.sittingStarted(event.id, event.at)
            }

            is ReportEvent.Visit -> {
                onVisit(event)
            }

            is ReportEvent.Stall -> {
                ledger.stall(event.at, event.positionMs)
            }

            is ReportEvent.Resumed -> {
                ledger.resumed(event.at, event.fromMs)
            }

            is ReportEvent.Paused, is ReportEvent.Unpaused, is ReportEvent.Seeking, is ReportEvent.Seeked -> {
                onTransport(event)
            }

            is ReportEvent.Leave -> {
                endVisit(event.at)
            }

            is ReportEvent.Tick -> {
                onTick(event)
            }

            is ReportEvent.Close -> {
                endVisit(event.at)
            }
        }
    }

    private suspend fun onVisit(event: ReportEvent.Visit) {
        val open = visit
        if (open != null && open.key == event.key && open.sittingId == event.sittingId) {
            open.target = event.target
        } else {
            endVisit(event.at)
            ledger.enter(event.key, event.at, event.fromMs)
            val dwell = ledger.dwell(event.sittingId, event.at)
            val id = ++visitCounter
            val timer = if (calls.halted) null else scope.launch { tickLoop(id, dwell) }
            visit = OpenVisit(id, event.sittingId, event.key, event.target, timer)
        }
    }

    /** The viewer's pause and seek keys, for the open visit (none: nothing to do). */
    private fun onTransport(event: ReportEvent) {
        val v = visit ?: return
        when (event) {
            is ReportEvent.Paused -> {
                ledger.pause(event.at, event.positionMs)
                if (v.pausedAt == null) v.pausedAt = event.at
            }

            is ReportEvent.Unpaused -> {
                ledger.unpause(event.at)
                // After the silence the server has let the session go: a fresh Playing now, then the cadence.
                if (v.silent) {
                    v.needsFreshStart = true
                    post(ReportEvent.Tick(v.id, event.at))
                }
                v.pausedAt = null
                v.silent = false
            }

            is ReportEvent.Seeking -> {
                ledger.seeking(event.at)
            }

            is ReportEvent.Seeked -> {
                ledger.seeked(event.at, event.toMs)
            }

            else -> {}
        }
    }

    private suspend fun tickLoop(
        id: Long,
        dwell: Duration,
    ) {
        delay(dwell)
        while (true) {
            post(ReportEvent.Tick(id, clock.now()))
            delay(config.progressEvery)
        }
    }

    private suspend fun onTick(event: ReportEvent.Tick) {
        val v = visit?.takeIf { it.id == event.visitId }
        if (v != null && calls.halted) {
            v.timer?.cancel()
        } else if (v != null) {
            markIfDue(v, event.at)
            if (!calls.halted && !quiet(v, event.at)) checkIn(v)
        }
    }

    /**
     * True when this tick sends nothing because the viewer is paused: before the first Playing (no Playing while
     * paused), or once the pause has outlasted `pauseReportMax`, which also sets [OpenVisit.silent] and logs it once.
     */
    private fun quiet(
        v: OpenVisit,
        at: Instant,
    ): Boolean {
        val since = v.pausedAt
        val paused = if (since == null) Duration.ZERO else at - since
        val outlasted = v.started && !v.silent && paused >= config.pauseReportMax
        if (outlasted) {
            v.silent = true
            log("report silent afterMs=${paused.inWholeMilliseconds}")
        }
        return since != null && (!v.started || v.silent)
    }

    /** One read of the item's user data, within the per-visit budget. True when [v] now knows its resume position. */
    private suspend fun readUserData(v: OpenVisit): Boolean {
        val state =
            if (v.userDataTries < config.userDataAttempts) {
                v.userDataTries++
                calls.call { server.userData(session, v.key.itemId) }
            } else {
                null
            }
        if (state != null) {
            v.resume = state.position
            v.userPlayed = state.played
            v.userRead = true
        }
        return state != null
    }

    /** Playing on the first check-in that gets through, Progress after. Always at the safe position. */
    private suspend fun checkIn(v: OpenVisit) {
        if (!v.userRead && !readUserData(v)) return
        val position = v.safePosition(ledger.isMarked(v.key))
        if (v.started && !v.needsFreshStart) {
            val paused = v.pausedAt != null
            if (calls.call { server.reportProgress(session, v.report(position, paused)) } != null) {
                log("report progress ${if (paused) "paused=1 " else ""}pos=${position.inWholeMilliseconds}")
            }
        } else if (calls.call { server.reportStart(session, v.report(position)) } != null) {
            v.started = true
            v.needsFreshStart = false
            log("report start pos=${position.inWholeMilliseconds}")
        }
    }

    /**
     * The visit is over as of [at]: stop its timers, mark it played if
     * due, and send Stopped if it was reported as playing.
     */
    private suspend fun endVisit(at: Instant) {
        val v = visit
        if (v != null) {
            visit = null
            v.timer?.cancel()
            ledger.leave(at)
            if (!calls.halted) markIfDue(v, at)
            if (!calls.halted && v.started) {
                val position = v.safePosition(ledger.isMarked(v.key))
                val sent = calls.call { server.reportStopped(session, v.report(position), failed = true) }
                if (sent != null) log("report stop pos=${position.inWholeMilliseconds}")
            }
        }
    }

    private suspend fun markIfDue(
        v: OpenVisit,
        at: Instant,
    ) {
        if (!v.userPlayed && ledger.markDue(v.key, v.target.runtime, at)) {
            val done = calls.call { server.markPlayed(session, v.key.itemId) } != null
            ledger.markResult(v.key, done)
            if (done) log("report played")
        }
    }
}
