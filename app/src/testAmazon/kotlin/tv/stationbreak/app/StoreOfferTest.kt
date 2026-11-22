// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.app.di.StoreModule
import tv.stationbreak.core.domain.store.ProOffer
import tv.stationbreak.core.domain.store.PurchaseOutcome
import tv.stationbreak.core.domain.store.RestoreOutcome

/** #99: the Amazon build offers the purchase screen, over a stand-in store that is "not available" until the SDK. */
class StoreOfferTest {
    @Test
    fun theOfferIsTheStore() {
        assertEquals(ProOffer.Store, StoreModule.proOffer())
    }

    @Test
    fun theStandInOwnsNothingHasNoPriceAndFailsEveryAsk() =
        runTest {
            val store = StoreModule.storeEntitlement()
            assertEquals(false, store.verified.value)
            assertNull(store.price.value)
            assertEquals(PurchaseOutcome.Failed, store.purchase())
            assertEquals(RestoreOutcome.Failed, store.restore())
        }
}
