// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.store

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

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

    /**
     * Whether the store can be bought from at all: true only with a real store adapter behind it. While false, nothing
     * Pro-facing is shown to a viewer who does not have Pro (no Pro row, no Pro notes, no purchase screen), so a build
     * with no working store, such as the Amazon release before its adapter lands, stays the plain free app. A real
     * adapter reports true whenever its SDK is bound, offline too, so a viewer without Pro keeps the purchase screen;
     * an owner's Settings row shows whatever this says.
     */
    val available: StateFlow<Boolean>

    /**
     * The price of Pro as the store words it, in the store's currency and format (never built here, and never kept in
     * the app), or null until the store has given one, or when it cannot.
     */
    val price: StateFlow<String?>

    /** Starts the store's purchase of Pro. The answer reaches [verified] as well as the outcome. */
    suspend fun purchase(): PurchaseOutcome

    /**
     * Asks the store again what the account owns (after a reinstall, on a second TV, on start and resume), and says
     * what it found. The answer reaches [verified] as well as the outcome.
     */
    suspend fun restore(): RestoreOutcome
}

/** What a [StoreEntitlement.restore] found. Only [Found] unlocks Pro. */
enum class RestoreOutcome {
    /** The account owns Pro: it is unlocked on this TV. */
    Found,

    /** The store answered, and the account owns no Pro. */
    None,

    /** The store or the network failed, so nothing is known. Nothing changed. */
    Failed,
}

/**
 * What a build offers a viewer who reaches for Pro (#99): a [Store] that sells it (the Amazon release), or a
 * [Support] link (the GitHub build, which is always unlocked and carries no billing code). Bound per flavor in `app`.
 */
sealed interface ProOffer {
    data object Store : ProOffer

    /** The project's support page; shown as text, since a TV has no browser to open it in. */
    data class Support(
        val url: String,
    ) : ProOffer
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
 * whether a verified refund stands ([readRefundPending]), so a kill before its reset does not skip it, and the
 * accounts whose reset for that refund is done ([readRefundHandled]): opaque lineup-owner keys, never names.
 */
interface ProCache {
    suspend fun read(): Boolean

    suspend fun write(owned: Boolean)

    /**
     * When the store last answered true (#1065), or null when none is kept or it cannot be read (a garbled value reads
     * as none). The cached owned answer stops granting Pro [StoreBackedPro.OFFLINE_GRACE] after it.
     */
    suspend fun readVerifiedAt(): Instant?

    suspend fun writeVerifiedAt(at: Instant)

    /** Null: unknown (the record is corrupt or cannot be read). Not a refund, but no offline Pro either (#1067). */
    suspend fun readRefundPending(): Boolean?

    /**
     * True: a refund stands. False (a verified purchase): it is over, and the handled accounts are forgotten too.
     * Either ends an unknown.
     */
    suspend fun writeRefundPending(pending: Boolean)

    suspend fun readRefundHandled(): Set<String>

    suspend fun addRefundHandled(owner: String)
}
