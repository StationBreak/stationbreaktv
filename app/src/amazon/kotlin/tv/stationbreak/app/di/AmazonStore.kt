// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.store.PurchaseOutcome
import tv.stationbreak.core.domain.store.RestoreOutcome
import tv.stationbreak.core.domain.store.StoreEntitlement
import kotlin.time.Duration.Companion.seconds

/** How a full purchase-updates sync ended. */
internal enum class SyncResult { Found, None, Failed }

/** A purchase-updates listing being read, page by page. [resetting] is true for the `reset = true` one. */
internal class Sync(
    val resetting: Boolean,
) {
    val done = CompletableDeferred<SyncResult>()
    val receipts = ArrayList<StoreReceipt>()
}

/**
 * Where [StoreEntitlement.verified] is decided from receipts. A Pro receipt is fulfilled with the SDK first, and only
 * then does `verified` become true; `verified` becomes false only in [settle], for a finished `reset = true` listing.
 */
internal class Ledger(
    private val sdk: AppstoreSdk,
    private val verified: MutableStateFlow<Boolean?>,
) {
    private val fulfilled = HashSet<String>()

    /** Fulfills a valid Pro receipt, then and only then sets verified. False if `notifyFulfillment` did not happen. */
    fun grant(receipt: StoreReceipt): Boolean {
        val already = synchronized(fulfilled) { receipt.id in fulfilled }
        val ok = already || sdk.notifyFulfilled(receipt.id)
        if (ok) {
            synchronized(fulfilled) { fulfilled += receipt.id }
            verified.value = true
        }
        return ok
    }

    /** [granted] if [receipt] is a live (not canceled) Pro receipt that could be fulfilled, else Failed. */
    fun outcomeOf(
        receipt: StoreReceipt?,
        granted: PurchaseOutcome,
    ): PurchaseOutcome {
        val live = receipt?.takeIf { it.sku == PRO_SKU && !it.canceled }
        return if (live != null && grant(live)) granted else PurchaseOutcome.Failed
    }

    /** Decides a listing that has no more pages. [resync] asks for a full listing. */
    fun settle(
        session: Sync,
        resync: () -> Unit,
    ): SyncResult {
        val pro = session.receipts.filter { it.sku == PRO_SKU }
        val live = pro.firstOrNull { !it.canceled }
        return when {
            live != null -> {
                if (grant(live)) SyncResult.Found else SyncResult.Failed
            }

            session.resetting -> {
                // The one place that says false: a successful, fully paged reset = true listing with no live Pro.
                verified.value = false
                SyncResult.None
            }

            else -> {
                // A delta that cancels Pro proves nothing alone: ask for the full listing.
                if (pro.isNotEmpty()) resync()
                SyncResult.None
            }
        }
    }
}

/**
 * The Amazon Appstore adapter behind [StoreEntitlement] (#99, #36, ADR-0032), over the [AppstoreSdk] seam.
 * The contract:
 *
 * - [verified] is true only after the SDK reports a valid Pro receipt (a purchase SUCCESSFUL, or ALREADY_PURCHASED, or
 *   a receipt in the purchase updates) and `notifyFulfillment` was called for it;
 * - it is false ONLY from a successful, fully paged (`hasMore == false`) `getPurchaseUpdates(reset = true)` sync that
 *   lists no Pro SKU, or lists it canceled. A refund is acted on only as the change from true to such a false;
 * - it stays null, never false, for anything else: an empty delta from `reset = false`, an error, a partial or
 *   unfinished page, offline. Then the cache stands;
 * - it keeps moving on its own: a purchase the purchase screen stopped waiting for (the viewer tuned away), or one
 *   that settles later, reaches it from the purchase responses and updates the SDK still delivers;
 * - [available] is true whenever the SDK is bound and the store did not say it is unsupported: offline too;
 * - nothing is logged, and no receipt, user ID, marketplace ID or token is kept, printed or passed on;
 * - the one SKU is [PRO_SKU], the only place it is named.
 *
 * [onForeground] asks for the purchase updates at start and at each resume. Nothing runs in this class unless
 * `BuildConfig.PRO_ON_SALE` built it ([StoreModule]).
 */
