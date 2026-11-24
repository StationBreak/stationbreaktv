// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import android.content.Context
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.FulfillmentResult
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.Receipt
import com.amazon.device.iap.model.UserDataResponse

/**
 * The Appstore SDK behind [AppstoreSdk]: the one file in the app that names it (#99, ADR-0032). It translates; the
 * decisions are [AmazonStore]'s. It never logs, and never prints a response, a receipt, a user ID or a marketplace ID:
 * not even their `toString`. The user data of a response is never read.
 */
internal class PurchasingServiceSdk(
    private val context: Context,
) : AppstoreSdk {
    override fun register(events: AppstoreEvents): Boolean =
        sdk { PurchasingService.registerListener(context, AppstoreListener(events)) } != null

    override fun getPurchaseUpdates(reset: Boolean): String? = requestId { PurchasingService.getPurchaseUpdates(reset) }

    override fun getProductData(skus: Set<String>): String? = requestId { PurchasingService.getProductData(skus) }

    override fun purchase(sku: String): String? = requestId { PurchasingService.purchase(sku) }

    override fun notifyFulfilled(receiptId: String): Boolean =
        sdk { PurchasingService.notifyFulfillment(receiptId, FulfillmentResult.FULFILLED) } != null

    // The request's ID is all that is kept of what the SDK returns.
    private fun requestId(call: () -> Any): String? = sdk { call().toString() }

    // The SDK reads its authentication key (assets/AppstoreAuthenticationKey.pem) and talks to the Appstore client, and
    // what it throws on a device with neither is its own business: a failed call is a call that did not happen.
    @Suppress("TooGenericExceptionCaught")
    private fun <T : Any> sdk(call: () -> T): T? =
        try {
            call()
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
}

/**
 * Translates the SDK's responses into [AppstoreEvents]. The SDK's jar has old bytecode that a JVM refuses to load, so
 * no unit test can build its responses; the logic is tested over the seam instead.
 */
internal class AppstoreListener(
    private val events: AppstoreEvents,
) : PurchasingListener {
    // The SDK's callback must not throw into it: a response that cannot be read is a failed one.
    override fun onPurchaseUpdatesResponse(response: PurchaseUpdatesResponse) {
        events.onPurchaseUpdates(updatesPage(response))
    }

    override fun onProductDataResponse(response: ProductDataResponse) {
        events.onProductData(productData(response))
    }

    override fun onPurchaseResponse(response: PurchaseResponse) {
        events.onPurchase(purchaseResult(response))
    }

    // The app asks for no user data: the user ID and marketplace ID are never needed, so never read.
    override fun onUserDataResponse(response: UserDataResponse) = Unit

    @Suppress("TooGenericExceptionCaught")
    private fun updatesPage(response: PurchaseUpdatesResponse): UpdatesPage {
        val id = response.requestId?.toString().orEmpty()
        return try {
            val reply =
                when (response.requestStatus) {
                    PurchaseUpdatesResponse.RequestStatus.SUCCESSFUL -> Reply.Ok
                    PurchaseUpdatesResponse.RequestStatus.NOT_SUPPORTED -> Reply.Unsupported
                    else -> Reply.Failed
                }
            updatesPageOf(id, reply, response.receipts?.map(::receipt), response.hasMore())
        } catch (_: Exception) {
            updatesPageOf(id, Reply.Failed, null, false)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun productData(response: ProductDataResponse): ProductData =
        try {
            val reply =
                when (response.requestStatus) {
                    ProductDataResponse.RequestStatus.SUCCESSFUL -> Reply.Ok
                    ProductDataResponse.RequestStatus.NOT_SUPPORTED -> Reply.Unsupported
                    else -> Reply.Failed
                }
            ProductData(reply, response.productData.orEmpty().mapValues { it.value?.price })
        } catch (_: Exception) {
            ProductData(Reply.Failed, emptyMap())
        }

    @Suppress("TooGenericExceptionCaught")
    private fun purchaseResult(response: PurchaseResponse): PurchaseResult {
        val id = response.requestId?.toString().orEmpty()
        return try {
            val reply =
                when (response.requestStatus) {
                    PurchaseResponse.RequestStatus.SUCCESSFUL -> PurchaseReply.Successful
                    PurchaseResponse.RequestStatus.ALREADY_PURCHASED -> PurchaseReply.AlreadyPurchased
                    PurchaseResponse.RequestStatus.PENDING -> PurchaseReply.Pending
                    PurchaseResponse.RequestStatus.NOT_SUPPORTED -> PurchaseReply.Unsupported
                    else -> PurchaseReply.Failed
                }
            PurchaseResult(id, reply, response.receipt?.let(::receipt))
        } catch (_: Exception) {
            PurchaseResult(id, PurchaseReply.Failed, null)
        }
    }

    private fun receipt(receipt: Receipt?): StoreReceipt? {
        val id = receipt?.receiptId
        val sku = receipt?.sku
        return if (receipt == null || id == null || sku == null) null else StoreReceipt(id, sku, receipt.isCanceled)
    }
}
