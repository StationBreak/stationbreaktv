// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.stationbreak.app.di.ClosedStore
import tv.stationbreak.app.di.StoreModule
import tv.stationbreak.app.di.storeFor
import tv.stationbreak.core.domain.store.FakeStoreEntitlement
import tv.stationbreak.core.domain.store.ProOffer
import tv.stationbreak.core.domain.store.PurchaseOutcome
import tv.stationbreak.core.domain.store.RestoreOutcome

/** #99: the Amazon build offers the purchase screen; PRO_ON_SALE decides if a real store or the closed one is bound. */
class StoreOfferTest {
    @Test
    fun theOfferIsTheStore() {
        assertEquals(ProOffer.Store, StoreModule.proOffer())
    }

    @Test
    fun withTheSwitchOffNothingOpensTheSdkAndTheClosedStoreOwnsNothing() =
        runTest {
            val store = storeFor(onSale = false) { error("the SDK must not be touched while Pro is not on sale") }
            assertSame(ClosedStore, store)
            assertEquals(false, store.verified.value)
            assertEquals("no Pro is shown while the store is unavailable", false, store.available.value)
            assertNull(store.price.value)
            assertEquals(PurchaseOutcome.Failed, store.purchase())
            assertEquals(RestoreOutcome.Failed, store.restore())
        }

    @Test
    fun withTheSwitchOnTheRealStoreIsOpenedOnce() {
        val real = FakeStoreEntitlement()
        var opened = 0
        val store =
            storeFor(onSale = true) {
                opened++
                real
            }
        assertSame(real, store)
        assertEquals(1, opened)
    }
}
