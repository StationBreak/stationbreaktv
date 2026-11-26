// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.app.di.AmazonStore
import tv.stationbreak.app.di.PRO_SKU
import tv.stationbreak.app.di.ProductData
import tv.stationbreak.app.di.PurchaseReply
import tv.stationbreak.app.di.PurchaseResult
import tv.stationbreak.app.di.Reply
import tv.stationbreak.app.di.StoreReceipt
import tv.stationbreak.app.di.UpdatesPage
import tv.stationbreak.app.di.updatesPageOf
import tv.stationbreak.core.domain.store.PurchaseOutcome
import tv.stationbreak.core.domain.store.RestoreOutcome

private const val PAST_TIMEOUT_MS = 61_000L
private const val PAST_RECHECK_MS = 5_001L
private const val BEFORE_PURCHASE_LIMIT_MS = 179_000L
private const val PAST_PURCHASE_LIMIT_MS = 2_000L

/** The Appstore adapter's contract (AmazonStore's KDoc), over a fake of the SDK seam: no SDK is touched. */
@Suppress("TooManyFunctions") // One test per contract bullet and case: splitting the class would hide the contract.
@OptIn(ExperimentalCoroutinesApi::class)
class AmazonStoreTest {
    private val sdk = FakeAppstoreSdk()

    private fun TestScope.store() = AmazonStore(sdk, backgroundScope + UnconfinedTestDispatcher(testScheduler))

    private fun pro(
        id: String = "r1",
        canceled: Boolean = false,
    ) = StoreReceipt(id, PRO_SKU, canceled)

    private fun page(
        id: String,
        reply: Reply = Reply.Ok,
        receipts: List<StoreReceipt> = emptyList(),
        hasMore: Boolean = false,
    ) = UpdatesPage(id, reply, receipts, hasMore)

    private fun AmazonStore.answerUpdates(
        receipts: List<StoreReceipt> = emptyList(),
        hasMore: Boolean = false,
        reply: Reply = Reply.Ok,
    ) = onPurchaseUpdates(page(sdk.asked("updates").last().id, reply, receipts, hasMore))

    // --- Start and resume ---------------------------------------------------------------------------------------

    @Test
    fun startRegistersAsksForAFullListingAndThePrice() =
        runTest {
            val store = store()
            assertNull("null until the store has answered", store.verified.value)
            assertFalse(store.available.value)

            store.onForeground()

            assertEquals(1, sdk.registers)
            assertEquals(listOf(true), sdk.asked("updates").map { it.reset })
            assertEquals(listOf(PRO_SKU), sdk.asked("products").map { it.sku })
            assertNull(store.verified.value)
        }

    @Test
    fun eachResumeAsksAgainButRegistersOnce() =
        runTest {
            val store = store()
            store.onForeground()
            store.answerUpdates()
            store.onForeground()

            assertEquals(1, sdk.registers)
            assertEquals(2, sdk.asked("updates").size)
        }

    // --- verified: true only after a valid receipt and notifyFulfillment ---------------------------------------------

    @Test
    fun aProReceiptInTheListingIsFulfilledBeforeVerifiedIsTrue() =
        runTest {
            val store = store()
            var seenAtNotify: Boolean? = false
            sdk.notifyObserver = { seenAtNotify = store.verified.value }
            store.onForeground()

            store.answerUpdates(listOf(pro()))

            assertNull("not true when the SDK was told", seenAtNotify)
            assertEquals(listOf("r1"), sdk.fulfilled)
            assertEquals(true, store.verified.value)
        }

    @Test
    fun aReceiptThatCannotBeFulfilledDoesNotVerify() =
        runTest {
            sdk.fulfillWorks = false
            val store = store()
            store.onForeground()

            store.answerUpdates(listOf(pro()))

            assertNull(store.verified.value)
        }

    @Test
    fun aCanceledOrOtherReceiptNeverVerifies() =
        runTest {
            val store = store()
            store.onForeground()

            store.answerUpdates(listOf(pro(canceled = true), StoreReceipt("x", "some.other.sku", false)))

            assertEquals(emptyList<String>(), sdk.fulfilled)
            assertEquals(false, store.verified.value)
        }

