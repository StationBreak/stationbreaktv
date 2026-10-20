// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.model.ItemId
import kotlin.time.Instant

/**
 * Keeps the active account's lineup up to date (spec 12): [LineupService.refresh] runs
 * - at start, when the lineup is absent, not yet finalised, or behind the starter catalog;
 * - when [LibraryRepository.collectionPassCompleted] shows a pass it has not acted on (the collections are in);
 * - when the last successful sync time advances (the items are in). A failed or running sync moves no time.
 *
 * Refreshes run one at a time: one collector handles every trigger in turn. A refresh that throws is reported
 * ([LineupEvent.RefreshFailed]) and the refresher keeps listening, so one bad plan never stops later ones or reaches
 * the app scope. Nothing runs while nobody is signed in or no libraries are chosen ([libraries] gives null or none).
 *
 * [activeAccount] is the account the saved library holds (the one the sync uses); [libraries] reads the user's chosen
 * libraries each time it is asked, so a changed choice is seen by the next refresh.
 */
class LineupRefresher(
    private val service: LineupService,
    private val library: LibraryRepository,
    private val activeAccount: Flow<LibraryAccount?>,
    private val libraries: suspend () -> Set<ItemId>?,
    private val report: (LineupEvent) -> Unit,
) {
    // The pass last acted on, per account, kept for the process: Room re-emits a value it already gave.
    private val actedOn = HashMap<LibraryAccount, Instant>()

    /** Starts listening in [scope]. Call it once. */
    @Suppress("TooGenericExceptionCaught") // Whatever a refresh throws is reported, never allowed to reach the scope.
    fun start(scope: CoroutineScope) {
        scope.launch {
            try {
                activeAccount
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
                .map { Trigger.Pass(it) }
        // The first value is where the sync stands, not news: the start check and the pass signal cover it.
        val successes =
            library
                .status(account)
                .map { it.lastSuccess }
                .distinctUntilChanged()
                .drop(1)
                .filterNotNull()
                .map { Trigger.Success }
        merge(start, passes, successes)
            .catch { if (it is CancellationException) throw it else report(LineupEvent.RefreshFailed) }
            .collect { trigger -> act(account, trigger) }
    }

    @Suppress("TooGenericExceptionCaught") // An unreadable start state just means: refresh.
    private suspend fun isStale(account: LibraryAccount): Boolean =
        try {
            val held = service.loaded(account)?.lineup
            held == null || !held.finalised || held.catalogVersion < service.catalog.version
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }

    @Suppress("TooGenericExceptionCaught") // Whatever a refresh throws is reported; the next trigger retries.
    private suspend fun act(
        account: LibraryAccount,
        trigger: Trigger,
    ) {
        if (trigger is Trigger.Pass) {
            if (actedOn[account] == trigger.startedAt) return
            actedOn[account] = trigger.startedAt
        }
        val chosen = libraries()?.takeIf { it.isNotEmpty() } ?: return
        try {
            service.refresh(account, chosen)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            report(LineupEvent.RefreshFailed)
        }
    }

    private sealed interface Trigger {
        data object Start : Trigger

        data object Success : Trigger

        data class Pass(
            val startedAt: Instant,
        ) : Trigger
    }
}
