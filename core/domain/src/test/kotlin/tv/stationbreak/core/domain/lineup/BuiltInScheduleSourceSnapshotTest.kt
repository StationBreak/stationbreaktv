// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The snapshots a day build reads are kept across lineup revisions and dropped when the library changes (#392), and a
 * build whose lineup revision has been replaced stops and hands its callers to the build that replaced it.
 */
class BuiltInScheduleSourceSnapshotTest {
    /** A library whose snapshot reads at a build's cut-off wait for [gate]; the refresh's read at [NOON] does not. */
    private class GatedLibrary(
        val inner: CountingLibrary,
    ) : LibraryRepository by inner {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override suspend fun ruleSnapshot(
            account: LibraryAccount,
            libraries: Set<ItemId>,
            cutoff: Instant,
        ): RuleSnapshot {
            val held = gate
            if (held != null && cutoff != NOON) {
                entered.complete(Unit)
                held.await()
            }
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
    private val builds = Collections.synchronizedList(mutableListOf<DayBuild>())
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private var chosen: Set<ItemId> = LIBRARIES
    private val source = BuiltInScheduleSource(service, library, built = { builds += it }) { chosen }

    init {
        runBlocking { counting.delegate.useAccount(ACCOUNT) }
        counting.seed(fillingLibrary())
    }

    private fun refresh() = runBlocking { service.refresh(ACCOUNT, LIBRARIES) }

    private fun days() = runBlocking { source.channelDays(ACCOUNT, chosen, NOON) }

    @Test
    fun aNewRevisionBuildsFromTheSnapshotAlreadyRead() {
        refresh()
        val first = days()
        refresh()
        val second = days()

        assertEquals(1, source.snapshotReads(), "one read serves both revisions")
        assertEquals(2, builds.size, "but the days were built for each")
        assertNotSame(first, second)
    }

    @Test
    fun aChangeToTheLibraryMakesTheNextBuildReadAgain() {
        refresh()
        days()
        counting.seed(movie(900, "Comedy", rating = "R"))
        refresh()
        days()

        assertEquals(2, source.snapshotReads(), "the sync moved the content generation")
    }

    @Test
    fun aCompletedCollectionPassMakesTheNextBuildReadAgain() {
        refresh()
        days()
        counting.delegate.completeCollectionPass(ACCOUNT, Instant.parse("2026-10-19T11:00:00Z"))
        refresh()
        days()

        assertEquals(2, source.snapshotReads(), "collectionsSynced may have changed with no row written")
    }

    @Test
    fun anotherChoiceOfLibrariesReadsItsOwnSnapshot() {
        refresh()
        days()
        chosen = setOf(ItemId("d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"))
        days()
        chosen = LIBRARIES
        days()

        assertEquals(2, source.snapshotReads(), "one read for each choice, and the first choice is still kept")
    }

    @Test
    fun aBuildForARevisionThatWasReplacedStopsAndItsCallersGetTheNewDays() =
        runBlocking {
            refresh()
            val gate = CompletableDeferred<Unit>()
            library.gate = gate
            // Undispatched: it has joined (or started) the revision 1 build, which waits on its snapshot, on return.
            val old = async(start = CoroutineStart.UNDISPATCHED) { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
            withTimeout(WAIT_MS) { library.entered.await() }
            refresh()
            val current = async(start = CoroutineStart.UNDISPATCHED) { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
            gate.complete(Unit)

            val oldResult = withTimeout(WAIT_MS) { old.await() }
            val currentResult = withTimeout(WAIT_MS) { current.await() }
            assertSame(currentResult, oldResult, "the caller of the replaced revision got the current revision's days")
            assertEquals(1, builds.size, "the stale build did not go on to a full build of its own")
            assertEquals(1, source.snapshotReads(), "and both builds used the one read")
            assertEquals(0, source.inFlightCount())
        }

    @Test
    fun aCallerThatJoinedTheStaleBuildAlsoGetsTheCurrentRevisionsDays() =
        runBlocking {
            refresh()
            val gate = CompletableDeferred<Unit>()
            library.gate = gate
            val starter = async(start = CoroutineStart.UNDISPATCHED) { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
            val joiner = async(start = CoroutineStart.UNDISPATCHED) { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
            withTimeout(WAIT_MS) { library.entered.await() }
            assertEquals(1, source.joined.get(), "the second caller joined the revision 1 build")
            refresh()
            val current = async(start = CoroutineStart.UNDISPATCHED) { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
            gate.complete(Unit)

            val expected = withTimeout(WAIT_MS) { current.await() }
            assertSame(expected, withTimeout(WAIT_MS) { starter.await() })
            assertSame(expected, withTimeout(WAIT_MS) { joiner.await() })
            assertEquals(1, builds.size)
        }

    @Test
    fun aSyncDuringAReadCancelsItAndTheBuildReadsTheNewState() =
        runBlocking {
            refresh()
            val gate = CompletableDeferred<Unit>()
            library.gate = gate
            val old = async(start = CoroutineStart.UNDISPATCHED) { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
            withTimeout(WAIT_MS) { library.entered.await() }
            // A sync writes: the content generation moves, so the read in flight is for a state that is gone.
            counting.seed(movie(901, "Comedy", rating = "R"))
            refresh()
            val current = async(start = CoroutineStart.UNDISPATCHED) { source.channelDays(ACCOUNT, LIBRARIES, NOON) }
            gate.complete(Unit)

            val expected = withTimeout(WAIT_MS) { current.await() }
            assertSame(expected, withTimeout(WAIT_MS) { old.await() })
            assertEquals(2, source.snapshotReads(), "the old read and the new one")
            assertEquals(1, source.cancelledSnapshotReads(), "and the old one was cancelled, not left to compete")
            assertEquals(1, builds.size)
            assertEquals(0, source.inFlightCount())
        }

    @Test
    fun aBuildReportsWhereItsTimeWent() {
        refresh()
        var thread = 0L
        var process = 100L
        var gcs = 7L
        val told = mutableListOf<DayBuild>()
        val probes =
            BuildProbes(
                threadCpuNanos = { thread.also { thread += 3_000_000L } },
                processCpuMs = { process.also { process += 5L } },
                gcCount = { gcs.also { gcs += 2L } },
                gcBlockMs = { 40L },
            )
        val timed = BuiltInScheduleSource(service, library, built = { told += it }, probes = probes) { chosen }
        runBlocking { timed.channelDays(ACCOUNT, chosen, NOON) }
        val build = told.single()

        assertEquals(3L, build.poolsCpuMs, "one pair of thread readings around the pool work")
        assertEquals(5L, build.snapshotsProcCpuMs)
        assertEquals(5L, build.poolsProcCpuMs)
        assertEquals(4L, build.gcCount, "read at the start, after the snapshots and at the end, 2 apart")
        assertEquals(0L, build.gcBlockMs)
        assertEquals("miss", build.snapshot)
    }

    @Test
    fun aBuildFromKeptSnapshotsSaysHit() {
        refresh()
        days()
        refresh()
        days()
        assertEquals(listOf("miss", "hit"), builds.map { it.snapshot })
    }

    @Test
    fun clocksThatCannotTellGiveMinusOne() {
        refresh()
        val told = mutableListOf<DayBuild>()
        val probes = BuildProbes({ -1L })
        val blind = BuiltInScheduleSource(service, library, built = { told += it }, probes = probes) { chosen }
        runBlocking { blind.channelDays(ACCOUNT, chosen, NOON) }
        val b = told.single()
        assertEquals(
            listOf(-1L, -1L, -1L, -1L, -1L),
            listOf(b.poolsCpuMs, b.snapshotsProcCpuMs, b.poolsProcCpuMs, b.gcCount, b.gcBlockMs),
        )
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
