// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import kotlin.time.Clock

/**
 * Builds the schedule's days before anyone needs them (#392). The first tune after launch and the first flip after a
 * day boundary used to wait for a build of up to 26 s on the slowest reference device. Now the days at the current
 * moment are built in the background whenever the lineup is loaded or refreshed (a new revision), and the day after
 * is built within an hour of a channel's day ending ([BuiltInScheduleSource.warm]).
 *
 * The builds run on [dispatcher], which the app gives a low-priority thread, so they yield to a tune that is starting.
 * A tune that needs a day already being built joins that build rather than starting its own (single flight), so at
 * worst it waits out the rest of it at low priority. Nothing here reports a failure: a build that fails is not kept,
 * and the caller that needs the day builds it again.
 */
class ScheduleWarmer(
    private val source: BuiltInScheduleSource,
    private val service: LineupService,
    private val library: LibraryRepository,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
) {
    private var started: Job? = null

    /** Starts following the active account in [scope]. A second call does nothing: one-shot, for the app's scope. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (started != null) return
        started =
            scope.launch {
                library.activeAccount().distinctUntilChanged().collectLatest { account ->
                    if (account != null) follow(account)
                }
            }
    }

    private suspend fun follow(account: LibraryAccount) {
        // `loaded` reads the stored lineup if nothing has yet, so a start with a saved lineup warms before any refresh.
        service.loaded(account)
        service
            .lineup(account)
            .map { it?.revision }
            .distinctUntilChanged()
            .collectLatest { revision -> if (revision != null) keepWarm() }
    }

    @Suppress("TooGenericExceptionCaught") // A failed warm is only a missed head start: see the class comment.
    private suspend fun keepWarm() {
        while (true) {
            val wake =
                try {
                    source.warm(clock.now(), dispatcher)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            // Nothing to warm yet (no libraries chosen, nothing scheduled) or a failed warm: look again later.
            val wait = wake?.let { (it - clock.now()).inWholeMilliseconds } ?: IDLE_MS
            delay(wait.coerceAtLeast(MIN_WAIT_MS))
        }
    }

    private companion object {
        /** Never look again sooner than this, so a wake time already past cannot spin the loop. */
        const val MIN_WAIT_MS = 1_000L

        /** How long to wait before looking again when there was nothing to warm. */
        const val IDLE_MS = 5 * 60 * 1_000L
    }
}
