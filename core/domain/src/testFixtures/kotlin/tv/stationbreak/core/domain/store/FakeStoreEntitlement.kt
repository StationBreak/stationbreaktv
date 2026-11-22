// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A [StoreEntitlement] for tests: [purchase] ends as [next], and the store's state follows what a real one would
 * report ([PurchaseOutcome.Purchased] and [PurchaseOutcome.AlreadyOwned] verify Pro, the rest change nothing). [revoke] is a refund seen in the
 * purchase updates, and [owned] is what the account owns, read by [restore].
 */
class FakeStoreEntitlement(
    initial: Boolean? = null,
) : StoreEntitlement {
    private val state = MutableStateFlow(initial)

    override val verified: StateFlow<Boolean?> = state

    /** What the next [purchase] ends as. */
    var next: PurchaseOutcome = PurchaseOutcome.Purchased

    /** What the account owns at the store: the answer of the next [restore]. */
    var owned: Boolean = false

    var restores: Int = 0
        private set

    override suspend fun purchase(): PurchaseOutcome {
        if (next == PurchaseOutcome.Purchased || next == PurchaseOutcome.AlreadyOwned) {
            owned = true
            state.value = true
        }
        return next
    }

    override suspend fun restore() {
        restores++
        state.value = owned
    }

    /** The store reports the purchase refunded. */
    fun revoke() {
        owned = false
        state.value = false
    }
}

/** A [ProCache] in memory, which counts its writes of the owned answer. */
class InMemoryProCache(
    private var held: Boolean = false,
    @Volatile private var refundPending: Boolean = false,
) : ProCache {
    var writes: Int = 0
        private set

    override suspend fun read(): Boolean = held

    override suspend fun write(owned: Boolean) {
        writes++
        held = owned
    }

    override suspend fun readRefundPending(): Boolean = refundPending

    override suspend fun writeRefundPending(pending: Boolean) {
        refundPending = pending
    }
}
