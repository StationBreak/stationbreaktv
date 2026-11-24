// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import tv.stationbreak.app.di.AppstoreEvents
import tv.stationbreak.app.di.AppstoreSdk

/** An [AppstoreSdk] that answers nothing by itself: a test delivers each response through [events], like the SDK. */
internal class FakeAppstoreSdk : AppstoreSdk {
    class Request(
        val id: String,
        val kind: String,
        val reset: Boolean = false,
        val sku: String? = null,
    )

    var bindable = true
    var registers = 0
    var events: AppstoreEvents? = null
    val requests = mutableListOf<Request>()
    val fulfilled = mutableListOf<String>()

    /** What `verified` was when each fulfillment was told: it must not be true before. */
    var notifyObserver: () -> Unit = {}
    var fulfillWorks = true
    var asks = true
    private var counter = 0

    val last: Request get() = requests.last()

    fun asked(kind: String): List<Request> = requests.filter { it.kind == kind }

    override fun register(events: AppstoreEvents): Boolean {
        registers++
        this.events = events.takeIf { bindable }
        return bindable
    }

    private fun record(
        kind: String,
        reset: Boolean = false,
        sku: String? = null,
    ): String? {
        if (!asks) return null
        val id = "request-${counter++}"
        requests += Request(id, kind, reset, sku)
        return id
    }

    override fun getPurchaseUpdates(reset: Boolean): String? = record("updates", reset = reset)

    override fun getProductData(skus: Set<String>): String? = record("products", sku = skus.single())

    override fun purchase(sku: String): String? = record("purchase", sku = sku)

    override fun notifyFulfilled(receiptId: String): Boolean {
        notifyObserver()
        if (fulfillWorks) fulfilled += receiptId
        return fulfillWorks
    }
}
