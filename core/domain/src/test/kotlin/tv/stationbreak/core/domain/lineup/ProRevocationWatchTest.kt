// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LineupStore
import tv.stationbreak.core.domain.ProEntitlement
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

    // A watcher has begun when it has read the active account: a negative test waits for this, not for the clock.
    private var watching = CompletableDeferred<Unit>()

    private fun observed(): LibraryRepository {
        watching = CompletableDeferred()
        val seen = watching
        return object : LibraryRepository by library {
            override fun activeAccount(): Flow<LibraryAccount?> = library.activeAccount().onEach { seen.complete(Unit) }
        }
    }

    private suspend fun untilWatching() = withTimeout(TIMEOUT_MS) { watching.await() }

    // A store that says when the provider has collected its answers: its start-up decisions are made by then.
    private class WatchedStore(
        initial: Boolean? = null,
    ) : FakeStoreEntitlement(initial) {
        val collected = CompletableDeferred<Unit>()
        private val real = super.verified

        override val verified: StateFlow<Boolean?> =
            object : StateFlow<Boolean?> by real {
                override suspend fun collect(collector: FlowCollector<Boolean?>): Nothing {
                    collected.complete(Unit)
                    real.collect(collector)
                }
            }
    }

    // The watcher's own question "is this account done for the refund?", and the answer it was given.
    private var handledCheck = CompletableDeferred<Boolean>()
    private var started: StoreBackedPro? = null

    // The watcher has finished with the account it was on: a second account (with no lineup) makes it ask about that
    // one, and it only does so after the first one's work is done or cancelled. Nothing is written for the second.
    private suspend fun afterTheWatcherIsDone() {
        handledCheck = CompletableDeferred()
        library.useAccount(LibraryAccount(ACCOUNT.server, UserId(id(98).value)))
        withTimeout(TIMEOUT_MS) { handledCheck.await() }
    }

    private suspend fun untilDecided(store: WatchedStore) = withTimeout(TIMEOUT_MS) { store.collected.await() }

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
        libraries: suspend () -> Set<tv.stationbreak.core.model.ItemId>? = { LIBRARIES },
    ): WatchedStore {
        val offline = WatchedStore()
        val restarted = StoreBackedPro(false, offline, cache, on)
        started = restarted
        handledCheck = CompletableDeferred()
        val probed =
            object : ProEntitlement by restarted {
                override suspend fun isRefundHandled(owner: String): Boolean =
                    restarted.isRefundHandled(owner).also { handledCheck.complete(it) }
            }
        val fresh = LineupService(files, library, clock, { 0 }, { }, TestCatalog)
        ProRevocation(fresh, libraries).watch(on, probed, observed())
        return offline
    }

    private fun watcher(libraries: suspend () -> Set<tv.stationbreak.core.model.ItemId>? = { LIBRARIES }) =
        ProRevocation(service, libraries).watch(scope, pro, observed())

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
            untilWatching()
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
            val answers = WatchedStore(initial = false)
            val cache = InMemoryProCache(false)
            val real = StoreBackedPro(false, answers, cache, scope)
            ProRevocation(service) { LIBRARIES }.watch(scope, real, observed())

            // The store's false is taken in once it is written to the cache, the last thing the provider does with it.
            untilDecided(answers)
            withTimeout(TIMEOUT_MS) { while (cache.writes < 1) delay(POLL_MS) }

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
            val cancelled = CompletableDeferred<Unit>()
            watcher {
                entered.complete(Unit)
                try {
                    release.await()
                } catch (c: CancellationException) {
                    cancelled.complete(Unit)
                    throw c
                }
                LIBRARIES
            }
            pro.refund()
            withTimeout(TIMEOUT_MS) { entered.await() }

            pro.set(true)
            withTimeout(TIMEOUT_MS) { cancelled.await() }
            release.complete(Unit)

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
            assertEquals(true, cache.readRefundPending(), "only a purchase ends the refund")
        }

    // A corrupt or unreadable refund record on a paying TV (#1067): no reset until the store gives a verified false.
    @Test
    fun anUnknownRefundNeverResets_andAVerifiedPurchaseEndsIt() =
        runBlocking<Unit> {
            seedMarked()
            library.useAccount(ACCOUNT)
            val cache = InMemoryProCache(held = true, refundPending = null)
            val offline = start(cache)
            untilDecided(offline)

            assertFalse(checkNotNull(started).revoked.value, "an unknown refund is not a refund")
            assertTrue(marked(), "an unknown refund is not a refund")

            offline.purchase()
            withTimeout(TIMEOUT_MS) { while (cache.readRefundPending() != false) delay(POLL_MS) }
            assertTrue(marked())
        }

    @Test
    fun anUnknownRefundThenAVerifiedFalseResets() =
        runBlocking<Unit> {
            seedMarked()
            library.useAccount(ACCOUNT)
            val cache = InMemoryProCache(held = true, refundPending = null)
            val offline = start(cache)
            untilDecided(offline)
            assertFalse(checkNotNull(started).revoked.value)
            assertTrue(marked())

            offline.revoke()

            untilReset()
            assertEquals(true, cache.readRefundPending())
        }

    @Test
    fun aLineupThatCouldNotBeReadKeepsTheRefundOwed_andTheNextGoodStartResets() =
        runBlocking<Unit> {
            seedMarked()
            library.useAccount(ACCOUNT)
            val cache = InMemoryProCache(held = false, refundPending = true)
            val failsOnce =
                object : LineupStore by store {
                    @Volatile var failed = false

                    override suspend fun load(account: LibraryAccount): StoredBytes =
                        if (failed) store.load(account) else StoredBytes.Failed.also { failed = true }
                }
            val firstRun = Job()
            start(cache, failsOnce, CoroutineScope(Dispatchers.Default + firstRun))
            withTimeout(TIMEOUT_MS) { while (!failsOnce.failed) delay(POLL_MS) }
            afterTheWatcherIsDone()
            firstRun.cancel()

            assertTrue(marked())
            assertTrue(cache.readRefundHandled().isEmpty(), "a lineup not read is never judged clean")
            library.useAccount(ACCOUNT)

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
            // The watcher's own answer to "is this account done?" is the decision; it does nothing after a yes.
            assertTrue(withTimeout(TIMEOUT_MS) { handledCheck.await() }, "already handled for this refund")
            assertTrue(marked(), "already handled for this refund")

            library.useAccount(other)
            untilReset(other)
            untilHandled(cache, 2)

            store2.purchase()
            withTimeout(TIMEOUT_MS) { while (cache.readRefundPending() != false) delay(POLL_MS) }
            assertTrue(cache.readRefundHandled().isEmpty())
        }

    @Test
    fun aResetThatCannotRunYetStaysOwed() =
        runBlocking<Unit> {
            seedMarked()
            library.useAccount(ACCOUNT)
            val cache = InMemoryProCache(held = false, refundPending = true)
            val asked = CompletableDeferred<Unit>()
            start(
                cache,
                libraries = {
                    asked.complete(Unit)
                    null
                },
            )

            withTimeout(TIMEOUT_MS) { asked.await() }
            afterTheWatcherIsDone()

            assertTrue(marked())
            assertTrue(cache.readRefundHandled().isEmpty(), "no libraries known: asked again at the next start")
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
