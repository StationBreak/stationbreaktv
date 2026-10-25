// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import tv.stationbreak.core.model.RuleSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** The snapshot cache's own rules: who keeps a read alive, which read a newer state replaces, what is kept. */
class SnapshotCacheTest {
    @Suppress("InjectDispatcher") // A test's own scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = SnapshotCache(scope)

    @AfterEach
    fun stop() = scope.cancel()

    private fun key(generation: Long) = SnapshotCache.Key(ACCOUNT, LIBRARIES, 0L, generation, null)

    private fun snapshot() = RuleSnapshot(0L, setOf(LIBRARY_ID), emptyList(), emptyMap(), emptyMap(), true)

    /** A read that says when it started, waits for [release], and says when it was cancelled. */
    private class Read(
        val result: RuleSnapshot,
    ) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        suspend fun run(): RuleSnapshot {
            entered.complete(Unit)
            try {
                release.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
            return result
        }
    }

    @Test
    fun theLastWaiterToLeaveCancelsTheRead() =
        runBlocking {
            val read = Read(snapshot())
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { cache.get(key(1)) { read.run() } }
            withTimeout(WAIT_MS) { read.entered.await() }

            waiter.cancelAndJoin()

            withTimeout(WAIT_MS) { read.cancelled.await() }
            assertEquals(1, cache.cancelledReads)
        }

    @Test
    fun aReadStaysWhileAnyWaiterIsLeft() =
        runBlocking {
            val read = Read(snapshot())
            val first = async(start = CoroutineStart.UNDISPATCHED) { cache.get(key(1)) { read.run() } }
            val second = async(start = CoroutineStart.UNDISPATCHED) { cache.get(key(1)) { read.run() } }
            withTimeout(WAIT_MS) { read.entered.await() }

            first.cancelAndJoin()
            read.release.complete(Unit)

            assertSame(read.result, withTimeout(WAIT_MS) { second.await() }.snapshot)
            assertEquals(0, cache.cancelledReads)
            assertEquals(1, cache.reads, "and the two waiters shared one read")
        }

    @Test
    fun aReadOfANewerStateReplacesTheOlderAndItsWaitersAskAgain() =
        runBlocking {
            val older = Read(snapshot())
            val newer = Read(snapshot())
            val waiting =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { cache.get(key(1)) { older.run() } }
                }
            withTimeout(WAIT_MS) { older.entered.await() }

            val current = async(start = CoroutineStart.UNDISPATCHED) { cache.get(key(2)) { newer.run() } }
            withTimeout(WAIT_MS) { older.cancelled.await() }
            newer.release.complete(Unit)

            assertIs<SnapshotCache.Superseded>(withTimeout(WAIT_MS) { waiting.await() }.exceptionOrNull())
            assertSame(newer.result, withTimeout(WAIT_MS) { current.await() }.snapshot)
            assertEquals(2, cache.reads)
            assertEquals(1, cache.cancelledReads)
        }

    @Test
    fun anOlderReadFinishingLateDoesNotPushOutANewerSnapshot() =
        runBlocking {
            val newer = snapshot()
            assertEquals(SnapshotCache.Found.Miss, cache.get(key(2)) { newer }.found)

            // An older state read after the newer one was kept: served to its caller, never kept.
            assertEquals(SnapshotCache.Found.Miss, cache.get(key(1)) { snapshot() }.found)

            val again = cache.get(key(2)) { error("the newer snapshot is still kept") }
            assertEquals(SnapshotCache.Found.Hit, again.found)
            assertSame(newer, again.snapshot)
        }

    @Test
    fun aNewerStateTakesTheOlderOneOutOfTheCache() =
        runBlocking {
            cache.get(key(1)) { snapshot() }
            cache.get(key(2)) { snapshot() }

            assertEquals(SnapshotCache.Found.Miss, cache.get(key(1)) { snapshot() }.found)
            assertEquals(3, cache.reads)
        }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
