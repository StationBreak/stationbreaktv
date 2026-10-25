// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Single flight: callers that miss the cache together share one build of the days. */
class BuiltInScheduleSourceFlightTest {
    /** A library whose snapshot reads wait for [gate] and can be made to fail. */
    private class GatedLibrary(
        val inner: CountingLibrary,
    ) : LibraryRepository by inner {
        val gate = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val entered = CompletableDeferred<Unit>()

        @Volatile var failNext = false

        @Volatile var cancelNext = false

        override suspend fun ruleSnapshot(
            account: LibraryAccount,
            libraries: Set<ItemId>,
            cutoff: Instant,
        ): RuleSnapshot {
            reads.incrementAndGet()
            entered.complete(Unit)
            gate.await()
            check(!failNext) { "read failed" }
            if (cancelNext) throw CancellationException("the build was cancelled")
            return inner.ruleSnapshot(account, libraries, cutoff)
        }
    }

    private val counting = CountingLibrary()
    private val library = GatedLibrary(counting)
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val source = BuiltInScheduleSource(service, library) { LIBRARIES }

    init {
        runBlocking { counting.delegate.useAccount(ACCOUNT) }
        counting.seed(fillingLibrary())
        val laughs = starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy")))
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineupOf(laughs))) }
    }

    @Test
    fun concurrentCallersShareOneBuild() =
        runBlocking {
            // Undispatched: each caller runs on to its await of the build before the next one starts, so all five
            // have missed the cache and joined (or started) the build by the time the read is let through.
            val callers =
                (1..5).map {
                    async(start = CoroutineStart.UNDISPATCHED) { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
                }
            withTimeout(WAIT_MS) { library.entered.await() }
            library.gate.complete(Unit)
            val results = callers.map { it.await() }

            assertEquals(1, library.reads.get(), "one build, so one snapshot read for the one cut-off")
            assertTrue(results.all { it === results.first() }, "every caller gets the one built list")
        }

    @Test
    fun aCancelledCallerLeavesTheBuildCompleteForTheOthers() =
        runBlocking {
            val first = async(start = CoroutineStart.UNDISPATCHED) { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
            withTimeout(WAIT_MS) { library.entered.await() }
            first.cancelAndJoin()
            library.gate.complete(Unit)

            assertEquals(1, second.await().size)
            // Nothing is read again: the build finished and was kept.
            source.channelDays(ACCOUNT, LIBRARIES, NOON)
            assertEquals(1, library.reads.get())
        }

    @Test
    fun aBuildThatFailsIsNotKept() =
        runBlocking {
            library.failNext = true
            library.gate.complete(Unit)
            assertFailsWith<IllegalStateException> { source.channelDays(ACCOUNT, LIBRARIES, NOON) }

            library.failNext = false
            assertEquals(1, source.channelDays(ACCOUNT, LIBRARIES, NOON).size)
            assertEquals(2, library.reads.get(), "the second call built again")
        }

    @Test
    fun aBuildCancelledUnderAnActiveCallerIsAFailureNotTheCallersCancellation() =
        runBlocking {
            library.cancelNext = true
            library.gate.complete(Unit)
            val failure = assertFailsWith<IllegalStateException> { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
            assertTrue(failure.cause is CancellationException)
            // And it is not kept either.
            library.cancelNext = false
            assertEquals(1, source.channelDays(ACCOUNT, LIBRARIES, NOON).size)
        }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
