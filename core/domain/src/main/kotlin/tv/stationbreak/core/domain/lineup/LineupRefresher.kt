// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.model.ItemId
import kotlin.time.Instant

/**
 * Keeps the active account's lineup up to date (spec 12) without wasted work. The lineup is **behind** when it is
 * absent, not yet finalised, planned from an older catalog than this app's, planned from a different set of libraries
 * than the user has chosen now, or planned from an older content generation than the library holds now
 * ([LibraryRepository.contentGeneration], durable, so a change that landed before a process died still counts).
 * [LineupService.refresh] runs
 * - at start, when the lineup is behind;
 * - when the last successful sync time advances (the items are in), if the lineup is behind. A failed or running sync
 *   moves no time;
 * - when [LibraryRepository.collectionPassCompleted] shows a pass (the collections are in), if the lineup is behind or
 *   has taken in no pass yet.
 *
 * So a restart with nothing changed refreshes nothing, whatever the launch sync did: a sync or a collection pass that
 * wrote nothing leaves the generation where it was, and the pass signal's replay of the latest pass is compared with
 * the pass stored with the lineup ([RefreshMeta.collectionPassActedOn]). A pass that is new to the lineup but changes
 * nothing is recorded without planning ([LineupService.notePass]), so the next start does not find it new either.
 * Every refresh stores the generation and the pass it read before it, so a start refresh and the pass signal that
 * follows it are one refresh.
 *
 * A refresh that throws is reported ([LineupEvent.RefreshFailed]) and stores nothing, so the lineup stays behind and
 * the refresh is tried again by the next trigger of any kind, and at the next start, for as long as the generation (or
 * the other reason) still differs. There is no timer of its own: with no further trigger it waits for the next one.
 *
 * A first lineup that cannot get its collections is not left empty: once the library reports that collection passes
 * keep ending without completing ([LibraryRepository.failedCollectionPasses], two or more), the next refresh plans
 * without them (see [LineupService.refresh]), for an unfinalised lineup and for a finalised one waiting on a newer
 * catalog. Either is behind, so any trigger, or the next start, makes that refresh.
 *
 * While a quiet sync run is going ([start]'s `syncing`, true from the run's start to its end) every trigger is held:
 * one run moves the last success, completes a collection pass and may write again after both (the studios re-read), so
 * acting on each would plan the lineup two or three times for one run, and still miss its last writes (#434). When the
 * run ends, one check asks what a success and a pass would have asked together: refresh if the lineup is behind or has
 * taken in no pass yet, otherwise record a new pass. A start check that finds the run already going waits for it too.
 * A run that never ends (the process dies) leaves nothing undone: the generation is durable, so the next start sees it.
 *
 * Refreshes run one at a time: one collector handles every trigger in turn. A refresh that throws never stops later
 * ones or reaches the app scope. A trigger source that itself fails is reported the same way and listened to again
 * after a wait that starts at one second and doubles up to five minutes. Nothing runs while nobody is signed in or no
 * libraries are chosen ([libraries] gives null or none).
 *
 * The account is [LibraryRepository.activeAccount], the one the sync uses; [libraries] reads the user's chosen
 * libraries each time it is asked, so a changed choice is seen by the next trigger. Nothing in the settings announces
 * a changed choice, so it is not a trigger of its own: it waits for the next sync, pass or start.
 */
