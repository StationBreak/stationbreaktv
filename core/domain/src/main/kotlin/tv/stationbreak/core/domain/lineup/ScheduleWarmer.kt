// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import kotlin.time.Clock

/**
 * Builds the schedule's days before anyone needs them (#392). The first tune after launch and the first flip after a
 * day boundary used to wait for a build of up to 26 s on the slowest reference device. Now the days at the current
 * moment are built in the background whenever the lineup is loaded or refreshed (a new revision), and the day after
 * is built within an hour of a channel's day ending ([BuiltInScheduleSource.warm]).
 *
 * The builds are the source's own, at the normal priority: a tune that needs a day already being built joins that
 * build (single flight) and must not wait on a slower one than it would have run itself. So the warmer decides only
 * when to start. It does not hold the hour-ahead build back while a tune is starting: the domain has no signal for
 * that, the build starts an hour before it is needed, and a tune that meets it only waits out what is left of it.
 * Nothing here reports a failure: a build that fails is not kept, and the caller that needs the day builds it again.
 *
 * `delay` stops while the TV is in standby, so no wait is longer than [IDLE_MS], and [nudge] (the app coming to the
 * foreground) looks again at once. Both also pick up a change of the chosen libraries, which no revision announces.
 */
class ScheduleWarmer(
    private val source: BuiltInScheduleSource,
    private val service: LineupService,
    private val library: LibraryRepository,
    private val clock: Clock,
) {
    private var started: Job? = null
    private val nudges = Channel<Unit>(Channel.CONFLATED)

    /** Looks again now, if the warmer is waiting: the app has come to the foreground, perhaps from standby. */
    fun nudge() {
        nudges.trySend(Unit)
    }

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
                    source.warm(clock.now())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            // A nudge that came while a warm ran has nothing left to ask for; after a failed one it is the retry.
            if (wake != null) while (nudges.tryReceive().isSuccess) Unit
            // Nothing to warm yet (no libraries chosen, nothing scheduled) or a failed warm: look again later.
            val wait = wake?.let { (it - clock.now()).inWholeMilliseconds } ?: IDLE_MS
            withTimeoutOrNull(wait.coerceIn(MIN_WAIT_MS, IDLE_MS)) { nudges.receive() }
        }
    }

    private companion object {
        /** Never look again sooner than this, so a wake time already past cannot spin the loop. */
        const val MIN_WAIT_MS = 1_000L

        /** The longest wait before looking again, and the wait when there was nothing to warm. */
        const val IDLE_MS = 5 * 60 * 1_000L
    }
}
