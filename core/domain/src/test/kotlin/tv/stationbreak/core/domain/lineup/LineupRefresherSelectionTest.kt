// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
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
    private val writing = WritingSignal()
    private val sync = SyncActivity { writing.flow() }

    @Volatile private var libraries: Set<ItemId>? = LIBRARIES

    // How many lost-block checks have run, as a state to await.
    private val checkCount = MutableStateFlow(0)

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
            checkCount.update { count -> count + 1 }
            // The first change's check (the second check) waits here when a test holds it.
            if (checks.size == 2) held?.await()
        }
        val read = { libraries }
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
        writing.awaitRunEndsListening()
        assertEquals(before, refreshes, "current at start")
        return before
    }

    // Waits, on the check hook itself, until [count] checks have run. The timeout only stops a hung test.
    private suspend fun awaitChecks(count: Int) = withTimeout(WAIT_MS) { checkCount.first { it >= count } }

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
            val reads = writing.reads.value
            writing.value = true

            libraries = LIBRARIES + OTHER
            seq.value = 1L
            until("the refresh is not held") { refreshes == before + 1 }
            // The change has asked whether a run writes, after its refresh: ending the run sooner races that read.
            writing.awaitReads(reads + 1)
            writing.value = false
            until("the check at the run's end") { checks.size == 2 }

            val expected = listOf(Check(0L), Check(1L), Check(1L + PROBE_STEP))
            assertEquals(expected, probe(), "never while the run wrote; once for the change and the run's end")
        }

    // Fix round 1, MED-2: whether a run writes is asked after the refresh, not before it. Synchronised on hooks only
    // (the run's start inside the planning read, the libraries read, the check hook), never on time or host load.
    @Test
    fun aRunThatBeginsDuringTheChangesRefresh_holdsTheCheckToItsEnd() =
        runBlocking<Unit> {
            val beginsARun = RunBeginsInRefresh(library, writing)
            startedCurrent(beginsARun)
            val writingReads = writing.reads.value

            beginsARun.armed = true
            libraries = LIBRARIES + OTHER
            seq.value = 1L
            withTimeout(WAIT_MS) { beginsARun.began.await() }
            // A second change while the run writes. Each change asks whether a run writes after its refresh: once both
            // have asked, both were handled, and ending the run cannot race either read.
            seq.value = 2L
            writing.awaitReads(writingReads + 2)
            assertEquals(listOf(Check(0L)), checks.toList(), "no check while the run that began in the refresh writes")

            writing.value = false
            awaitChecks(2)
            assertEquals(listOf(Check(0L), Check(2L), Check(2L + PROBE_STEP)), probe())
        }

    @Test
    fun aChangeWhileTheStartCheckIsOwed_runsTheCheckOnceAtTheRunsEnd() =
        runBlocking<Unit> {
            writing.value = true
            LineupService(store, library, clock, { 0 }, {}, TestCatalog).refresh(ACCOUNT, LIBRARIES)
            refresher(library).start(scope)
            writing.awaitRunEndsListening()

            seq.value = 1L
            // The start check and the change each ask whether a run writes, in either order, the change after its
            // refresh. Once both have asked, ending the run cannot race either read. (A libraries read is no barrier:
            // the start check makes one too, and the change makes its own before its refresh, not after.)
            writing.awaitReads(2)
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

/**
 * A sync's writing signal for the tests: it gives its current value first and then every change, in order, to every
 * collector, as [SyncActivity] asks. Unlike a `StateFlow` it never conflates: a run that starts and ends before a
 * collector is scheduled is still seen to end. And a test can wait until the refresher's run-end listener is
 * subscribed ([awaitRunEndsListening]) before it starts or ends a run, so no test depends on how busy the host is.
 */
private class WritingSignal {
    private val values = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = BUFFER)
    private val calls = AtomicInteger()
    private val listening = CompletableDeferred<Unit>()

    /** How many one-value reads (every call after the first) have taken a value, as a state to await. */
    val reads = MutableStateFlow(0)

    init {
        check(values.tryEmit(false))
    }

    /**
     * The flow for one `SyncActivity.writing()` call. The refresher's first call is the one that builds its run-end
     * listener (when a follow begins, before any trigger is acted on); later calls are its one-value reads.
     */
    fun flow(): Flow<Boolean> {
        val runEnds = calls.getAndIncrement() == 0
        return if (runEnds) {
            values.onSubscription { listening.complete(Unit) }
        } else {
            // Counted once the value is taken, so a change made after the count is reached cannot reach this read.
            values.onEach { reads.update { count -> count + 1 } }
        }
    }

    /** Waits until [count] one-value reads have taken a value. The timeout only stops a hung test. */
    suspend fun awaitReads(count: Int) = withTimeout(LISTEN_WAIT_MS) { reads.first { it >= count } }

    /** Waits until the refresher's run-end listener has subscribed. The timeout only stops a hung test. */
    suspend fun awaitRunEndsListening() = withTimeout(LISTEN_WAIT_MS) { listening.await() }

    @Volatile var value: Boolean = false
        set(next) {
            field = next
            check(values.tryEmit(next)) { "the writing signal's buffer is full" }
        }

    private companion object {
        const val BUFFER = 1024
        const val LISTEN_WAIT_MS = 10_000L
    }
}

/** A library whose planning read, once [armed], starts a sync run ([writing] goes true) in the middle of a refresh. */
private class RunBeginsInRefresh(
    private val inner: CountingLibrary,
    private val writing: WritingSignal,
) : LibraryRepository by inner {
    @Volatile var armed = false

    /** Completed when the run has begun, inside the planning read. */
    val began = CompletableDeferred<Unit>()

    override suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot {
        if (armed) {
            armed = false
            writing.value = true
            began.complete(Unit)
        }
        return inner.ruleSnapshot(account, libraries, cutoff)
    }
}
