// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

/**
 * The thin seam between [AmazonStore] and the Appstore SDK (#99, ADR-0032). Nothing here names an SDK type, so the
 * contract's logic is tested with a fake of [AppstoreSdk] and no SDK. [PurchasingServiceSdk] is the one file that binds
 * it to the real SDK. Every call returns instead of throwing: null or false means the call did not happen.
 */
internal interface AppstoreSdk {
    /** Registers [events] with the SDK. False when the SDK cannot be bound, so no store is behind it. */
    fun register(events: AppstoreEvents): Boolean

    /** Asks for purchase updates. Returns the request's ID, which the answer carries, or null if it was not asked. */
    fun getPurchaseUpdates(reset: Boolean): String?

    /** Asks for the products named. Returns the request's ID, or null if it was not asked. */
    fun getProductData(skus: Set<String>): String?

    /** Starts the purchase of [sku]. Returns the request's ID, or null if it was not asked. */
    fun purchase(sku: String): String?

    /** Tells the store the receipt's entitlement was granted. False when the call did not happen. */
    fun notifyFulfilled(receiptId: String): Boolean
}

/** The SDK's answers, as the adapter's own types. They may arrive on any thread. */
internal interface AppstoreEvents {
    fun onPurchaseUpdates(page: UpdatesPage)

    fun onProductData(data: ProductData)

    fun onPurchase(result: PurchaseResult)
}

/** How a request ended. [Unsupported] is the store telling the app it has no store behind it. */
internal enum class Reply { Ok, Failed, Unsupported }

/** One page of purchase updates. [requestId] says which request it answers. */
internal class UpdatesPage(
    val requestId: String,
    val reply: Reply,
    val receipts: List<StoreReceipt>,
    val hasMore: Boolean,
)

/** The store's answer to a product request: [prices] has each SKU's localized price string, as the store worded it. */
internal class ProductData(
    val reply: Reply,
    val prices: Map<String, String?>,
)

/**
 * A page from the SDK's receipts, each already mapped or null when it could not be. A receipt that cannot be read (or a
 * missing list) makes the whole page [Reply.Failed]: dropping it could hide a Pro receipt and turn a full listing into
 * "no Pro", which would say false.
 */
internal fun updatesPageOf(
    requestId: String,
    reply: Reply,
    receipts: List<StoreReceipt?>?,
    hasMore: Boolean,
): UpdatesPage {
    val readable = receipts?.filterNotNull()
    return if (reply != Reply.Ok || readable == null || readable.size != receipts.size) {
        UpdatesPage(requestId, if (reply == Reply.Unsupported) reply else Reply.Failed, emptyList(), false)
    } else {
        UpdatesPage(requestId, reply, readable, hasMore)
    }
}

internal enum class PurchaseReply { Successful, AlreadyPurchased, Pending, Failed, Unsupported }

internal class PurchaseResult(
    val requestId: String,
    val reply: PurchaseReply,
    val receipt: StoreReceipt?,
)

/**
 * A receipt, reduced to what the adapter reads. The ID is a secret of the viewer's purchase: it is only handed back to
 * [AppstoreSdk.notifyFulfilled], and [toString] hides it so that nothing which prints this prints it.
 */
internal class StoreReceipt(
    val id: String,
    val sku: String,
    val canceled: Boolean,
) {
    override fun toString(): String = "StoreReceipt(redacted)"
}