    // --- verified: false only from a fully paged reset = true listing --------------------------------------------

    @Test
    fun aFullyPagedResetListingWithNoProSaysFalse() =
        runTest {
            val store = store()
            store.onForeground()
            store.answerUpdates()
            assertEquals(false, store.verified.value)
        }

    @Test
    fun aResetListingWithProCanceledSaysFalseAfterATrue() =
        runTest {
            val store = store()
            store.onForeground()
            store.answerUpdates(listOf(pro()))
            assertEquals(true, store.verified.value)

            store.onForeground()
            store.answerUpdates(listOf(pro(canceled = true)))

            assertEquals(false, store.verified.value)
        }

    @Test
    fun aPartialPageSaysNothingUntilTheLastPageThenSaysFalse() =
        runTest {
            val store = store()
            store.onForeground()

            store.answerUpdates(hasMore = true)

            assertNull("a partial page is not an answer", store.verified.value)
            val resets = sdk.asked("updates").map { it.reset }
            assertEquals("the next page is asked with reset = false", listOf(true, false), resets)
            store.answerUpdates()
            assertEquals(false, store.verified.value)
        }

    @Test
    fun aProReceiptOnAnEarlierPageIsSeenOnTheLastPage() =
        runTest {
            val store = store()
            store.onForeground()
            store.answerUpdates(listOf(pro()), hasMore = true)
            store.answerUpdates()
            assertEquals(true, store.verified.value)
        }

    @Test
    fun aFailureOnALaterPageLeavesNull() =
        runTest {
            val store = store()
            store.onForeground()
            store.answerUpdates(hasMore = true)
            store.answerUpdates(reply = Reply.Failed)
            assertNull(store.verified.value)
        }

    @Test
    fun aNextPageThatCannotBeAskedLeavesNull() =
        runTest {
            val store = store()
            store.onForeground()
            sdk.asks = false
            store.answerUpdates(hasMore = true)
            assertNull(store.verified.value)
        }

    @Test
    fun anErrorOrUnsupportedListingLeavesNull() =
        runTest {
            val store = store()
            store.onForeground()
            store.answerUpdates(reply = Reply.Failed)
            assertNull(store.verified.value)

            store.onForeground()
            store.answerUpdates(reply = Reply.Unsupported)
            assertNull(store.verified.value)
        }

    @Test
    fun anEmptyDeltaFromResetFalseLeavesNull() =
        runTest {
            val store = store()
            store.onForeground()
            // The SDK delivers an update for a request this adapter did not make: a delta.
            store.onPurchaseUpdates(page("pushed-by-the-store"))
            assertNull(store.verified.value)
        }

    @Test
    fun aDeltaWithProVerifiesAndADeltaThatCancelsAsksForTheFullListingInsteadOfSayingFalse() =
        runTest {
            val store = store()
            store.onForeground()
            store.answerUpdates()
            store.onPurchaseUpdates(page("pushed-1", receipts = listOf(pro())))
            assertEquals(true, store.verified.value)

            store.onPurchaseUpdates(page("pushed-2", receipts = listOf(pro(canceled = true))))

            assertEquals("a delta alone cannot say false", true, store.verified.value)
            assertEquals("it asks for a full reset listing", true, sdk.asked("updates").last().reset)
            assertEquals(2, sdk.asked("updates").size)
        }

    @Test
    fun anUnsolicitedPageWithMoreIsNeverPagedAndLeavesAnInFlightSyncItsPages() =
        runTest {
            val store = store()
            store.onForeground()
            store.answerUpdates(hasMore = true)
            assertEquals(listOf(true, false), sdk.asked("updates").map { it.reset })

            store.onPurchaseUpdates(page("pushed", hasMore = true))

            assertEquals("no reset = false request for a delta", 2, sdk.asked("updates").size)
            store.answerUpdates()
            val resets = sdk.asked("updates").map { it.reset }
            assertEquals("the full sync waited its turn", listOf(true, false, true), resets)
            assertEquals(false, store.verified.value)
        }

