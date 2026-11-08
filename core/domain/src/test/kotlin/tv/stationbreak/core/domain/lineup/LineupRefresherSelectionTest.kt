// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The selection trigger (spec 26 Part 4, §2 and D14): the selection's seq, a state, changes. A change refreshes the
 * lineup when it is behind, then runs the lost-block check, which is held to a run's end while a sync writes, by the
 * same rule as the start check, and judged after the refresh. A changes source listened to again gives its newest
 * value, so a change made while it was not listened to is not lost, and a burst of changes is one more check.
 *
 * No test sleeps to show that something did not happen. Each check records the seq and whether a run was writing;
 * a test waits for the checks it expects, then saves one more change (a probe) and waits for its check: the refresher
 * handles one trigger at a time, so any check owed before the probe would have come first, and the whole list is exact.
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

    // How many times the refresher read the libraries: once in every refresh it considers.
    private val libraryReads = AtomicInteger()

    // The selection's seq, as LibrarySelection will hold it.
    private val seq = MutableStateFlow(0L)

    /** One lost-block check: the seq then, and whether a sync run was writing. */
    private data class Check(
        val seq: Long,
        val writing: Boolean = false,
    )

    private val checks = CopyOnWriteArrayList<Check>()

    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }

    private val refreshes get() = library.snapshotCutoffs.size

    @AfterTest
    fun stop() {
        job.cancel()
    }

    private suspend fun until(
        what: String,
        condition: suspend () -> Boolean,
    ) {
        withTimeout(WAIT_MS) { while (!condition()) delay(POLL_MS) }
        check(condition()) { what }
    }

    private fun refresher(
        lib: LibraryRepository,
        held: CompletableDeferred<Unit>? = null,
    ): LineupRefresher {
        val service = LineupService(store, lib, clock, { 0 }, { events += it }, TestCatalog)
        val afterSync: suspend (LibraryAccount) -> Unit = {
            checks += Check(seq.value, writing.value)
            // The first change's check (the second check) waits here when a test holds it.
            if (checks.size == 2) held?.await()
        }
        val read = {
            libraryReads.incrementAndGet()
            libraries
        }
        return LineupRefresher(service, lib, read, sync, seq, BACKOFF_MS, afterSync) { events += it }
    }

    // A current lineup and a started refresher whose start check has run; returns the refreshes so far.
    private suspend fun startedCurrent(
        lib: LibraryRepository = library,
        held: CompletableDeferred<Unit>? = null,
        beforeStart: () -> Unit = {},
    ): Int {
        LineupService(store, lib, clock, { 0 }, {}, TestCatalog).refresh(ACCOUNT, LIBRARIES)
        val before = refreshes
        beforeStart()
        refresher(lib, held).start(scope)
        until("the start check") { checks.isNotEmpty() }
        assertEquals(before, refreshes, "current at start")
        return before
    }

    // Saves one more change and waits for its check: everything owed before it has been handled by then.
    private suspend fun probe(): List<Check> {
        val next = seq.value + PROBE_STEP
        seq.value = next
        until("the probe's check") { checks.any { it.seq == next } }
        return checks.toList()
    }

    @Test
    fun aChangedChoice_refreshesWithoutASync_thenRunsTheLostBlockCheck() =
        runBlocking<Unit> {
            val before = startedCurrent()
            val refreshesAtCheck = CopyOnWriteArrayList<Int>()

            libraries = LIBRARIES + OTHER
            seq.value = 1L
            until("the change's check") { checks.size == 2 }
            refreshesAtCheck += refreshes

            assertEquals(listOf(before + 1), refreshesAtCheck, "the check ran after the one refresh")
            val planned = LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded
            assertEquals((LIBRARIES + OTHER).map { it.value }.sorted(), planned.meta.plannedLibraries)
            assertEquals(listOf(Check(0L), Check(1L), Check(1L + PROBE_STEP)), probe())
            assertEquals(before + 1, refreshes, "the probe changed nothing to plan")
        }

    @Test
    fun theSeqAtTheStart_isNoChange() =
        runBlocking<Unit> {
            seq.value = 4L
            startedCurrent()

            assertEquals(listOf(Check(4L), Check(4L + PROBE_STEP)), probe(), "the seq it began with was seen")
        }

    @Test
    fun aChangeThatLeavesTheLineupCurrent_refreshesNothing_butStillRunsTheCheck() =
        runBlocking<Unit> {
            val before = startedCurrent()

            seq.value = 1L
            until("the change's check") { checks.size == 2 }

            assertEquals(before, refreshes)
            assertEquals(listOf(Check(0L), Check(1L), Check(1L + PROBE_STEP)), probe())
        }

    @Test
    fun aChangeWhileASyncWrites_refreshesAtOnce_andLeavesTheCheckToTheRunsEnd() =
        runBlocking<Unit> {
            val before = startedCurrent()
            writing.value = true

            libraries = LIBRARIES + OTHER
            seq.value = 1L
            until("the refresh is not held") { refreshes == before + 1 }
            writing.value = false
            until("the check at the run's end") { checks.size == 2 }

            val expected = listOf(Check(0L), Check(1L), Check(1L + PROBE_STEP))
            assertEquals(expected, probe(), "never while the run wrote; once for the change and the run's end")
        }

    // Fix round 1, MED-2: whether a run writes is asked after the refresh, not before it.
    @Test
    fun aRunThatBeginsDuringTheChangesRefresh_holdsTheCheckToItsEnd() =
        runBlocking<Unit> {
            val beginsARun = RunBeginsInRefresh(library, writing)
            val before = startedCurrent(beginsARun)

            beginsARun.armed = true
            libraries = LIBRARIES + OTHER
            seq.value = 1L
            until("the change's refresh") { refreshes == before + 1 }
            val reads = libraryReads.get()
            // A second change while the run writes: once its refresh is considered, the first change was handled.
            seq.value = 2L
            until("the first change was handled") { libraryReads.get() > reads }
            assertEquals(listOf(Check(0L)), checks.toList(), "no check while the run that began in the refresh writes")

            writing.value = false
            until("the check at that run's end") { checks.size == 2 }
            assertEquals(listOf(Check(0L), Check(2L), Check(2L + PROBE_STEP)), probe())
        }

    @Test
    fun aChangeWhileTheStartCheckIsOwed_runsTheCheckOnceAtTheRunsEnd() =
        runBlocking<Unit> {
            writing.value = true
            LineupService(store, library, clock, { 0 }, {}, TestCatalog).refresh(ACCOUNT, LIBRARIES)
            val reads = libraryReads.get()
            refresher(library).start(scope)

            seq.value = 1L
            until("the change was handled") { libraryReads.get() > reads }
            assertEquals(emptyList(), checks.toList(), "neither check runs while the run writes")

            writing.value = false
            until("the check at the run's end") { checks.isNotEmpty() }
            assertEquals(listOf(Check(1L), Check(1L + PROBE_STEP)), probe(), "one covers the start and the change")
        }

    @Test
    fun aBurstOfChanges_whileOneIsHandled_isOneMoreCheck() =
        runBlocking<Unit> {
            val held = CompletableDeferred<Unit>()
            startedCurrent(held = held)

            seq.value = 1L
            until("the first change's check, held") { checks.size == 2 }
            for (next in 2L..BURST_END) seq.value = next
            held.complete(Unit)
            until("the burst's check") { checks.size == 3 }

            val expected = listOf(Check(0L), Check(1L), Check(BURST_END), Check(BURST_END + PROBE_STEP))
            assertEquals(expected, probe(), "the start, the first change, one for the whole burst")
        }

    @Test
    fun aChangeAfterAnotherWasHandled_isAnotherCheck() =
        runBlocking<Unit> {
            startedCurrent()

            seq.value = 1L
            until("the first change's check") { checks.size == 2 }
            seq.value = 2L
            until("the second change's check") { checks.size == 3 }

            assertEquals(listOf(Check(0L), Check(1L), Check(2L)), checks.toList())
        }

    // Fix round 1, MED-1: a change saved while a failed source waits to be listened to again is not lost.
    @Test
    fun aChangeSavedDuringTheBackoff_isOneCheckAfterTheRetry() =
        runBlocking<Unit> {
            val failing = FailingPassesOnSignal(library)
            val before = startedCurrent(failing, beforeStart = { failing.armed.set(true) })

            failing.fail.complete(Unit)
            until("the source failed") { LineupEvent.RefreshFailed in events }
            // Saved during the backoff, while nothing listens.
            seq.value = 1L
            until("listened to again") { failing.listenedAgain.get() >= 1 }
            until("the change's check") { checks.size == 2 }

            assertEquals(listOf(Check(0L), Check(1L), Check(1L + PROBE_STEP)), probe(), "exactly one, for the change")
            assertEquals(before, refreshes, "nothing was behind")
        }

    @Test
    fun aRetryWithNoChange_runsNoCheck() =
        runBlocking<Unit> {
            val failing = FailingPassesOnSignal(library)
            startedCurrent(failing, beforeStart = { failing.armed.set(true) })

            failing.fail.complete(Unit)
            until("listened to again") { failing.listenedAgain.get() >= 1 }

            assertEquals(listOf(Check(0L), Check(PROBE_STEP)), probe(), "the replayed seq was already seen")
        }

    @Test
    fun aGoneSelection_refreshesNothing_butStillRunsTheCheck() =
        runBlocking<Unit> {
            val before = startedCurrent()

            // Gone: nothing is playable (the refresher reads the playable libraries).
            libraries = null
            seq.value = 1L
            until("the change's check") { checks.size == 2 }

            assertEquals(before, refreshes, "no libraries to plan from")
        }

    private companion object {
        val OTHER = setOf(ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0"))
        const val BURST_END = 6L
        const val PROBE_STEP = 100L
        const val BACKOFF_MS = 50L
        const val WAIT_MS = 10_000L
        const val POLL_MS = 5L
    }
}

/**
 * A library whose first collection-pass flow after [armed] is set (the refresher's pass source) fails when [fail]
 * completes; every other collection is the real one, and those after the failure are counted ([listenedAgain]).
 */
private class FailingPassesOnSignal(
    private val inner: CountingLibrary,
) : LibraryRepository by inner {
    val armed = AtomicBoolean(false)
    val fail = CompletableDeferred<Unit>()
    val listenedAgain = AtomicInteger()

    override fun collectionPassCompleted(account: LibraryAccount): Flow<Instant?> =
        flow {
            if (armed.getAndSet(false)) {
                fail.await()
                error("the trigger flow failed")
            }
            if (fail.isCompleted) listenedAgain.incrementAndGet()
            inner.collectionPassCompleted(account).collect { emit(it) }
        }
}

/** A library whose planning read, once [armed], starts a sync run ([writing] goes true) in the middle of a refresh. */
private class RunBeginsInRefresh(
    private val inner: CountingLibrary,
    private val writing: MutableStateFlow<Boolean>,
) : LibraryRepository by inner {
    @Volatile var armed = false

    override suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot {
        if (armed) {
            armed = false
            writing.value = true
        }
        return inner.ruleSnapshot(account, libraries, cutoff)
    }
}
