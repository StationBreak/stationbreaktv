// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.store.FakeStoreEntitlement
import tv.stationbreak.core.domain.store.InMemoryProCache
import tv.stationbreak.core.domain.store.StoreBackedPro

/**
 * The store release guard (#793): the free tier's gates must be closed in the Amazon release build, so v1 cannot ship
 * with Pro unlocked by mistake. This source set is compiled only for the Amazon release variant; the GitHub release
 * is unlocked on purpose (Q-BIZ-1, #119) and has its own test.
 */
class ProReleaseGuardTest {
    @Test
    fun theStoreReleaseShipsWithProLocked() {
        assertFalse("PRO_UNLOCKED must be false in the store release build", BuildConfig.PRO_UNLOCKED)
    }

    // Pro comes only from the store here (#99): with the release's build flag and a store that has not verified a
    // purchase, Pro is locked, and a verified purchase unlocks it. The store is a fake, so this holds for whichever
    // adapter the flavor binds.
    @Test
    fun theStoreReleaseIsLockedUntilTheStoreVerifiesAPurchase() =
        runTest {
            val store = FakeStoreEntitlement(initial = null)
            val scope = backgroundScope + UnconfinedTestDispatcher(testScheduler)
            val pro = StoreBackedPro(BuildConfig.PRO_UNLOCKED, store, InMemoryProCache(), scope)
            assertFalse(pro.pro.value)

            store.purchase()

            assertTrue(pro.pro.value)
        }
}