    @Test
    fun aPageWithAnUnreadableReceiptNeverSaysFalse() =
        runTest {
            val store = store()
            store.onForeground()
            store.onPurchaseUpdates(updatesPageOf(sdk.last.id, Reply.Ok, listOf(null), hasMore = false))
            assertNull(store.verified.value)
        }

    @Test
    fun aListingThatIsNeverAnsweredLeavesNull() =
        runTest {
            val store = store()
            store.onForeground()
            advanceTimeBy(PAST_TIMEOUT_MS)
            assertNull(store.verified.value)
            // A late page for the abandoned request is a delta: it grants, it never says false.
            store.answerUpdates()
            assertNull(store.verified.value)
        }

    // --- available and price ------------------------------------------------------------------------------------

    @Test
    fun availableWhenBoundAndStaysSoOffline() =
        runTest {
            val store = store()
            store.onForeground()
            assertTrue(store.available.value)

            store.answerUpdates(reply = Reply.Failed)
            store.onProductData(ProductData(Reply.Failed, emptyMap()))

            assertTrue("offline is not unavailable", store.available.value)
        }

    @Test
    fun notAvailableWithoutABoundSdkOrWhenTheStoreIsUnsupported() =
        runTest {
            sdk.bindable = false
            val unbound = store()
            unbound.onForeground()
            assertFalse(unbound.available.value)
            assertEquals(PurchaseOutcome.Failed, unbound.purchase())
            assertEquals(RestoreOutcome.Failed, unbound.restore())
            assertEquals(emptyList<FakeAppstoreSdk.Request>(), sdk.requests)

            sdk.bindable = true
            val store = store()
            store.onForeground()
            store.onProductData(ProductData(Reply.Unsupported, emptyMap()))
            assertFalse(store.available.value)
        }

    @Test
    fun thePriceIsTheStoresOwnLocalizedString() =
        runTest {
            val store = store()
            store.onForeground()
            assertNull(store.price.value)

            store.onProductData(ProductData(Reply.Ok, mapOf(PRO_SKU to " 19,99 € ")))

            assertEquals("19,99 €", store.price.value)
        }

    @Test
    fun noPriceFromAFailureOrABlankOrAnotherSku() =
        runTest {
            val store = store()
            store.onForeground()
            store.onProductData(ProductData(Reply.Failed, mapOf(PRO_SKU to "$1")))
            store.onProductData(ProductData(Reply.Ok, mapOf(PRO_SKU to "  ")))
            store.onProductData(ProductData(Reply.Ok, mapOf("other" to "$1")))
            assertNull(store.price.value)
        }

    // --- purchase ----------------------------------------------------------------------------------------------

    @Test
    fun aPurchaseAsksForTheProSkuAndAValidReceiptEndsPurchasedAndVerified() =
        runTest {
            val store = store()
            val outcome = async(UnconfinedTestDispatcher(testScheduler)) { store.purchase() }
            assertEquals(PRO_SKU, sdk.last.sku)

            store.onPurchase(PurchaseResult(sdk.last.id, PurchaseReply.Successful, pro()))

            assertEquals(PurchaseOutcome.Purchased, outcome.await())
            assertEquals(listOf("r1"), sdk.fulfilled)
            assertEquals(true, store.verified.value)
        }

    @Test
    fun aSuccessfulPurchaseWithNoValidProReceiptFails() =
        runTest {
            for (receipt in listOf(null, pro(canceled = true), StoreReceipt("x", "other", false))) {
                val store = store()
                val outcome = async(UnconfinedTestDispatcher(testScheduler)) { store.purchase() }
                store.onPurchase(PurchaseResult(sdk.last.id, PurchaseReply.Successful, receipt))
                assertEquals(PurchaseOutcome.Failed, outcome.await())
                assertNull(store.verified.value)
            }
        }

