// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.ProEntitlement
import tv.stationbreak.core.domain.store.StoreEntitlement
import tv.stationbreak.core.engine.lineup.isViewerCustom
import tv.stationbreak.core.model.ItemId

/**
 * What a refund does to a lineup (ADR-0023, #99): the starters return to the planner's defaults (the lineup is reset
 * the way the viewer's own reset does it, which keeps every custom channel), and the customs beyond the free cap are
 * locked by [lockedCustoms], which stores nothing. Nothing is deleted. A reset is made only when a starter still holds
 * a viewer's mark, so it is safe to ask again at every start: a free viewer cannot make such a mark. It is asked only
 * on a verified answer of the store, never on an unanswered one, so a TV that starts offline keeps its lineup.
 * [libraries] is the viewer's playable libraries, as for a reset; none known means try again later.
 */
class ProRevocation(
    private val service: LineupService,
    private val libraries: suspend () -> Set<ItemId>?,
) {
    /** True when the starters were reset. */
    @Suppress("TooGenericExceptionCaught") // Any failure of the plan or the save: reported, and tried again later.
    suspend fun onRevoked(account: LibraryAccount): Boolean {
        val channels =
            service
                .loaded(account)
                ?.lineup
                ?.channels
                .orEmpty()
        if (channels.none { !it.isViewerCustom() && it.chosenByViewer }) return false
        return try {
            val chosen = libraries()?.takeIf { it.isNotEmpty() }
            chosen != null && service.reset(account, chosen) is Saved.Ok
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            service.reportEvent(LineupEvent.ResetFailed)
            false
        }
    }

    /** Resets the active account's starters whenever the store verifies Pro is not owned and the build is locked. */
    fun watch(
        scope: CoroutineScope,
        pro: ProEntitlement,
        store: StoreEntitlement,
        library: LibraryRepository,
    ): Job =
        scope.launch {
            combine(store.verified, pro.pro, library.activeAccount()) { verified, isPro, account ->
                account.takeIf { verified == false && !isPro }
            }.collect { account -> if (account != null) onRevoked(account) }
        }
}
