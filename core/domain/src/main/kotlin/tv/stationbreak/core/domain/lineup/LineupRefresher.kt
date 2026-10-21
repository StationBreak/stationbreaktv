// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.ItemId
import kotlin.time.Instant

/**
 * Keeps the active account's lineup up to date (spec 12) without wasted work: [LineupService.refresh] runs
 * - at start, when the lineup is absent, not yet finalised, or behind the starter catalog;
 * - when [LibraryRepository.collectionPassCompleted] shows a pass the lineup has not taken in (the collections are
 *   in), and the library could have changed since the last refresh ([LibraryRepository.contentChanges]), or the lineup
 *   is unfinalised or behind the catalog, or it has taken in no pass yet;
 * - when the last successful sync time advances (the items are in), and the library could have changed since the last
 *   refresh or the lineup is unfinalised or behind the catalog. A failed or running sync moves no time.
 *
 * So a restart with nothing changed in the library refreshes nothing: the pass last acted on is stored with the lineup
 * ([Lineup.collectionPassActedOn]), so the pass the signal replays is known, and a launch sync that wrote nothing
 * (it still records a new pass and may move the success time) is not news. Every refresh records the latest pass read
 * before it, so a start refresh and the pass signal that follows it are one refresh. A refresh that throws records
 * nothing, so the next start tries that pass again. After a successful sync that finds the lineup unfinalised for the
 * second time with no pass in, the lineup is planned once without waiting for the collections (see
 * [LineupService.refresh]).
 *
 * Refreshes run one at a time: one collector handles every trigger in turn. A refresh that throws is reported
 * ([LineupEvent.RefreshFailed]) and the refresher keeps listening, so one bad plan never stops later ones or reaches
 * the app scope. A trigger source that itself fails is reported the same way and listened to again after a wait that
 * starts at one second and doubles up to five minutes. Nothing runs while nobody is signed in or no libraries are
 * chosen ([libraries] gives null or none).
 *
 * The account is [LibraryRepository.activeAccount], the one the sync uses; [libraries] reads the user's chosen
 * libraries each time it is asked, so a changed choice is seen by the next refresh.
 */