    @Test
    fun alreadyPurchasedWithAReceiptIsAlreadyOwned() =
        runTest {
            val store = store()
            val outcome = async(UnconfinedTestDispatcher(testScheduler)) { store.purchase() }
            store.onPurchase(PurchaseResult(sdk.last.id, PurchaseReply.AlreadyPurchased, pro()))
            assertEquals(PurchaseOutcome.AlreadyOwned, outcome.await())
            assertEquals(true, store.verified.value)
        }

    @Test
    fun alreadyPurchasedWithNoReceiptReadsTheListing() =
        runTest {
            val store = store()
            val outcome = async(UnconfinedTestDispatcher(testScheduler)) { store.purchase() }
            store.onPurchase(PurchaseResult(sdk.last.id, PurchaseReply.AlreadyPurchased, null))
            assertEquals(true, sdk.last.reset)

            store.answerUpdates(listOf(pro()))

            assertEquals(PurchaseOutcome.AlreadyOwned, outcome.await())
            assertEquals(true, store.verified.value)
        }

    @Test
    fun aFailedPurchaseFailsAndChangesNothing() =
        runTest {
            val store = store()
            val outcome = async(UnconfinedTestDispatcher(testScheduler)) { store.purchase() }
            store.onPurchase(PurchaseResult(sdk.last.id, PurchaseReply.Failed, null))
            assertEquals(PurchaseOutcome.Failed, outcome.await())
            assertNull(store.verified.value)
        }

    @Test
    fun aPurchaseTheSdkCannotAskForFails() =
        runTest {
            sdk.asks = false
            assertEquals(PurchaseOutcome.Failed, store().purchase())
        }

    @Test
    fun aPendingPurchaseThatSettlesLaterReachesVerifiedOnItsOwn() =
        runTest {
            val store = store()
            val outcome = async(UnconfinedTestDispatcher(testScheduler)) { store.purchase() }
            val id = sdk.last.id
            store.onPurchase(PurchaseResult(id, PurchaseReply.Pending, null))
            assertEquals(PurchaseOutcome.Pending, outcome.await())
            assertNull(store.verified.value)

            // The store settles it later, for the same request, with nobody waiting.
            store.onPurchase(PurchaseResult(id, PurchaseReply.Successful, pro()))

            assertEquals(true, store.verified.value)
            assertEquals(listOf("r1"), sdk.fulfilled)
        }

    @Test
    fun aPurchaseThatSettlesAfterTheScreenWasLeftStillReachesVerified() =
        runTest {
            val store = store()
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
            val call = scope.async { store.purchase() }
            val id = sdk.last.id
            // The viewer tunes away: the purchase screen's call is cancelled.
            call.cancel()

            store.onPurchase(PurchaseResult(id, PurchaseReply.Successful, pro()))

            assertEquals(true, store.verified.value)
        }

    @Test
    fun aPendingPurchaseThatSettlesInTheUpdatesReachesVerified() =
        runTest {
            val store = store()
            store.onForeground()
            store.answerUpdates()
            assertEquals(false, store.verified.value)

            store.onPurchaseUpdates(page("pushed", receipts = listOf(pro())))

            assertEquals(true, store.verified.value)
        }

    // --- the purchase race (beta.3): the store says SUCCESSFUL, its listing has not caught up ------------------------

    // The sheet closes, onForeground asks for a full listing, and that listing lacks the receipt just fulfilled.
    private fun TestScope.boughtThenALaggingListing(): AmazonStore {
        val store = store()
        val outcome = async(UnconfinedTestDispatcher(testScheduler)) { store.purchase() }
        store.onPurchase(PurchaseResult(sdk.last.id, PurchaseReply.Successful, pro()))
        assertEquals(true, store.verified.value)
        store.onForeground()
        store.answerUpdates()
        assertTrue(outcome.isCompleted)
        return store
    }

    @Test
    fun aListingThatLacksAReceiptJustPurchasedDoesNotUnverifyIt() =
        runTest {
            val store = boughtThenALaggingListing()
            assertEquals("a lagging listing is not a refund", true, store.verified.value)
        }

