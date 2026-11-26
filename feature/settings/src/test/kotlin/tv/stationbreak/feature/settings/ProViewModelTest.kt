// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.stationbreak.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ProEntitlement
import tv.stationbreak.core.domain.kids.FakeKidsLock
import tv.stationbreak.core.domain.store.FakeStoreEntitlement
import tv.stationbreak.core.domain.store.InMemoryProCache
import tv.stationbreak.core.domain.store.ProOffer
import tv.stationbreak.core.domain.store.PurchaseOutcome
import tv.stationbreak.core.domain.store.StoreBackedPro

/** #99: the Pro purchase screen's states over fakes of the store port: no store SDK, no network, no purchase. */
class ProViewModelTest {
    private val store = FakeStoreEntitlement(initial = false)
    private val kids = FakeKidsLock()

    private class Rig(
        val vm: ProViewModel,
        val pro: ProEntitlement,
    ) {
        val now get() = vm.state.value
    }

    // Pro follows the store's verified answer, as in the Amazon release.
    private fun TestScope.rig(
        offer: ProOffer = ProOffer.Store,
        gate: FakeStoreEntitlement = store,
    ): Rig {
        val scope = backgroundScope + UnconfinedTestDispatcher(testScheduler)
        val pro = StoreBackedPro(false, gate, InMemoryProCache(), scope)
        val vm = ProViewModel(gate, pro, kids, offer, StandardTestDispatcher(testScheduler))
        runCurrent()
        return Rig(vm, pro)
    }

    private fun TestScope.press(
        rig: Rig,
        down: Boolean = false,
    ) {
        if (down) rig.vm.move(up = false)
        rig.vm.select()
        runCurrent()
    }

    @Test
    fun itOpensOnBuy_showingTheStoresPrice_andNothingSaidYet() =
        runTest {
            val rig = rig()
            assertEquals(ProChoice.Buy, rig.now.focus)
            assertEquals("$19.99", rig.now.price)
            assertNull(rig.now.result)
            assertTrue(rig.now.actionable)
        }

    @Test
    fun noPriceFromTheStoreIsShownAsNone_neverMadeUp() =
        runTest {
            store.price.value = null
            assertNull(rig().now.price)
        }

    @Test
    fun buySuccess_turnsProOn_andSaysSo() =
        runTest {
            val rig = rig()
            press(rig)
            assertEquals(ProResult.Bought, rig.now.result)
            assertTrue(rig.pro.pro.value)
            assertTrue("owned: no Buy any more", rig.now.owned)
            assertFalse(rig.now.actionable)
        }

    @Test
    fun alreadyOwned_unlocksAndSaysItWasNotCharged() =
        runTest {
            store.next = PurchaseOutcome.AlreadyOwned
            val rig = rig()
            press(rig)
            assertEquals(ProResult.AlreadyOwned, rig.now.result)
            assertTrue(rig.pro.pro.value)
        }

    @Test
    fun cancelledAndFailed_leaveProOff_andBuyStays_andEachIsSaid() =
        runTest {
            val rig = rig()
            val said =
                listOf(
                    PurchaseOutcome.Cancelled to ProResult.Cancelled,
                    PurchaseOutcome.Failed to ProResult.Failed,
                ).map { (outcome, result) ->
                    store.next = outcome
                    press(rig)
                    assertFalse("$outcome", rig.pro.pro.value)
                    assertTrue("Buy stays", rig.now.actionable)
                    rig.now.result to result
                }
            said.forEach { (got, want) -> assertEquals(want, got) }
        }

    @Test
    fun restoreFound_unlocks_restoreNone_andRestoreFailed_doNot() =
        runTest {
            val rig = rig()
            store.owned = false
            press(rig, down = true)
            assertEquals(ProResult.NothingToRestore, rig.now.result)
            assertFalse(rig.pro.pro.value)

            store.restoreFails = true
            press(rig)
            assertEquals(ProResult.RestoreFailed, rig.now.result)

            store.restoreFails = false
            store.owned = true
            press(rig)
            assertEquals(ProResult.Restored, rig.now.result)
            assertTrue(rig.pro.pro.value)
            assertEquals(3, store.restores)
        }

