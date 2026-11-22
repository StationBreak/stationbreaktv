// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.store

import kotlinx.coroutines.flow.StateFlow

/**
 * What the app store says about StationBreak Pro (#99, ADR-0032). The one port the store SDKs sit behind: only the
 * Amazon release's adapter implements it for real, so no other flavor, and no test, ever sees a billing SDK.
 */
interface StoreEntitlement {
    /**
     * The store's last verified answer: true while it reports a valid Pro purchase, false once it reports none or a
     * revoked one, null until it has answered at all (start-up, or offline). An adapter sets true only after the SDK
     * reports a valid receipt and the purchase is fulfilled, and false when the purchase updates revoke it.
     */
    val verified: StateFlow<Boolean?>

    /** Starts the store's purchase of Pro. The answer reaches [verified] as well as the outcome. */
    suspend fun purchase(): PurchaseOutcome

    /** Asks the store again what the account owns (after a reinstall, on a second TV, on start and resume). */
    suspend fun restore()
}

/** How a [StoreEntitlement.purchase] ended. Only [Purchased] and [AlreadyOwned] unlock Pro. */
enum class PurchaseOutcome {
    /** The store charged and reported a valid purchase. */
    Purchased,

    /** The account owned Pro already: nothing was charged, and Pro is unlocked. */
    AlreadyOwned,

    /** The viewer backed out. Nothing changed. */
    Cancelled,

    /** The store or the network failed, or the receipt did not verify. Nothing changed. */
    Failed,

    /** The store has not settled the purchase (a hold). Pro stays locked until it reports one. */
    Pending,
}

/**
 * The last verified answer, kept on this TV so an offline start keeps Pro. A revoke writes false over it. Beside it,
 * whether a verified refund still owes this TV its reset ([readRefundPending]), so a kill between the two does not
 * skip the reset.
 */
interface ProCache {
    suspend fun read(): Boolean

    suspend fun write(owned: Boolean)

    suspend fun readRefundPending(): Boolean

    suspend fun writeRefundPending(pending: Boolean)
}
