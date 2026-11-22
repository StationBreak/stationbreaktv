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
 * clears it. Until the store has answered, the cache stands; a store that answers false always beats the cache.
 * [revoked] is set only by that change from owned to a verified false, so a TV that never held Pro is never refunded.
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
                if (cache.read() && store.verified.value == null) state.value = true
                store.verified.filterNotNull().collect {
                    // A refund is a change from owned (verified, or kept from before) to a verified "not owned".
                    if (it) {
                        refunded.value = false
                    } else if (state.value) {
                        refunded.value = true
                    }
                    state.value = it
                    cache.write(it)
                }
            }
        }
    }
}
