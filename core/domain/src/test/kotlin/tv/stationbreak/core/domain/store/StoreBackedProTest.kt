// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Build unlock OR store entitlement" (#99): every outcome the store can give, and what a refund does. */
class StoreBackedProTest {
    private val store = FakeStoreEntitlement()
    private val cache = InMemoryProCache()

    private fun TestScope.pro(buildUnlocked: Boolean = false) =
        StoreBackedPro(buildUnlocked, store, cache, backgroundScope + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun aBuildThatUnlocksProIsProAndNeverReadsTheStore() =
        runTest {
            val pro = pro(buildUnlocked = true)
            store.revoke()
            assertTrue(pro.pro.value)
            assertEquals(0, cache.writes)
        }

    @Test
    fun aLockedBuildIsLockedUntilTheStoreVerifiesAPurchase() =
        runTest {
            val pro = pro()
            assertFalse(pro.pro.value)

            assertEquals(PurchaseOutcome.Purchased, store.purchase())

            assertTrue(pro.pro.value)
            assertTrue(cache.read(), "the verified answer is kept for an offline start")
        }

    @Test
    fun alreadyOwnedUnlocks() =
        runTest {
            val pro = pro()
            store.next = PurchaseOutcome.AlreadyOwned

            assertEquals(PurchaseOutcome.AlreadyOwned, store.purchase())

            assertTrue(pro.pro.value)
        }

    @Test
    fun cancelFailedAndPendingLeaveItLockedAndChangeNothingElse() =
        runTest {
            val pro = pro()
            for (outcome in listOf(PurchaseOutcome.Cancelled, PurchaseOutcome.Failed, PurchaseOutcome.Pending)) {
                store.next = outcome
                assertEquals(outcome, store.purchase())
                assertFalse(pro.pro.value, "$outcome")
            }
            assertEquals(0, cache.writes, "nothing is written for a purchase that did not verify")
            assertFalse(cache.read())
        }

    @Test
    fun restoreReadsWhatTheAccountOwnsAgain() =
        runTest {
            val pro = pro()
            store.owned = true

            store.restore()

            assertEquals(1, store.restores)
            assertTrue(pro.pro.value)
        }

    @Test
    fun restoreSaysWhatItFound_andOnlyFoundUnlocks() =
        runTest {
            val pro = pro()
            store.owned = false
            assertEquals(RestoreOutcome.None, store.restore())
            assertFalse(pro.pro.value)

            store.restoreFails = true
            assertEquals(RestoreOutcome.Failed, store.restore())
            assertFalse(pro.pro.value)

            store.restoreFails = false
            store.owned = true
            assertEquals(RestoreOutcome.Found, store.restore())
            assertTrue(pro.pro.value)
        }

    @Test
    fun aRefundRelocksAndClearsTheCache_andABuyUnlocksAgain() =
        runTest {
            val pro = pro()
            store.purchase()
            assertTrue(pro.pro.value)

            store.revoke()

            assertFalse(pro.pro.value)
            assertFalse(cache.read(), "a revoke clears the cache, so an offline start is not Pro")

            store.purchase()
            assertTrue(pro.pro.value)
        }

    @Test
    fun anOfflineStartKeepsProFromTheCache_untilTheStoreAnswersFalse() =
        runTest {
            cache.write(true)
            val pro = pro()
            assertTrue(pro.pro.value, "the store has not answered: the cache stands")

            store.owned = false
            store.restore()

            assertFalse(pro.pro.value, "a verified false beats the cache")
            assertFalse(cache.read())
        }

    @Test
    fun anOwedRefundMeansTheCacheNeverGrantsPro_untilTheStoreVerifiesAPurchase() =
        runTest {
            // A kill between writing the owed refund and clearing the owned answer (#1067).
            cache.writeRefundPending(true)
            cache.write(true)
            val pro = pro()
            assertFalse(pro.pro.value, "an owed refund beats a cached owned")
            assertTrue(pro.revoked.value)

            store.purchase()

            assertTrue(pro.pro.value, "a verified purchase still unlocks")
            assertFalse(pro.revoked.value)
        }

    @Test
    fun anUnknownRefundRecordGrantsNothingFromTheCache_andIsNoRefund_untilTheStoreSaysTrue() =
        runTest {
            val unknown = InMemoryProCache(held = true, refundPending = null)
            val pro = StoreBackedPro(false, store, unknown, backgroundScope + UnconfinedTestDispatcher(testScheduler))
            assertFalse(pro.pro.value, "no offline Pro while the refund is unknown")
            assertFalse(pro.revoked.value, "an unknown is not a refund")

            store.purchase()

            assertTrue(pro.pro.value)
            assertFalse(pro.revoked.value)
            assertEquals(false, unknown.readRefundPending(), "a verified answer settles it")
        }

    @Test
    fun anUnknownRefundRecordThenAVerifiedFalseIsARefund() =
        runTest {
            val unknown = InMemoryProCache(held = true, refundPending = null)
            val pro = StoreBackedPro(false, store, unknown, backgroundScope + UnconfinedTestDispatcher(testScheduler))

            store.restore()

            assertFalse(pro.pro.value)
            assertTrue(pro.revoked.value)
            assertEquals(true, unknown.readRefundPending())
        }

    @Test
    fun aVerifiedFalseBeforeTheCacheIsReadIsNotOverwrittenByIt() =
        runTest {
            cache.write(true)
            val verifiedFalse = FakeStoreEntitlement(initial = false)

            val scope = backgroundScope + UnconfinedTestDispatcher(testScheduler)
            val pro = StoreBackedPro(false, verifiedFalse, cache, scope)

            assertFalse(pro.pro.value)
        }

    @Test
    fun aRefundIsOnlyTheChangeFromOwnedToAVerifiedFalse() =
        runTest {
            val never = pro()
            store.restore()
            assertFalse(never.pro.value)
            assertFalse(never.revoked.value, "a TV that never held Pro is never refunded")
        }

    @Test
    fun aRefundSetsRevoked_andBuyingAgainClearsIt() =
        runTest {
            val pro = pro()
            store.purchase()
            assertFalse(pro.revoked.value)

            store.revoke()
            assertTrue(pro.revoked.value)

            store.purchase()
            assertFalse(pro.revoked.value)
        }

    @Test
    fun aCachedOwnedFollowedByAVerifiedFalseIsARefund() =
        runTest {
            cache.write(true)
            val pro = pro()

            store.restore()

            assertTrue(pro.revoked.value)
        }

    // A new provider over the same cache, with a store that has not answered: the app after a kill, started offline.
    private fun TestScope.restarted(offline: FakeStoreEntitlement = FakeStoreEntitlement()) =
        StoreBackedPro(false, offline, cache, backgroundScope + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun aRefundSeenBeforeAKillIsStillOwedItsResetAtTheNextStart() =
        runTest {
            pro()
            store.purchase()
            store.revoke()
            assertEquals(true, cache.readRefundPending(), "kept before anything else")

            val next = restarted()

            assertTrue(next.revoked.value)
            assertFalse(next.pro.value)
        }

    @Test
    fun aPurchaseAfterTheRefundClearsTheOwedReset() =
        runTest {
            pro()
            store.purchase()
            store.revoke()
            val offline = FakeStoreEntitlement()
            val next = restarted(offline)

            next.refundHandled("a")
            offline.purchase()

            assertFalse(next.revoked.value)
            assertEquals(false, cache.readRefundPending())
            assertTrue(cache.readRefundHandled().isEmpty(), "the handled accounts end with the refund")
            assertFalse(restarted().revoked.value)
        }

    @Test
    fun aHandledAccountIsKeptAcrossStarts_whileTheRefundStandsForEveryOther() =
        runTest {
            val pro = pro()
            store.purchase()
            store.revoke()

            pro.refundHandled("a")

            val next = restarted()
            assertTrue(next.revoked.value, "another account that signs in later is still reset")
            assertTrue(next.isRefundHandled("a"))
            assertFalse(next.isRefundHandled("b"))
        }

    @Test
    fun aBuildThatUnlocksProIsNeverRevoked() =
        runTest {
            val pro = pro(buildUnlocked = true)
            store.purchase()
            store.revoke()
            assertFalse(pro.revoked.value)
        }

    @Test
    @Suppress("InjectDispatcher") // A real multi-threaded dispatcher: the state must be right off the test thread.
    fun theStateFollowsTheStoreOnARealMultiThreadedDispatcher() =
        runBlocking<Unit> {
            val job = Job()
            val pro = StoreBackedPro(false, store, cache, CoroutineScope(Dispatchers.Default + job))

            store.purchase()
            withTimeout(TIMEOUT_MS) { pro.pro.first { it } }
            store.revoke()
            withTimeout(TIMEOUT_MS) { pro.revoked.first { it } }
            assertFalse(pro.pro.value)
            job.cancel()
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
