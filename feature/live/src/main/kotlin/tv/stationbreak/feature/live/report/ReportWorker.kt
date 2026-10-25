// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.report

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.PlaybackReport
import tv.stationbreak.core.model.Session
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
    ) : ReportEvent

    data class Stall(
        val at: Instant,
    ) : ReportEvent

    data class Resumed(
        val at: Instant,
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
 * state, and talks to the server under a timeout. Touched from one coroutine only. [postTick] queues a tick for a
 * visit back onto the same event queue.
 */
internal class ReportWorker(
    private val scope: CoroutineScope,
    private val server: MediaServer,
    private val session: Session,
    private val config: ReportingConfig,
    private val log: (String) -> Unit,
    private val postTick: (visitId: Long) -> Unit,
) {
    private class OpenVisit(
        val id: Long,
        val sittingId: Long,
        val key: AiringKey,
        var target: ReportTarget,
        val timer: Job,
    ) {
        var userDataTries = 0
        var userRead = false
        var resume: Duration = Duration.ZERO
        var userPlayed = false
        var started = false
    }

    private val ledger = WatchLedger(config)
    private var visit: OpenVisit? = null
    private var visitCounter = 0L
    private var halted = false

    /** Returns false once the worker should end. */
    suspend fun handle(event: ReportEvent): Boolean {
        when (event) {
            is ReportEvent.Sitting -> {
                endVisit(event.at)
                ledger.sittingStarted(event.id, event.at)
            }

            is ReportEvent.Visit -> {
                onVisit(event)
            }

            is ReportEvent.Stall -> {
                ledger.stall(event.at)
            }

            is ReportEvent.Resumed -> {
                ledger.resumed(event.at)
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
        return event !is ReportEvent.Close
    }

    private suspend fun onVisit(event: ReportEvent.Visit) {
        val open = visit
        if (open != null && open.key == event.key && open.sittingId == event.sittingId) {
            open.target = event.target
        } else {
            endVisit(event.at)
            ledger.enter(event.key, event.at)
            val dwell = ledger.dwell(event.sittingId, event.at)
            val id = ++visitCounter
            val timer =
                scope.launch {
                    delay(dwell)
                    while (true) {
                        postTick(id)
                        delay(config.progressEvery)
                    }
                }
            visit = OpenVisit(id, event.sittingId, event.key, event.target, timer)
        }
    }

    private suspend fun onTick(event: ReportEvent.Tick) {
        val v = visit?.takeIf { it.id == event.visitId }
        if (v != null && !halted) {
            markIfDue(v, event.at)
            if (!halted && (v.userRead || readUserData(v))) checkIn(v)
        }
    }

    /** One read of the item's user data, within the per-visit budget. True when [v] now knows its resume position. */
    private suspend fun readUserData(v: OpenVisit): Boolean {
        val state =
            if (v.userDataTries < config.userDataAttempts) {
                v.userDataTries++
                call { server.userData(session, v.key.itemId) }
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
        val position = safePosition(v)
        if (v.started) {
            if (call { server.reportProgress(session, report(v, position)) } != null) {
                log("report progress pos=${position.inWholeMilliseconds}")
            }
        } else if (call { server.reportStart(session, report(v, position)) } != null) {
            v.started = true
            log("report start pos=${position.inWholeMilliseconds}")
        }
    }

    private suspend fun endVisit(at: Instant) {
        val v = visit
        visit = null
        if (v != null) {
            v.timer.cancel()
            ledger.leave(at)
            if (!halted) markIfDue(v, at)
            if (!halted && v.started) {
                val position = safePosition(v)
                if (call { server.reportStopped(session, report(v, position), failed = true) } != null) {
                    log("report stop pos=${position.inWholeMilliseconds}")
                }
            }
        }
    }

    private suspend fun markIfDue(
        v: OpenVisit,
        at: Instant,
    ) {
        if (!v.userPlayed && ledger.markDue(v.key, v.target.runtime, at)) {
            val done = call { server.markPlayed(session, v.key.itemId) } != null
            ledger.markResult(v.key, done)
            if (done) log("report played")
        }
    }

    /** The item's resume position, or 0 once it is played or the app has marked it played (R7). */
    private fun safePosition(v: OpenVisit): Duration {
        val played = v.userPlayed || ledger.isMarked(v.key)
        return if (played) Duration.ZERO else v.resume
    }

    private fun report(
        v: OpenVisit,
        position: Duration,
    ) = PlaybackReport(
        itemId = v.key.itemId,
        sourceId = v.target.sourceId,
        playSessionId = v.target.playSessionId,
        position = position,
        playMethod = v.target.playMethod,
    )

    /**
     * Runs one server call under the timeout. Returns the value, or null after logging why not; a 401 halts the run.
     * Never throws except for cancellation.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Reporting is best-effort; cancellation still passes.
    private suspend fun <T> call(block: suspend () -> ServerResult<T>): T? {
        val result =
            try {
                withTimeoutOrNull(config.callTimeout) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("report error kind=exception")
                return null
            }
        return when (result) {
            is ServerResult.Success -> result.value
            is ServerResult.Failure -> failed(result.error)
            null -> null.also { log("report error kind=timeout") }
        }
    }

    private fun failed(error: MediaServerError): Nothing? {
        if (error is MediaServerError.HttpUnauthorized || error is MediaServerError.SessionExpired) {
            halted = true
            visit?.timer?.cancel()
        }
        log("report error kind=${error::class.simpleName.orEmpty().lowercase()}")
        return null
    }
}
