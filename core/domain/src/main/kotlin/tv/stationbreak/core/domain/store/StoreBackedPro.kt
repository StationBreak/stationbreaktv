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
 */
class StoreBackedPro(
    buildUnlocked: Boolean,
    private val store: StoreEntitlement,
    private val cache: ProCache,
    scope: CoroutineScope,
) : ProEntitlement {
    private val state = MutableStateFlow(buildUnlocked)

    override val pro: StateFlow<Boolean> = state

    init {
        if (!buildUnlocked) {
            scope.launch {
                if (cache.read() && store.verified.value == null) state.value = true
                store.verified.filterNotNull().collect {
                    state.value = it
                    cache.write(it)
                }
            }
        }
    }
}
