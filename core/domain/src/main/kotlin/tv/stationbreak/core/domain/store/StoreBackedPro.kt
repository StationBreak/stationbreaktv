// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.ProEntitlement

/**
 * "Build unlock OR store entitlement" (#99, ADR-0032). A build that unlocks Pro ([buildUnlocked]: debug, benchmark and
 * the GitHub release) is Pro and never reads the store. Any other build is Pro only while [store] has verified a
 * purchase: the last verified answer is kept in [cache], so a TV that starts offline keeps Pro, and a verified revoke
 * clears it. Until the store has answered, the cache stands unless a refund is owed; a store that answers false always
 * beats the cache.
 * [revoked] is set only by that change from owned to a verified false, so a TV that never held Pro is never refunded.
 * That refund is kept in [cache] too, before the owned answer is cleared, until a verified purchase ends it, with the
 * accounts already reset for it ([refundHandled]): a kill before a reset, or an account that signs in at a later start,
 * is still reset (#1063). A refund record that is unknown (corrupt or unreadable) is neither: it never sets [revoked]
 * or resets anything, but the cache does not grant Pro either, and the store's first verified answer settles it: true
 * ends it, false is a refund (#1067).
 */
class StoreBackedPro(
    buildUnlocked: Boolean,
    private val store: StoreEntitlement,
    private val cache: ProCache,
    scope: CoroutineScope,
) : ProEntitlement {
    private val state = MutableStateFlow(buildUnlocked)

    private val refunded = MutableStateFlow(false)

    override val pro: StateFlow<Boolean> = state

    override val revoked: StateFlow<Boolean> = refunded

    init {
        if (!buildUnlocked) {
            scope.launch {
                val owed = cache.readRefundPending()
                if (owed == true) refunded.value = true
                // Only a refund known not owed lets the cache grant: a kill can land between writing it and the cache.
                if (owed == false && cache.read() && store.verified.value == null) state.value = true
                var unknown = owed == null
                store.verified.filterNotNull().collect {
                    // A refund is a change from owned (verified, kept from before, or perhaps hidden by an unknown
                    // record) to a verified "not owned". An unknown is one on a free TV too: no mark to reset there.
                    if (it) {
                        if (refunded.value || unknown) cache.writeRefundPending(false)
                        refunded.value = false
                    } else if (state.value || unknown) {
                        cache.writeRefundPending(true)
                        refunded.value = true
                    }
                    unknown = false
                    state.value = it
                    cache.write(it)
                }
            }
        }
    }

    override suspend fun isRefundHandled(owner: String): Boolean = owner in cache.readRefundHandled()

    override suspend fun refundHandled(owner: String) = cache.addRefundHandled(owner)
}
