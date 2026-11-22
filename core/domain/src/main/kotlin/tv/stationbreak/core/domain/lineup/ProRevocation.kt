// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.ProEntitlement
import tv.stationbreak.core.model.ItemId

/**
 * What a refund does to a lineup (ADR-0023, #99): the starters return to the planner's defaults (the lineup is reset
 * the way the viewer's own reset does it, which keeps every custom channel), and the customs beyond the free cap are
 * locked by [lockedCustoms], which stores nothing. Nothing is deleted. A reset is made only when a starter still holds
 * a viewer's mark, so it is safe to ask again at every start: a free viewer cannot make such a mark. It is asked only
 * on a verified change from owned to not owned ([ProEntitlement.revoked]), never on an unanswered store or on a TV that
 * never held Pro, so a TV that starts offline keeps its lineup.
 * [libraries] is the viewer's playable libraries, as for a reset; none known means try again later.
 */
class ProRevocation(
    private val service: LineupService,
    private val libraries: suspend () -> Set<ItemId>?,
) {
    /**
     * What came of the reset for [account]. [stillRevoked] is asked in the lineup's lock just before the save (#1063):
     * a purchase that lands while the reset waits or plans means no reset is saved. Only a lineup that was read and may
     * be written is judged: one not read (a failed read, an account not resolved, none yet) is [Revocation.NotNow].
     */
    suspend fun onRevoked(
        account: LibraryAccount,
        stillRevoked: () -> Boolean,
    ): Revocation {
        val held = service.loaded(account)?.takeIf { it.writable }
        return when {
            held == null -> Revocation.NotNow
            held.lineup.channels.none { !it.isTheViewers() && it.chosenByViewer } -> Revocation.NothingToReset
            reset(account, stillRevoked) -> Revocation.Reset
            else -> Revocation.NotNow
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any failure of the plan or the save: reported, and tried again later.
    private suspend fun reset(
        account: LibraryAccount,
        stillRevoked: () -> Boolean,
    ): Boolean =
        try {
            val chosen = libraries()?.takeIf { it.isNotEmpty() }
            chosen != null && service.reset(account, chosen, stillRevoked) is Saved.Ok
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            service.reportEvent(LineupEvent.ResetFailed)
            false
        }

    /**
     * Resets the active account's starters while a refund stands ([ProEntitlement.revoked]) and Pro is off. A purchase
     * that lands mid-reset, or a switch of account, cancels the one under way before it saves; a purchase that lands
     * while it waits for the lineup's lock is caught there. An account whose reset is done for this refund
     * ([ProEntitlement.refundHandled]) is not reset again; one that could not be judged or reset yet
     * ([Revocation.NotNow]) is asked again at its next sign-in or start.
     */
    fun watch(
        scope: CoroutineScope,
        pro: ProEntitlement,
        library: LibraryRepository,
    ): Job =
        scope.launch {
            combine(pro.revoked, pro.pro, library.activeAccount()) { revoked, isPro, account ->
                account.takeIf { revoked && !isPro }
            }.collectLatest { account ->
                val owner = account?.let(::ownerKey)
                if (owner != null && !pro.isRefundHandled(owner)) {
                    val done = onRevoked(account) { pro.revoked.value && !pro.pro.value } != Revocation.NotNow
                    if (done) pro.refundHandled(owner)
                }
            }
        }
}

/** What came of a refund's reset for one account. */
enum class Revocation {
    /** The starters were reset. */
    Reset,

    /** The lineup was read and no starter holds a viewer's mark. */
    NothingToReset,

    /** Not judged or not done yet (no readable lineup, no libraries, a failed or refused save): ask again later. */
    NotNow,
}

// The opaque key a refund's handled accounts are kept by: the lineup owner's canonical server and user IDs.
private fun ownerKey(account: LibraryAccount): String? {
    val owner = canonicalAccount(account) ?: return null
    return "${owner.server.value}:${owner.user.value}"
}
