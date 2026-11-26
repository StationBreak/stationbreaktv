// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.store.PurchaseOutcome
import tv.stationbreak.core.domain.store.RestoreOutcome
import tv.stationbreak.core.domain.store.StoreEntitlement
import kotlin.time.Duration.Companion.minutes
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
 * then does `verified` become true; `verified` becomes false only in [settle], for a finished `reset = true` listing,
 * and never while a purchase this process granted has not yet shown in a listing: the store's listing can lag a
 * purchase it reported SUCCESSFUL, and that lag is not a refund.
 */
internal class Ledger(
    private val sdk: AppstoreSdk,
    private val verified: MutableStateFlow<Boolean?>,
) {
    // Receipt IDs already fulfilled in this process: memory only, never persisted, logged or passed on.
    private val fulfilled = HashSet<String>()

    // Receipts granted from a purchase response and not yet seen in a listing: the store's listing may trail them.
    // Memory only, never logged. A receipt a listing showed is not in it, so a later listing without it is a refund.
    private val awaitingListing = HashSet<String>()

    // True once a listing that lacked such a receipt has been answered by one re-sync (the lag gets one more look;
    // after that the grant stands). Reset when a new purchase is granted.
    private var rechecked = false

    /**
     * Fulfills a valid Pro receipt, then and only then sets verified. `notifyFulfillment` is void and asynchronous, so
     * "fulfilled" means the call was made and did not throw; false if it did not happen.
     */
    fun grant(receipt: StoreReceipt): Boolean {
        val already = synchronized(fulfilled) { receipt.id in fulfilled }
        val ok = already || sdk.notifyFulfilled(receipt.id)
        if (ok) {
            synchronized(fulfilled) { fulfilled += receipt.id }
            verified.value = true
        }
        return ok
    }

    private fun awaitingAListing(): Boolean = synchronized(fulfilled) { awaitingListing.isNotEmpty() }

    /** [granted] if [receipt] is a live (not canceled) Pro receipt that could be fulfilled, else Failed. */
    fun outcomeOf(
        receipt: StoreReceipt?,
        granted: PurchaseOutcome,
    ): PurchaseOutcome {
        val live = receipt?.takeIf { it.sku == PRO_SKU && !it.canceled }
        if (live == null || !grant(live)) return PurchaseOutcome.Failed
        synchronized(fulfilled) {
            if (awaitingListing.add(live.id)) rechecked = false
        }
        return granted
    }

    /**
     * Decides a listing that has no more pages. [resync] asks for a full listing; [recheck] asks for one a moment
     * later, for a listing that lacked a receipt this process granted.
     */
    fun settle(
        session: Sync,
        resync: () -> Unit,
        recheck: () -> Unit = resync,
    ): SyncResult {
        val pro = session.receipts.filter { it.sku == PRO_SKU }
        val live = pro.firstOrNull { !it.canceled }
        return when {
            live != null -> {
                synchronized(fulfilled) { awaitingListing.remove(live.id) }
                if (grant(live)) SyncResult.Found else SyncResult.Failed
            }

            session.resetting && awaitingAListing() -> {
                // A purchase granted here has not shown in a listing, and this one lacks it: the store lags, so it is
                // not a refund. Look once more, and say nothing (verified stays true; no refund is recorded).
                val first =
                    synchronized(fulfilled) {
                        val fresh = !rechecked
                        rechecked = true
                        fresh
                    }
                if (first) recheck()
                SyncResult.None
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
 *   unfinished page, offline. Then the cache stands. While a purchase this process granted has not yet shown
 *   in a listing, it never says false: a listing that lacks the receipt just fulfilled (the store lags) is looked at
 *   once more, never read as a refund;
 * - it keeps moving on its own: a purchase the purchase screen stopped waiting for (the viewer tuned away), or one
 *   that settles later, reaches it from the purchase responses and updates the SDK still delivers;
 * - [available] is true whenever the SDK is bound and the store did not say it is unsupported: offline too;
 * - nothing is logged or persisted, and no user ID, marketplace ID or token is read. Receipt IDs are held in memory
 *   only, for the life of the process, to tell the SDK once that a receipt was fulfilled, and are never printed;
 * - `notifyFulfillment` is void and asynchronous, so "fulfilled" means the call was made and did not throw;
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
                // A store that never answers must not strand the screen; a late reply still grants in [onPurchase].
                withTimeoutOrNull(PURCHASE_TIMEOUT) { waiter.await() } ?: PurchaseOutcome.Failed
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
        val session = synchronized(lock) { syncs.remove(page.requestId) }
        if (session == null) {
            // An answer nobody is waiting for is a delta. It can grant, and it asks for a full sync (behind the mutex)
            // when it has more pages or cancels Pro. It is never paged here, since `reset = false` would take pages
            // from a sync in flight, and it never says false.
            if (page.reply == Reply.Ok) {
                val delta = Sync(resetting = false).also { it.receipts += page.receipts }
                ledger.settle(delta, resync = { scope.launch { sync() } })
                if (page.hasMore) scope.launch { sync() }
            }
            return
        }
        if (page.reply != Reply.Ok) {
            session.done.complete(SyncResult.Failed)
            return
        }
        session.receipts += page.receipts
        if (page.hasMore) {
            val next = synchronized(lock) { sdk.getPurchaseUpdates(reset = false)?.also { syncs[it] = session } }
            if (next == null) session.done.complete(SyncResult.Failed)
        } else {
            session.done.complete(ledger.settle(session, { scope.launch { sync() } }, ::recheckLater))
        }
    }

    // One more full look a few seconds on, for a store whose listing trails the purchase it just reported.
    private fun recheckLater() {
        scope.launch {
            delay(RECHECK_DELAY)
            sync()
        }
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
        val PURCHASE_TIMEOUT = 3.minutes
        val RECHECK_DELAY = 5.seconds
    }
}
