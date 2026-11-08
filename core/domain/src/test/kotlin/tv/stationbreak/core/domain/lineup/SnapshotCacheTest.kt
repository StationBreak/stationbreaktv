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
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The snapshot cache's own rules: who keeps a read alive, which read a newer state replaces, what is kept. */
class SnapshotCacheTest {
    @Suppress("InjectDispatcher") // A test's own scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = SnapshotCache(scope)

    @AfterEach
    fun stop() = scope.cancel()

    private fun key(generation: Long) = SnapshotCache.Key(ACCOUNT, LIBRARIES, 0L, generation, extras = false)

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
    fun readsOfNewerStatesNeverCancelOlderReadsThatStillHaveWaiters() =
        runBlocking {
            // A sync bumping the generation under overlapping builds: each build keeps the read it asked for.
            val reads = (1L..3L).map { Read(snapshot()) }
            val waiters =
                reads.mapIndexed { index, read ->
                    async(start = CoroutineStart.UNDISPATCHED) { cache.get(key(index + 1L)) { read.run() } }
                }
            reads.forEach { withTimeout(WAIT_MS) { it.entered.await() } }
            assertEquals(3, cache.reads)
            assertEquals(0, cache.cancelledReads)

            reads.forEach { it.release.complete(Unit) }

            waiters.forEachIndexed { index, waiter ->
                assertSame(reads[index].result, withTimeout(WAIT_MS) { waiter.await() }.snapshot)
            }
            assertEquals(3, cache.reads, "no read was started again")
            assertEquals(0, cache.cancelledReads)
            val newest = cache.get(key(3)) { error("the newest is kept") }
            assertEquals(SnapshotCache.Found.Hit, newest.found)
        }

    @Test
    fun aReplacedReadTakesNoNewWaitersAndIsCancelledWhenItsLastLeaves() =
        runBlocking {
            val older = Read(snapshot())
            val newer = Read(snapshot())
            val first = async(start = CoroutineStart.UNDISPATCHED) { cache.get(key(1)) { older.run() } }
            withTimeout(WAIT_MS) { older.entered.await() }
            val current = async(start = CoroutineStart.UNDISPATCHED) { cache.get(key(2)) { newer.run() } }
            withTimeout(WAIT_MS) { newer.entered.await() }

            // Asking for the old state again does not join the replaced read.
            val again = Read(snapshot())
            val late = async(start = CoroutineStart.UNDISPATCHED) { cache.get(key(1)) { again.run() } }
            withTimeout(WAIT_MS) { again.entered.await() }
            assertEquals(3, cache.reads)

            first.cancelAndJoin()
            withTimeout(WAIT_MS) { older.cancelled.await() }
            assertEquals(1, cache.cancelledReads, "the replaced read stopped with its last waiter")
            current.cancelAndJoin()
            late.cancelAndJoin()
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

    // #487: a pass that changed nothing moves the pass instant and not the generation, and the key has no pass.
    @Test
    fun aKeyOfTheSameGenerationIsAHitAndAMovedGenerationIsAMiss() =
        runBlocking {
            val kept = snapshot()
            assertEquals(SnapshotCache.Found.Miss, cache.get(key(5)) { kept }.found)

            val quiet = cache.get(key(5)) { error("a quiet pass moves no generation, so the snapshot is kept") }
            assertEquals(SnapshotCache.Found.Hit, quiet.found)
            assertSame(kept, quiet.snapshot)

            assertEquals(SnapshotCache.Found.Miss, cache.get(key(6)) { snapshot() }.found)
            assertEquals(2, cache.reads)
        }

    // --- Eviction of the snapshots that name a library (spec 26, Part 4 §2: before an add lands) -------------------

    private val other = ItemId("d0".repeat(16))

    private fun keyOf(
        libraries: Set<ItemId>,
        account: LibraryAccount = ACCOUNT,
    ) = SnapshotCache.Key(account, libraries, 0L, 1L, extras = false)

    @Test
    fun evictNaming_dropsTheKeptSnapshotsThatNameTheLibrary_andNoOthers() =
        runBlocking {
            val withOther = keyOf(LIBRARIES + other)
            val plain = keyOf(LIBRARIES)
            val otherAccount = keyOf(LIBRARIES + other, LibraryAccount(ACCOUNT.server, UserId(id(5).value)))
            for (key in listOf(withOther, plain, otherAccount)) cache.get(key) { snapshot() }

            cache.evictNaming(ACCOUNT, other)

            assertEquals(SnapshotCache.Found.Miss, cache.get(withOther) { snapshot() }.found, "read again")
            assertEquals(SnapshotCache.Found.Hit, cache.get(plain) { error("kept") }.found)
            assertEquals(SnapshotCache.Found.Hit, cache.get(otherAccount) { error("another account's") }.found)
        }

    @Test
    fun evictNaming_aReadUnderWayIsServedToItsWaiters_butNotKept_norJoined() =
        runBlocking {
            val key = keyOf(LIBRARIES + other)
            val read = Read(snapshot())
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { cache.get(key) { read.run() } }
            withTimeout(WAIT_MS) { read.entered.await() }

            cache.evictNaming(ACCOUNT, other)
            val after = Read(snapshot())
            val late = async(start = CoroutineStart.UNDISPATCHED) { cache.get(key) { after.run() } }
            withTimeout(WAIT_MS) { after.entered.await() }
            assertEquals(2, cache.reads, "a caller after the eviction does not join the read before it")
            read.release.complete(Unit)

            assertSame(read.result, withTimeout(WAIT_MS) { waiter.await() }.snapshot)
            after.release.complete(Unit)
            assertSame(after.result, withTimeout(WAIT_MS) { late.await() }.snapshot)
            val kept = cache.get(key) { error("the read after the eviction is kept") }
            assertSame(after.result, kept.snapshot)
        }

    @Test
    fun evictNaming_aReadUnderWayThatFinishesAlone_isNotKept() =
        runBlocking {
            val key = keyOf(LIBRARIES + other)
            val read = Read(snapshot())
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { cache.get(key) { read.run() } }
            withTimeout(WAIT_MS) { read.entered.await() }

            cache.evictNaming(ACCOUNT, other)
            read.release.complete(Unit)
            withTimeout(WAIT_MS) { waiter.await() }

            assertEquals(SnapshotCache.Found.Miss, cache.get(key) { snapshot() }.found, "read before the eviction")
        }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
