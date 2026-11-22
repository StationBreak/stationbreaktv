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
    fun cancelledFailedAndPending_leaveProOff_andEachIsSaid() =
        runTest {
            val rig = rig()
            val said =
                listOf(
                    PurchaseOutcome.Cancelled to ProResult.Cancelled,
                    PurchaseOutcome.Failed to ProResult.Failed,
                    PurchaseOutcome.Pending to ProResult.Pending,
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
            val slow =
                object : FakeStoreEntitlement(initial = false) {
                    override suspend fun purchase(): PurchaseOutcome {
                        held.await()
                        return super.purchase()
                    }
                }
            val rig = rig(gate = slow)
            press(rig)
            assertTrue(rig.now.busy)
            press(rig)
            held.complete(Unit)
            runCurrent()
            assertEquals(1, slow.purchases)
            assertFalse(rig.now.busy)
            assertEquals(ProResult.Bought, rig.now.result)
        }

    @Test
    fun aThrowFromTheStoreIsOneFailure_withNoMessageKept() =
        runTest {
            val throwing =
                object : FakeStoreEntitlement(initial = false) {
                    override suspend fun purchase(): PurchaseOutcome = error("receipt 123 for user 9")
                }
            val rig = rig(gate = throwing)
            press(rig)
            assertEquals(ProResult.Failed, rig.now.result)
            assertFalse(rig.now.busy)
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
