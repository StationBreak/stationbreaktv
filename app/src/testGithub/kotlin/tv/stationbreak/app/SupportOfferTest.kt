// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.app.di.StoreModule
import tv.stationbreak.core.domain.store.ProOffer
import tv.stationbreak.core.domain.store.PurchaseOutcome
import tv.stationbreak.core.domain.store.RestoreOutcome

/** #99: the GitHub build offers a Support link and no purchase: Settings shows the Support page, never Buy. */
class SupportOfferTest {
    @Test
    fun theOfferIsASupportLinkNotAStore() {
        val offer = StoreModule.proOffer()
        assertTrue(offer is ProOffer.Support)
        assertTrue((offer as ProOffer.Support).url.startsWith("https://"))
    }

    @Test
    fun theStoreHereSellsNothing() =
        runTest {
            val store = StoreModule.storeEntitlement()
            assertEquals(false, store.verified.value)
            assertEquals("no Pro is shown while the store is unavailable", false, store.available.value)
            assertNull(store.price.value)
            assertEquals(PurchaseOutcome.Failed, store.purchase())
            assertEquals(RestoreOutcome.Failed, store.restore())
        }
}
