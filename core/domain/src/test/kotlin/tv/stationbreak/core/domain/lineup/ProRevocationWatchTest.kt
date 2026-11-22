// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.store.FakeStoreEntitlement
import tv.stationbreak.core.domain.store.InMemoryProCache
import tv.stationbreak.core.domain.store.StoreBackedPro
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.rules.Source
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** What watches a refund (#99): only a verified change from owned to not owned acts, and a purchase stops it. */
@Suppress("InjectDispatcher") // The watcher runs on real threads here, as in the app.
class ProRevocationWatchTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, { }, TestCatalog)
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val pro = FakeProEntitlement(initial = false)

    @AfterTest
    fun stop() = job.cancel()

    private suspend fun seedMarked() {
        val touched = starter("whodunit", 210, ChannelState.Shelf, rules("W", Source.Genre("Crime")))
        val base = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
        store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(base, touched.copy(viewerChoice = ViewerChoice.Touched))))
    }

    private fun marked(): Boolean {
        val bytes = checkNotNull(store.inner.peek(ACCOUNT))
        val lineup: Lineup = checkNotNull((LineupCodec.decode(bytes) as? LineupCodec.Decoded.Loaded)?.lineup)
        return lineup.channels.any { it.chosenByViewer }
    }

    private suspend fun untilReset() = withTimeout(TIMEOUT_MS) { while (marked()) delay(POLL_MS) }

    private fun watcher(libraries: suspend () -> Set<tv.stationbreak.core.model.ItemId>? = { LIBRARIES }) =
        ProRevocation(service, libraries).watch(scope, pro, library)

    @Test
    fun aVerifiedRefundResetsTheActiveAccountsStarters() =
        runBlocking<Unit> {
            seedMarked()
            library.useAccount(ACCOUNT)
            watcher()

            pro.refund()

            untilReset()
        }

    @Test
    fun withNoActiveAccountItWaits_andActsWhenOneComes() =
        runBlocking<Unit> {
            seedMarked()
            watcher()
            pro.refund()
            delay(SETTLE_MS)
            assertTrue(marked(), "no account: nothing to reset")

            library.useAccount(ACCOUNT)

            untilReset()
        }

    @Test
    fun aFreeTvThatNeverHeldProIsNeverReset_evenWithMarksAndAFalseFromTheStore() =
        runBlocking<Unit> {
            seedMarked()
            library.useAccount(ACCOUNT)
            // The Amazon stand-in answers false from the start, and the cache never held true.
            val real = StoreBackedPro(false, FakeStoreEntitlement(initial = false), InMemoryProCache(false), scope)
            ProRevocation(service) { LIBRARIES }.watch(scope, real, library)

            delay(SETTLE_MS)

            assertFalse(real.pro.value)
            assertFalse(real.revoked.value)
            assertTrue(marked())
        }

    @Test
    fun aPurchaseThatLandsMidResetStopsItBeforeItSaves() =
        runBlocking<Unit> {
            seedMarked()
            library.useAccount(ACCOUNT)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            watcher {
                entered.complete(Unit)
                release.await()
                LIBRARIES
            }
            pro.refund()
            withTimeout(TIMEOUT_MS) { entered.await() }

            pro.set(true)
            release.complete(Unit)
            delay(SETTLE_MS)

            assertTrue(marked(), "the reset was cancelled: Pro is back")
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 400L
    }
}