class LineupRefresher(
    private val service: LineupService,
    private val library: LibraryRepository,
    private val libraries: suspend () -> Set<ItemId>?,
    private val initialBackoffMs: Long = INITIAL_BACKOFF_MS,
    private val report: (LineupEvent) -> Unit,
) {
    private var started: Job? = null
    private var syncing: Flow<Boolean> = flowOf(false)

    /**
     * Starts listening in [scope]. A second call does nothing: [started] is never cleared, so this is one-shot, meant
     * for an app-scoped singleton. [syncing] is true while a quiet sync run is writing the library (see the class
     * comment); it must give its current value first, as a `StateFlow` does.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever a refresh throws is reported, never allowed to reach the scope.
    @Synchronized
    fun start(
        scope: CoroutineScope,
        syncing: Flow<Boolean> = flowOf(false),
    ) {
        if (started != null) return
        this.syncing = syncing
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
        // A run that ends: the first value is where things stand, so only a change to "not running" is news.
        val runEnds =
            syncing
                .distinctUntilChanged()
                .drop(1)
                .filter { !it }
                .map { Trigger.SyncEnded }
        val backoff = Backoff(initialBackoffMs)
        merge(start, passes, successes, runEnds)
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
            }.collect { trigger -> act(account, trigger) }
    }

    @Suppress("TooGenericExceptionCaught") // An unreadable start state just means: refresh.
    private suspend fun isStale(account: LibraryAccount): Boolean =
        try {
            // With no libraries chosen yet, only the lineup itself can be behind; act() will do nothing about it.
            behind(account, service.loaded(account), libraries()?.takeIf { it.isNotEmpty() })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }

    // See the class doc. [chosen] null means the choice is not known: then it and the generation are not compared.
    private suspend fun behind(
        account: LibraryAccount,
        held: Revisioned?,
        chosen: Set<ItemId>?,
    ): Boolean {
        if (held == null || needsPlan(held.lineup)) return true
        return chosen != null &&
            (
                held.meta.plannedLibraries != chosen.map { it.value }.sorted() ||
                    held.meta.plannedGeneration != library.contentGeneration(account)
            )
    }

    // Not yet finalised, planned from an older catalog than this app's, or holding an unedited starter that predates
    // `alignToGrid: false` (the planner sets it, but only when a plan runs, and a steady library would never run one).
    private fun needsPlan(lineup: Lineup): Boolean {
        val catalog = service.catalog.version
        return !lineup.finalised || lineup.catalogVersion < catalog || lineup.channels.any(::needsBackToBack)
    }

    private fun needsBackToBack(channel: LineupChannel): Boolean {
        val source = channel.source as? ChannelSource.Starter ?: return false
        return !source.edited && source.rules.timing.alignToGrid == null
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // Each early return is a reason there is nothing to do.
    private suspend fun act(
        account: LibraryAccount,
        trigger: Trigger,
    ) {
        try {
            // Held until the run ends: its end is a trigger of its own, which checks everything this one would.
            if (syncing.first()) return
            val chosen = libraries()?.takeIf { it.isNotEmpty() } ?: return
            // The choice is the active account's. The account flow may not have delivered a change yet (nothing is
            // cancelled then), so ask the port again, directly, just before refreshing: if the account is no longer
            // this one, the libraries read may be the new account's, and nothing is refreshed or recorded.
            currentCoroutineContext().ensureActive()
            if (library.activeAccount().first() != account) return
            val held = service.loaded(account)
            val pass = library.collectionPassCompleted(account).first()
            if (!worthRefreshing(trigger, account, held, chosen, pass)) {
                // Nothing to plan, but the lineup does hold this pass's collections: say so, or the next start would
                // find the pass new.
                val pastPass = trigger == Trigger.Pass || trigger == Trigger.SyncEnded
                if (pastPass && pass != null) service.notePass(account, pass)
                return
            }
            service.refresh(account, chosen)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            report(LineupEvent.RefreshFailed)
        }
    }

    // Start is asked again: another trigger may have caught the lineup up since the start check ran.
    private suspend fun worthRefreshing(
        trigger: Trigger,
        account: LibraryAccount,
        held: Revisioned?,
        chosen: Set<ItemId>,
        pass: Instant?,
    ): Boolean =
        when (trigger) {
            Trigger.Start, Trigger.Success -> {
                behind(account, held, chosen)
            }

            Trigger.Pass -> {
                // The lineup has taken in no pass yet: its collections are not in, whatever else is so.
                pass != null && (held?.meta?.collectionPassActedOn == null || behind(account, held, chosen))
            }

            Trigger.SyncEnded -> {
                // What the run's success and its pass would each have asked.
                behind(account, held, chosen) || (pass != null && held?.meta?.collectionPassActedOn == null)
            }
        }

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

        data object SyncEnded : Trigger
    }
}
