// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.store

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * A [StoreEntitlement] for tests: [purchase] ends as [next], and the store's state follows what a real one would
 * report ([PurchaseOutcome.Purchased] and [PurchaseOutcome.AlreadyOwned] verify Pro, the rest change nothing).
 * [revoke] is a refund seen in the purchase updates, and [owned] is what the account owns, read by [restore].
 * Open only because a test wraps [verified] to see when it is collected.
 */
open class FakeStoreEntitlement(
    initial: Boolean? = null,
) : StoreEntitlement {
    private val state = MutableStateFlow(initial)

    override val verified: StateFlow<Boolean?> = state

    /** Whether the store can be bought from: a fake is available unless a test says otherwise. */
    override val available = MutableStateFlow(true)

    /** What the store says Pro costs; null is a store that has not given a price. */
    override val price = MutableStateFlow<String?>("$19.99")

    /** The next [restore] fails (the store could not be asked). */
    var restoreFails: Boolean = false

    /** [purchase] throws (the store call itself broke). */
    var purchaseThrows: Boolean = false

    /** When set, [purchase] waits for it to complete before it answers: a store call still out. */
    var purchaseHold: CompletableDeferred<Unit>? = null

    /** What the next [purchase] ends as. */
    var next: PurchaseOutcome = PurchaseOutcome.Purchased

    /** What the account owns at the store: the answer of the next [restore]. */
    var owned: Boolean = false

    var restores: Int = 0
        private set

    /** How many times [purchase] was asked. */
    var purchases: Int = 0
        private set

    override suspend fun purchase(): PurchaseOutcome {
        purchases++
        purchaseHold?.await()
        check(!purchaseThrows) { "store broke" }
        if (next == PurchaseOutcome.Purchased || next == PurchaseOutcome.AlreadyOwned) {
            owned = true
            state.value = true
        }
        return next
    }

    override suspend fun restore(): RestoreOutcome {
        restores++
        if (restoreFails) return RestoreOutcome.Failed
        state.value = owned
        return if (owned) RestoreOutcome.Found else RestoreOutcome.None
    }

    /** A held purchase settles later: the store now reports Pro as [verified] (true lands it, false lapses it). */
    fun settle(verified: Boolean) {
        owned = verified
        state.value = verified
    }

    /** The store reports the purchase refunded. */
    fun revoke() {
        owned = false
        state.value = false
    }
}

/** A [ProCache] in memory, which counts its writes of the owned answer. A null [refundPending] is an unknown record. */
class InMemoryProCache(
    private var held: Boolean = false,
    @Volatile private var refundPending: Boolean? = false,
) : ProCache {
    var writes: Int = 0
        private set

    override suspend fun read(): Boolean = held

    override suspend fun write(owned: Boolean) {
        writes++
        held = owned
    }

    @Volatile private var handled: Set<String> = emptySet()

    /** When the owned answer was last verified true; a test sets it to make an old (or missing) stamp. */
    @Volatile var verifiedAt: Instant? = null

    override suspend fun readVerifiedAt(): Instant? = verifiedAt

    override suspend fun writeVerifiedAt(at: Instant) {
        verifiedAt = at
    }

    override suspend fun readRefundPending(): Boolean? = refundPending

    override suspend fun writeRefundPending(pending: Boolean) {
        refundPending = pending
        if (!pending) handled = emptySet()
    }

    override suspend fun readRefundHandled(): Set<String> = handled

    override suspend fun addRefundHandled(owner: String) {
        handled = handled + owner
    }
}