    @Test
    fun aLaggingListingIsLookedAtOnceMoreAfterAMomentAndTheLateReceiptStandsVerified() =
        runTest {
            val store = boughtThenALaggingListing()
            assertEquals("no re-sync yet", 1, sdk.asked("updates").size)
            advanceTimeBy(PAST_RECHECK_MS)
            assertEquals("one more full listing", listOf(true, true), sdk.asked("updates").map { it.reset })

            store.answerUpdates(listOf(pro()))

            assertEquals(true, store.verified.value)
        }

    @Test
    fun aLaggingListingIsReSyncedOnlyOnceAndNeverSaysFalse() =
        runTest {
            val store = boughtThenALaggingListing()
            advanceTimeBy(PAST_RECHECK_MS)
            store.answerUpdates()
            advanceTimeBy(PAST_TIMEOUT_MS)
            assertEquals("no loop of re-syncs", 2, sdk.asked("updates").size)
            assertEquals(true, store.verified.value)
        }

    @Test
    fun aRefundAfterAListingShowedTheReceiptIsStillSeen() =
        runTest {
            val store = boughtThenALaggingListing()
            advanceTimeBy(PAST_RECHECK_MS)
            store.answerUpdates(listOf(pro()))
            store.onForeground()
            store.answerUpdates(listOf(pro(canceled = true)))
            assertEquals("the store showed it, then took it back: a refund", false, store.verified.value)
        }

    // --- the purchase waiter times out ------------------------------------------------------------------------

    @Test
    fun aPurchaseTheStoreNeverAnswersEndsFailedAfterThreeMinutesAndALateReplyStillGrants() =
        runTest {
            val store = store()
            val outcome = async(UnconfinedTestDispatcher(testScheduler)) { store.purchase() }
            val id = sdk.last.id
            advanceTimeBy(BEFORE_PURCHASE_LIMIT_MS)
            assertFalse("still waiting before the limit", outcome.isCompleted)
            advanceTimeBy(PAST_PURCHASE_LIMIT_MS)
            assertEquals(PurchaseOutcome.Failed, outcome.await())
            assertNull("a timeout says nothing about ownership", store.verified.value)

            store.onPurchase(PurchaseResult(id, PurchaseReply.Successful, pro()))

            assertEquals("a late reply still grants", true, store.verified.value)
            assertEquals(listOf("r1"), sdk.fulfilled)
        }

    // --- restore -----------------------------------------------------------------------------------------------

    @Test
    fun restoreIsAFullResetListing() =
        runTest {
            val store = store()
            val found = async(UnconfinedTestDispatcher(testScheduler)) { store.restore() }
            assertEquals(true, sdk.last.reset)
            store.answerUpdates(listOf(pro()))
            assertEquals(RestoreOutcome.Found, found.await())
            assertEquals(true, store.verified.value)
        }

    @Test
    fun restoreSaysNoneOnAFullListingWithNoProAndFailedOnAnError() =
        runTest {
            val store = store()
            val none = async(UnconfinedTestDispatcher(testScheduler)) { store.restore() }
            store.answerUpdates()
            assertEquals(RestoreOutcome.None, none.await())
            assertEquals(false, store.verified.value)

            val failed = async(UnconfinedTestDispatcher(testScheduler)) { store.restore() }
            store.answerUpdates(reply = Reply.Failed)
            assertEquals(RestoreOutcome.Failed, failed.await())
            assertEquals("an error does not undo the last verified answer", false, store.verified.value)
        }

    @Test
    fun restoreFailsWhenTheStoreNeverAnswers() =
        runTest {
            val store = store()
            val result = async(UnconfinedTestDispatcher(testScheduler)) { store.restore() }
            advanceTimeBy(PAST_TIMEOUT_MS)
            assertEquals(RestoreOutcome.Failed, result.await())
            assertNull(store.verified.value)
        }

    @Test
    fun restoreFailsWhenTheSdkCannotAsk() =
        runTest {
            val store = store()
            store.onForeground()
            sdk.asks = false
            assertEquals(RestoreOutcome.Failed, store.restore())
        }
}
