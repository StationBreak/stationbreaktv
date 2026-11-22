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
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.StoredBytes
import tv.stationbreak.core.domain.store.FakeStoreEntitlement
import tv.stationbreak.core.domain.store.InMemoryProCache
import tv.stationbreak.core.domain.store.StoreBackedPro
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.UserId
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

    private suspend fun seedMarked(account: LibraryAccount = ACCOUNT) {
        val touched = starter("whodunit", 210, ChannelState.Shelf, rules("W", Source.Genre("Crime")))
        val base = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
        val lineup = lineupOf(base, touched.copy(viewerChoice = ViewerChoice.Touched))
        val owned = lineup.copy(userId = CanonicalId.parse(account.user.value))
        store.inner.save(account, LineupCodec.encode(owned))
    }

    private fun marked(account: LibraryAccount = ACCOUNT): Boolean {
        val bytes = checkNotNull(store.inner.peek(account))
        val lineup: Lineup = checkNotNull((LineupCodec.decode(bytes) as? LineupCodec.Decoded.Loaded)?.lineup)
        return lineup.channels.any { it.chosenByViewer }
    }

    private suspend fun untilReset(account: LibraryAccount = ACCOUNT) =
        withTimeout(TIMEOUT_MS) {
            while (marked(account)) delay(POLL_MS)
        }

    private suspend fun untilHandled(
        cache: InMemoryProCache,
        count: Int,
    ) = withTimeout(TIMEOUT_MS) { while (cache.readRefundHandled().size < count) delay(POLL_MS) }

    // One start of the app: a new service over the same lineup files, a new provider over the same Pro cache.
    private fun start(
        cache: InMemoryProCache,
        files: LineupStore = store,
        on: CoroutineScope = scope,
    ): FakeStoreEntitlement {
        val offline = FakeStoreEntitlement()
        val restarted = StoreBackedPro(false, offline, cache, on)
        val fresh = LineupService(files, library, clock, { 0 }, { }, TestCatalog)
        ProRevocation(fresh) { LIBRARIES }.watch(on, restarted, library)
        return offline
    }

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

    @Test
    fun aRefundOwedFromBeforeAKillIsResetAtStart_andTheAccountIsHandled() =
        runBlocking<Unit> {
            seedMarked()
            library.useAccount(ACCOUNT)
            // What the app left on disk: the refund was verified, the reset never ran. The store has not answered yet.
            val cache = InMemoryProCache(held = false, refundPending = true)
            start(cache)

            untilReset()

            untilHandled(cache, 1)
            assertTrue(cache.readRefundPending(), "only a purchase ends the refund")
        }

    @Test
    fun aLineupThatCouldNotBeReadKeepsTheRefundOwed_andTheNextGoodStartResets() =
        runBlocking<Unit> {
            seedMarked()
            library.useAccount(ACCOUNT)
            val cache = InMemoryProCache(held = false, refundPending = true)
            val failsOnce =
                object : LineupStore by store {
                    var failed = false

                    override suspend fun load(account: LibraryAccount): StoredBytes =
                        if (failed) store.load(account) else StoredBytes.Failed.also { failed = true }
                }
            val firstRun = Job()
            start(cache, failsOnce, CoroutineScope(Dispatchers.Default + firstRun))
            delay(SETTLE_MS)
            firstRun.cancel()

            assertTrue(marked())
            assertTrue(cache.readRefundHandled().isEmpty(), "a lineup not read is never judged clean")

            start(cache)

            untilReset()
            untilHandled(cache, 1)
        }

    @Test
    fun eachAccountIsResetOnceForARefund_acrossStarts_andAPurchaseEndsIt() =
        runBlocking<Unit> {
            val other = LibraryAccount(ACCOUNT.server, UserId(id(99).value))
            seedMarked()
            seedMarked(other)
            library.useAccount(ACCOUNT)
            val cache = InMemoryProCache(held = false, refundPending = true)
            val firstRun = Job()
            start(cache, on = CoroutineScope(Dispatchers.Default + firstRun))
            untilReset()
            untilHandled(cache, 1)
            firstRun.cancel()

            // A later start: the first account is marked again (as if by Pro before the refund) and is not reset twice.
            seedMarked()
            val store2 = start(cache)
            delay(SETTLE_MS)
            assertTrue(marked(), "already handled for this refund")

            library.useAccount(other)
            untilReset(other)
            untilHandled(cache, 2)

            store2.purchase()
            withTimeout(TIMEOUT_MS) { while (cache.readRefundPending()) delay(POLL_MS) }
            assertTrue(cache.readRefundHandled().isEmpty())
        }

    @Test
    fun aResetThatCannotRunYetStaysOwed() =
        runBlocking<Unit> {
            seedMarked()
            library.useAccount(ACCOUNT)
            val cache = InMemoryProCache(held = false, refundPending = true)
            val restarted = StoreBackedPro(false, FakeStoreEntitlement(), cache, scope)
            ProRevocation(service) { null }.watch(scope, restarted, library)

            delay(SETTLE_MS)

            assertTrue(marked())
            assertTrue(cache.readRefundHandled().isEmpty(), "no libraries known: asked again at the next start")
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 400L
    }
}