class LineupRefresher(
    private val service: LineupService,
    private val library: LibraryRepository,
    private val libraries: suspend () -> Set<ItemId>?,
    private val initialBackoffMs: Long = INITIAL_BACKOFF_MS,
    private val report: (LineupEvent) -> Unit,
) {
    private var started: Job? = null

    /**
     * Starts listening in [scope]. A second call does nothing: [started] is never cleared, so this is one-shot, meant
     * for an app-scoped singleton.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever a refresh throws is reported, never allowed to reach the scope.
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (started != null) return
        started =
            scope.launch {
                try {
                    library
                        .activeAccount()
                        .distinctUntilChanged()
                        .collectLatest { account -> if (account != null) follow(account) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The saved library itself failed to answer: nothing to listen to now. The next app start retries.
                    report(LineupEvent.RefreshFailed)
                }
            }
    }

    private suspend fun follow(account: LibraryAccount) {
        val start =
            flow {
                if (isStale(account)) emit(Trigger.Start)
            }
        val passes =
            library
                .collectionPassCompleted(account)
                .filterNotNull()
                .distinctUntilChanged()
                .map { Trigger.Pass }
        // The first value is where the sync stands, not news: the start check and the pass signal cover it.
        val successes =
            library
                .status(account)
                .map { it.lastSuccess }
                .distinctUntilChanged()
                .drop(1)
                .filterNotNull()
                .map { Trigger.Success }
        val backoff = Backoff(initialBackoffMs)
        // What the library's change count stood at when the last refresh began, kept for the process: a count that is
        // still this is a library the lineup already follows. It starts at the count now, which is the state the start
        // check judges.
        val seen = Seen(library.contentChanges(account).first())
        merge(start, passes, successes)
            // A trigger from a real source (a pass or a sync) got through: it is healthy again, so the next failure
            // waits the initial time. Start is not one: every resubscription emits it while the lineup is stale.
            .onEach { if (it !is Trigger.Start) backoff.reset() }
            .retryWhen { cause, _ ->
                // A trigger source that fails is reported and listened to again after a wait that doubles to a cap.
                if (cause is CancellationException) {
                    false
                } else {
                    report(LineupEvent.RefreshFailed)
                    delay(backoff.next())
                    true
                }
            }.collect { trigger -> act(account, trigger, seen) }
    }

    @Suppress("TooGenericExceptionCaught") // An unreadable start state just means: refresh.
    private suspend fun isStale(account: LibraryAccount): Boolean =
        try {
            behind(service.loaded(account)?.lineup)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }

    // Absent, not yet finalised, or planned from an older catalog than this app's.
    private fun behind(held: Lineup?): Boolean {
        val current = service.catalog.version
        return held == null || !held.finalised || held.catalogVersion < current
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // Each early return is a reason there is nothing to do.
    private suspend fun act(
        account: LibraryAccount,
        trigger: Trigger,
        seen: Seen,
    ) {
        try {
            val chosen = libraries()?.takeIf { it.isNotEmpty() } ?: return
            // The choice is the active account's. The account flow may not have delivered a change yet (nothing is
            // cancelled then), so ask the port again, directly, just before refreshing: if the account is no longer
            // this one, the libraries read may be the new account's, and nothing is refreshed or recorded.
            currentCoroutineContext().ensureActive()
            if (library.activeAccount().first() != account) return
            // Both read before the refresh, so what lands during it counts as news for the next trigger, and the
            // collections of the pass recorded are sure to be in the planning read.
            val changes = library.contentChanges(account).first()
            val pass = library.collectionPassCompleted(account).first()
            val held = service.loaded(account)?.lineup
            if (!worthRefreshing(trigger, held, changes != seen.changes, pass, seen)) {
                // Nothing to plan, but the lineup does hold this pass's collections: say so, or the next start would
                // find the pass new.
                if (trigger == Trigger.Pass && pass != null) service.notePass(account, pass)
                return
            }
            service.refresh(account, chosen, pass, afterSync = trigger is Trigger.Success)
            // Only a refresh that ran to its end: one that throws leaves the lineup as it was, so the next trigger or
            // start tries again.
            seen.changes = changes
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            report(LineupEvent.RefreshFailed)
        }
    }

    // A sync or a pass that wrote nothing is not news once the lineup is final and current; an unfinalised or older
    // lineup always takes a chance to catch up. Start is asked again: another trigger may have caught the lineup up
    // since the start check ran.
    private fun worthRefreshing(
        trigger: Trigger,
        held: Lineup?,
        changed: Boolean,
        pass: Instant?,
        seen: Seen,
    ): Boolean =
        when (trigger) {
            Trigger.Start -> {
                behind(held)
            }

            Trigger.Success -> {
                changed || behind(held)
            }

            Trigger.Pass -> {
                val acted = held?.collectionPassActedOn
                val newPass = pass != null && pass.toEpochMilliseconds() != acted
                // The first pass signal of a run replays the latest pass: one the last run did not take in is news.
                val replayed = seen.passes++ == 0
                changed || (newPass && (behind(held) || acted == null || replayed))
            }
        }

    /** What the change count stood at when the last refresh began, and how many pass signals this run has handled. */
    private class Seen(
        var changes: Long,
        var passes: Int = 0,
    )

    internal companion object {
        /** The wait after the first trigger failure; each further one doubles it, up to [MAX_BACKOFF_MS]. */
        const val INITIAL_BACKOFF_MS = 1_000L

        /** The longest wait between listening again: five minutes. */
        const val MAX_BACKOFF_MS = 300_000L

        /** The wait before listening again after failure number [attempt] (0 is the first), doubling to the cap. */
        fun backoffMs(
            attempt: Long,
            initialMs: Long,
        ): Long {
            // Past this many doublings the cap is reached; stopping there keeps the shift from overflowing.
            val doublings = attempt.coerceIn(0L, MAX_DOUBLINGS).toInt()
            return minOf(MAX_BACKOFF_MS, initialMs shl doublings)
        }

        private const val MAX_DOUBLINGS = 20L
    }

    /** The waits between failures of one trigger source: the initial wait, doubling to the cap; [reset] starts over. */
    internal class Backoff(
        private val initialMs: Long,
    ) {
        private var failures = 0L

        /** The wait after the next failure. */
        fun next(): Long = backoffMs(failures++, initialMs)

        /** A trigger got through: the next failure waits the initial time again. */
        fun reset() {
            failures = 0L
        }
    }

    private sealed interface Trigger {
        data object Start : Trigger

        data object Success : Trigger

        data object Pass : Trigger
    }
}
