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

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