internal class AmazonStore(
    private val sdk: AppstoreSdk,
    private val scope: CoroutineScope,
) : StoreEntitlement,
    AppstoreEvents {
    private val verifiedState = MutableStateFlow<Boolean?>(null)
    private val availableState = MutableStateFlow(false)
    private val priceState = MutableStateFlow<String?>(null)
    private val ledger = Ledger(sdk, verifiedState)

    override val verified: StateFlow<Boolean?> = verifiedState
    override val available: StateFlow<Boolean> = availableState
    override val price: StateFlow<String?> = priceState

    // The state below is touched from the caller's thread and from the SDK's callbacks.
    private val lock = Any()
    private var bound = false
    private var unsupported = false
    private val syncs = HashMap<String, Sync>()
    private val purchases = HashMap<String, CompletableDeferred<PurchaseOutcome>>()

    // One sync at a time: a `reset = false` request for the next page must not be taken by another sync.
    private val syncMutex = Mutex()

    /** At start and at each resume: registers with the SDK if not yet, then asks what is owned, and the price. */
    fun onForeground() {
        if (!ensureBound()) return
        scope.launch { sync() }
        if (priceState.value == null) sdk.getProductData(setOf(PRO_SKU))
    }

    override suspend fun purchase(): PurchaseOutcome {
        if (!ensureBound()) return PurchaseOutcome.Failed
        val waiter = CompletableDeferred<PurchaseOutcome>()
        val id = synchronized(lock) { sdk.purchase(PRO_SKU)?.also { purchases[it] = waiter } }
        return if (id == null) {
            PurchaseOutcome.Failed
        } else {
            try {
                waiter.await()
            } finally {
                // The screen's call may be cancelled; the purchase is not, and its answer still reaches `verified`.
                synchronized(lock) { purchases.remove(id) }
            }
        }
    }

    override suspend fun restore(): RestoreOutcome {
        if (!ensureBound()) return RestoreOutcome.Failed
        return when (sync()) {
            SyncResult.Found -> RestoreOutcome.Found
            SyncResult.None -> RestoreOutcome.None
            SyncResult.Failed -> RestoreOutcome.Failed
        }
    }

    private fun ensureBound(): Boolean =
        synchronized(lock) {
            if (!bound) bound = sdk.register(this)
            availableState.value = bound && !unsupported
            bound
        }

    // Any answer that is not "unsupported" shows a store is there, even a failure (offline).
    private fun answered(unsupportedNow: Boolean) {
        synchronized(lock) {
            unsupported = unsupportedNow
            availableState.value = bound && !unsupported
        }
    }

    /** A full `reset = true` sync, paged to its end. Failed if the store does not answer in a minute. */
    private suspend fun sync(): SyncResult =
        syncMutex.withLock {
            val sync = Sync(resetting = true)
            val asked = synchronized(lock) { sdk.getPurchaseUpdates(reset = true)?.also { syncs[it] = sync } }
            val result =
                if (asked == null) SyncResult.Failed else withTimeoutOrNull(SYNC_TIMEOUT) { sync.done.await() }
            // A timed-out sync's late pages are handled as unsolicited deltas, which never say false.
            synchronized(lock) { syncs.values.removeAll { it === sync } }
            result ?: SyncResult.Failed
        }

    override fun onPurchaseUpdates(page: UpdatesPage) {
        answered(page.reply == Reply.Unsupported)
        val sync = synchronized(lock) { syncs.remove(page.requestId) }
        // An answer nobody is waiting for is a delta: it can grant, and it can ask for a full sync, never say false.
        val session = sync ?: Sync(resetting = false)
        if (page.reply != Reply.Ok) {
            session.done.complete(SyncResult.Failed)
            return
        }
        session.receipts += page.receipts
        if (page.hasMore) {
            askNextPage(session)
        } else {
            session.done.complete(ledger.settle(session) { scope.launch { sync() } })
        }
    }

    private fun askNextPage(session: Sync) {
        val next = synchronized(lock) { sdk.getPurchaseUpdates(reset = false)?.also { syncs[it] = session } }
        if (next == null) session.done.complete(SyncResult.Failed)
    }

    override fun onProductData(data: ProductData) {
        answered(data.reply == Reply.Unsupported)
        val localized = data.prices[PRO_SKU]?.trim()
        if (data.reply == Reply.Ok && !localized.isNullOrEmpty()) priceState.value = localized
    }

    override fun onPurchase(result: PurchaseResult) {
        answered(result.reply == PurchaseReply.Unsupported)
        // A response with no waiter (the screen was left, or a hold settled later) is granted all the same.
        val waiter = synchronized(lock) { purchases.remove(result.requestId) }
        val receipt = result.receipt
        when (result.reply) {
            PurchaseReply.Successful -> {
                val outcome = ledger.outcomeOf(receipt, PurchaseOutcome.Purchased)
                waiter?.complete(outcome)
            }

            PurchaseReply.Pending -> {
                waiter?.complete(PurchaseOutcome.Pending)
            }

            PurchaseReply.Failed, PurchaseReply.Unsupported -> {
                waiter?.complete(PurchaseOutcome.Failed)
            }

            PurchaseReply.AlreadyPurchased -> {
                if (receipt != null && receipt.sku == PRO_SKU) {
                    waiter?.complete(ledger.outcomeOf(receipt, PurchaseOutcome.AlreadyOwned))
                } else {
                    // No receipt came with it: the listing of what the account owns has it.
                    scope.launch {
                        val found = sync() == SyncResult.Found
                        waiter?.complete(if (found) PurchaseOutcome.AlreadyOwned else PurchaseOutcome.Failed)
                    }
                }
            }
        }
    }

    private companion object {
        val SYNC_TIMEOUT = 60.seconds
    }
}
