// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The selection trigger (spec 26 Part 4, §2 and D14): a saved change of the chosen libraries refreshes the lineup when
 * it is behind, then runs the lost-block check, which is held to a run's end while a sync writes, by the same rule as
 * the start check. The changes flow keeps one trigger waiting: a burst of changes while one is handled is one more.
 */
class LineupRefresherSelectionTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
    }

    private val store = RecordingStore()
    private val events = CopyOnWriteArrayList<LineupEvent>()
    private val job = Job()
    private val scope = CoroutineScope(job)
    private val writing = MutableStateFlow(false)
    private val sync = SyncActivity { writing }

    @Volatile private var libraries: Set<ItemId>? = LIBRARIES

    // As LibrarySelection will publish them (D14): one buffered, the older dropped.
    private val changes =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, { events += it }, TestCatalog)

    private val refreshes get() = library.snapshotCutoffs.size

    private fun refresher(afterSync: suspend (LibraryAccount) -> Unit) =
        LineupRefresher(service, library, { libraries }, sync, afterSync = afterSync, selectionChanges = changes) {
            events += it
        }

    @AfterTest
    fun stop() {
        job.cancel()
    }

    private suspend fun until(
        what: String,
        condition: suspend () -> Boolean,
    ) {
        withTimeout(WAIT_MS) { while (!condition()) delay(10) }
        check(condition()) { what }
    }

    private suspend fun settle() = delay(SETTLE_MS)

    // A current lineup and a started refresher whose start check has run; returns the refreshes so far.
    private suspend fun startedCurrent(afterSync: suspend (LibraryAccount) -> Unit): Int {
        service.refresh(ACCOUNT, LIBRARIES)
        val before = refreshes
        val started = CompletableDeferred<Unit>()
        refresher {
            afterSync(it)
            started.complete(Unit)
        }.start(scope)
        withTimeout(WAIT_MS) { started.await() }
        settle()
        assertEquals(before, refreshes, "current at start")
        return before
    }

    @Test
    fun aChangedChoice_refreshesWithoutASync_thenRunsTheLostBlockCheck() =
        runBlocking<Unit> {
            val refreshesAtCheck = CopyOnWriteArrayList<Int>()
            val before = startedCurrent { refreshesAtCheck += refreshes }

            libraries = LIBRARIES + OTHER
            changes.emit(Unit)
            until("the check after the change") { refreshesAtCheck.size == 2 }
            settle()

            assertEquals(before + 1, refreshes, "one refresh, for the new choice")
            assertEquals(listOf(before, before + 1), refreshesAtCheck, "the check runs after the refresh")
            val planned = LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded
            assertEquals((LIBRARIES + OTHER).map { it.value }.sorted(), planned.meta.plannedLibraries)
        }

    @Test
    fun aChangeThatLeavesTheLineupCurrent_refreshesNothing_butStillRunsTheCheck() =
        runBlocking<Unit> {
            val seen = CopyOnWriteArrayList<LibraryAccount>()
            val before = startedCurrent { seen += it }

            changes.emit(Unit)
            until("the check after the change") { seen.size == 2 }
            settle()

            assertEquals(before, refreshes)
            assertEquals(listOf(ACCOUNT, ACCOUNT), seen)
        }

    @Test
    fun aChangeWhileASyncWrites_refreshesAtOnce_andLeavesTheCheckToTheRunsEnd() =
        runBlocking<Unit> {
            val seen = CopyOnWriteArrayList<LibraryAccount>()
            val before = startedCurrent { seen += it }
            writing.value = true

            libraries = LIBRARIES + OTHER
            changes.emit(Unit)
            until("the refresh for the change") { refreshes == before + 1 }
            settle()
            assertEquals(listOf(ACCOUNT), seen, "the check waits for the run's end")

            writing.value = false
            until("the check after the run") { seen.size == 2 }
            settle()
            assertEquals(listOf(ACCOUNT, ACCOUNT), seen, "exactly once for the change and the run's end together")
        }

    @Test
    fun aChangeWhileTheStartCheckIsOwed_runsTheCheckOnceAtTheRunsEnd() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val seen = CopyOnWriteArrayList<LibraryAccount>()
            writing.value = true
            refresher { seen += it }.start(scope)
            settle()

            changes.emit(Unit)
            settle()
            assertEquals(emptyList(), seen, "neither check runs while the run writes")

            writing.value = false
            until("the check after the run") { seen.isNotEmpty() }
            settle()
            assertEquals(listOf(ACCOUNT), seen, "one check covers the start and the change")
        }

    @Test
    fun aBurstOfChanges_whileOneIsHandled_isOneMoreCheck() =
        runBlocking<Unit> {
            val calls = CopyOnWriteArrayList<Int>()
            val inCheck = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            startedCurrent { _ ->
                calls += calls.size
                // The first change's check: held, while the burst arrives.
                if (calls.size == 2) {
                    inCheck.complete(Unit)
                    release.await()
                }
            }

            changes.emit(Unit)
            withTimeout(WAIT_MS) { inCheck.await() }
            repeat(BURST) {
                changes.emit(Unit)
                delay(5)
            }
            settle()
            release.complete(Unit)
            until("the check for the burst") { calls.size >= 3 }
            settle()

            assertEquals(3, calls.size, "the start, the first change, and one for the whole burst")
        }

    @Test
    fun aChangeAfterTheBurstWasHandled_isAnotherCheck() =
        runBlocking<Unit> {
            val seen = CopyOnWriteArrayList<LibraryAccount>()
            startedCurrent { seen += it }

            changes.emit(Unit)
            until("the first change's check") { seen.size == 2 }
            settle()
            changes.emit(Unit)
            until("the second change's check") { seen.size == 3 }
            settle()

            assertEquals(3, seen.size, "a change that comes after is never folded into an earlier one")
        }

    @Test
    fun aGoneSelection_refreshesNothing_butStillRunsTheCheck() =
        runBlocking<Unit> {
            val seen = CopyOnWriteArrayList<LibraryAccount>()
            val before = startedCurrent { seen += it }

            // Gone: nothing is playable (the refresher reads the playable libraries).
            libraries = null
            changes.emit(Unit)
            until("the check after the change") { seen.size == 2 }
            settle()

            assertEquals(before, refreshes, "no libraries to plan from")
        }

    private companion object {
        val OTHER = setOf(ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0"))
        const val BURST = 5
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 250L
    }
}