    @Test
    fun ownedFromTheStartHidesBuyAndRestore_andSaysPro() =
        runTest {
            val owned = FakeStoreEntitlement(initial = true)
            val rig = rig(gate = owned)
            assertTrue(rig.now.owned)
            assertFalse(rig.now.actionable)
            rig.vm.select()
            runCurrent()
            assertEquals("a press does nothing with Pro owned", 0, owned.restores)
            assertNull(rig.now.result)
        }

    @Test
    fun aSecondPressWhileTheStoreIsAsked_startsNothingMore() =
        runTest {
            val held = CompletableDeferred<Unit>()
            store.purchaseHold = held
            val rig = rig()
            press(rig)
            assertTrue(rig.now.busy)
            press(rig)
            held.complete(Unit)
            runCurrent()
            assertEquals(1, store.purchases)
            assertFalse(rig.now.busy)
            assertEquals(ProResult.Bought, rig.now.result)
        }

    @Test
    fun aThrowFromTheStoreIsOneFailure_withNoMessageKept() =
        runTest {
            store.purchaseThrows = true
            val rig = rig()
            press(rig)
            assertEquals(ProResult.Failed, rig.now.result)
            assertFalse(rig.now.busy)
        }

    @Test
    fun aSuccessIsSaidAsConfirming_untilProIsOwned() {
        assertEquals(ProResult.Confirming, said(ProResult.Bought, owned = false))
        assertEquals(ProResult.Confirming, said(ProResult.Restored, owned = false))
        assertEquals(ProResult.AlreadyOwned, said(ProResult.AlreadyOwned, owned = true))
        assertNull("a Pending never shows under the owned layout", said(ProResult.Pending, owned = true))
    }

    @Test
    fun pendingThenTheStoreSettles_clearsThePendingLine_underTheOwnedLayout() =
        runTest {
            store.next = PurchaseOutcome.Pending
            val rig = rig()
            press(rig)
            assertEquals(ProResult.Pending, rig.now.result)
            store.settle(true)
            runCurrent()
            assertTrue(rig.now.owned)
            assertNull(rig.now.result)
        }

    @Test
    fun boughtThenARefund_bringsBackBuy_withNoThankYou() =
        runTest {
            val rig = rig()
            press(rig)
            assertEquals(ProResult.Bought, rig.now.result)
            store.revoke()
            runCurrent()
            assertFalse(rig.pro.pro.value)
            assertFalse(rig.now.owned)
            assertTrue("Buy is back", rig.now.actionable)
            assertNull(rig.now.result)
        }

    @Test
    fun pendingKeepsBuyOff_untilTheStoresAnswerChanges() =
        runTest {
            store.next = PurchaseOutcome.Pending
            val rig = rig()
            press(rig)
            assertEquals(ProResult.Pending, rig.now.result)
            assertTrue(rig.now.held)
            assertFalse("Buy is off", rig.now.canBuy)
            assertTrue("Restore stays", rig.now.actionable)
            assertEquals(ProChoice.Restore, rig.now.focus)
            rig.vm.move(up = true)
            press(rig)
            assertEquals("a second purchase was not started", 1, store.purchases)
            assertEquals("Restore ran instead", 1, store.restores)

            store.settle(false)
            runCurrent()
            assertTrue("a lapse with no change of answer is still held", rig.now.held)
            store.settle(true)
            runCurrent()
            assertFalse(rig.now.held)
        }

    @Test
    fun restoreWhilePending_findsThePurchase_andEndsThePendingAsOwned() =
        runTest {
            store.next = PurchaseOutcome.Pending
            val rig = rig()
            press(rig)
            assertTrue(rig.now.held)
            store.owned = true
            press(rig)
            assertTrue(rig.now.owned)
            assertFalse(rig.now.held)
            assertEquals(ProResult.Restored, rig.now.result)
        }

    @Test
    fun pendingIsForgottenWhenTheViewerLeavesAndComesBack() =
        runTest {
            store.next = PurchaseOutcome.Pending
            val rig = rig()
            press(rig)
            assertFalse(rig.now.canBuy)
            rig.vm.open()
            runCurrent()
            assertTrue(rig.now.canBuy)
            assertNull(rig.now.result)
        }

    @Test
    fun boughtBeforeTheStoreVerifies_isConfirming_butBuyAndRestoreStayUsable_untilProIsOwned() =
        runTest {
            store.purchaseVerifies = false
            val rig = rig()
            press(rig)
            assertEquals(ProResult.Confirming, rig.now.result)
            assertFalse(rig.now.owned)
            assertTrue("Buy and Restore stay offered while confirming (#beta.3 race)", rig.now.actionable)
            assertTrue(rig.now.canBuy)
            press(rig, down = true)
            assertEquals("Restore ran", 1, store.restores)

            store.settle(true)
            runCurrent()
            assertTrue(rig.now.owned)
        }

    @Test
    fun aSuccessWhileProIsFalse_doesNotHideBuyAndRestore() =
        runTest {
            store.purchaseVerifies = false
            val rig = rig()
            press(rig)
            assertEquals(ProResult.Confirming, rig.now.result)
            press(rig)
            assertEquals("Buy can be pressed again", 2, store.purchases)
        }

    @Test
    fun confirmingEndsWhenTheViewerLeavesAndComesBack() =
        runTest {
            store.purchaseVerifies = false
            val rig = rig()
            press(rig)
            rig.vm.open()
            runCurrent()
            assertTrue(rig.now.actionable)
            assertNull(rig.now.result)
        }

    @Test
    fun theAnswerMovingNullToFalseDuringAPurchase_doesNotLeaveItHeld() =
        runTest {
            val unknown = FakeStoreEntitlement(initial = null)
            unknown.next = PurchaseOutcome.Pending
            val hold = CompletableDeferred<Unit>()
            unknown.purchaseHold = hold
            val rig = rig(gate = unknown)
            press(rig)
            assertTrue(rig.now.busy)
            unknown.settle(false)
            runCurrent()
            hold.complete(Unit)
            runCurrent()
            assertFalse(rig.now.busy)
            assertFalse("the answer moved under the call: nothing to wait on", rig.now.held)
            assertTrue(rig.now.canBuy)
            assertNull("no stale Pending line", rig.now.result)
        }

    @Test
    fun theAnswerMovingDuringAPurchase_thatReturnsPending_leavesNoStalePending_afterARefund() =
        runTest {
            val hold = CompletableDeferred<Unit>()
            store.next = PurchaseOutcome.Pending
            store.purchaseHold = hold
            val rig = rig()
            press(rig)
            store.settle(true)
            runCurrent()
            hold.complete(Unit)
            runCurrent()
            assertFalse(rig.now.held)
            assertNull(rig.now.result)
            store.revoke()
            runCurrent()
            assertFalse(rig.now.held)
            assertTrue(rig.now.canBuy)
            assertNull("Pending does not come back with the refund", rig.now.result)
        }

    @Test
    fun aHoldEndedByTheAnswerMoving_takesThePendingLineWithIt_evenWhenNotOwned() =
        runTest {
            val unknown = FakeStoreEntitlement(initial = null)
            unknown.next = PurchaseOutcome.Pending
            val rig = rig(gate = unknown)
            press(rig)
            assertTrue(rig.now.held)
            assertEquals(ProResult.Pending, rig.now.result)
            unknown.settle(false)
            runCurrent()
            assertFalse(rig.now.held)
            assertNull("no Pending line under a live Buy", rig.now.result)
            assertTrue(rig.now.canBuy)
        }

    @Test
    fun kidsMode_startsNoPurchase_andNoRestore() =
        runTest {
            kids.lock()
            val rig = rig()
            assertTrue(rig.now.locked)
            press(rig)
            press(rig, down = true)
            assertEquals(0, store.restores)
            assertNull(rig.now.result)
            assertTrue("no purchase was made", store.verified.value == false)
        }

    @Test
    fun theGithubBuild_showsSupport_andNoActionStartsAPurchase() =
        runTest {
            val rig = rig(offer = ProOffer.Support("https://example.test/support"))
            assertFalse(rig.now.actionable)
            press(rig)
            press(rig, down = true)
            assertEquals(0, store.restores)
            assertEquals(0, store.purchases)
            assertNull(rig.now.result)
        }
}
