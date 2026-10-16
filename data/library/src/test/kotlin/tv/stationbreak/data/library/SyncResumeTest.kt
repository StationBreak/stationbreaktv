// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The process died: nothing after this runs, the way a kill stops the worker with no clean-up. */
private class ProcessDied : RuntimeException("the process died here")

/**
 * Spec 06 task 3.5: kill the first sync after the first page, a middle page and the last page, start it again, and the
 * database ends up the same as an uninterrupted run. A "kill" throws out of the server call, so nothing the sync would
 * have done afterwards happens, and the restart opens the database file again with a new repository, as a new process
 * would.
 */
class SyncResumeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val opened = mutableListOf<RoomLibraryRepository>()

    @After
    fun close() = opened.forEach { it.close() }

    private fun open(
        name: String,
        clock: FakeClock,
    ): RoomLibraryRepository = TestLibraries.onDisk(folder.root, name, clock).also { opened += it }

    private fun newServer() = SyncWorld.server(movies to SyncWorld.source("m", 450))

    /** Runs the first sync to its end on the database file [name] and returns what it left behind. */
    private suspend fun uninterrupted(name: String): Snapshot {
        val clock = FakeClock()
        val repository = open(name, clock)
        repository.useAccount(ALICE)
        val outcome = SyncWorld.engine(repository, newServer(), clock).run(session, ALICE, setOf(MOVIES))
        assertEquals(SyncOutcome.Completed, outcome)
        return Snapshot.of(repository)
    }

    /** Runs the first sync, killing it when the [killAt]th items call arrives, then restarts it on the same file. */
    private suspend fun killedAtItemsCall(
        name: String,
        killAt: Int,
        restartAfter: kotlin.time.Duration = 0.minutes,
        fault: ((RoomLibraryRepository) -> Unit)? = null,
    ): Snapshot {
        val clock = FakeClock()
        val first = open(name, clock)
        first.useAccount(ALICE)
        var itemCalls = 0
        val doomed = newServer()
        doomed.respond = {
            if (!it.idsOnly && ++itemCalls == killAt) throw ProcessDied()
            null
        }
        fault?.invoke(first)
        assertThrows(Exception::class.java) {
            runBlocking { SyncWorld.engine(first, doomed, clock).run(session, ALICE, setOf(MOVIES)) }
        }
        first.close()
        opened -= first

        // A new process: a new clock reading, a new repository on the same file, a new server connection.
        clock.instant += restartAfter
        val second = open(name, clock)
        second.useAccount(ALICE)
        val outcome = SyncWorld.engine(second, newServer(), clock).run(session, ALICE, setOf(MOVIES))
        assertEquals(SyncOutcome.Completed, outcome)
        return Snapshot.of(second)
    }

    /** What a finished sync leaves, in a form two runs can be compared by. */
    private data class Snapshot(
        val items: List<StoredItem>,
        val rows: RowCounts,
        val checkpoint: Checkpoint?,
        val pending: Set<tv.stationbreak.core.model.ItemId>,
        val status: LibraryStatus?,
    ) {
        companion object {
            suspend fun of(repository: RoomLibraryRepository) =
                Snapshot(
                    repository.stored(ALICE),
                    repository.rowCounts(),
                    repository.checkpoint(ALICE, MOVIES),
                    repository.pendingLibraries(ALICE),
                    repository.activeStatus().first(),
                )
        }
    }

    @Test
    fun anUninterruptedRunSavesEveryItemAndEndsIdle() =
        runBlocking {
            val result = uninterrupted("whole.db")

            assertEquals(450, result.items.size)
            assertEquals(SyncState.Idle, result.status?.state)
            assertEquals(emptySet<tv.stationbreak.core.model.ItemId>(), result.pending)
            assertEquals(450, result.checkpoint?.nextStartIndex)
        }

    @Test
    fun killedAfterTheFirstPageItEndsTheSameAsAnUninterruptedRun() =
        runBlocking {
            // Items call 1 is the first page, so the kill at call 2 leaves exactly one page saved.
            assertEquals(uninterrupted("a.db"), killedAtItemsCall("b.db", killAt = 2))
        }

    @Test
    fun killedAtAMiddlePageItEndsTheSame() =
        runBlocking {
            assertEquals(uninterrupted("a.db"), killedAtItemsCall("b.db", killAt = 4))
        }

    @Test
    fun killedAfterTheLastPageItEndsTheSame() =
        runBlocking {
            // Calls 1 to 5 are the five pages; call 6 is the empty page that ends the walk.
            assertEquals(uninterrupted("a.db"), killedAtItemsCall("b.db", killAt = 6))
        }

    @Test
    fun killedDuringThePostWalkPassItEndsTheSame() =
        runBlocking {
            assertEquals(uninterrupted("a.db"), killedAtItemsCall("b.db", killAt = 7))
        }

    @Test
    fun killedWhileAPageWasBeingWrittenItEndsTheSame() =
        runBlocking {
            // The third page dies between its items and its checkpoint: neither is kept, and it is fetched again.
            var writes = 0
            val result =
                killedAtItemsCall("b.db", killAt = Int.MAX_VALUE) { repository ->
                    repository.faultBetweenItemsAndCheckpoint = {
                        if (++writes == 3) throw ProcessDied()
                    }
                }

            assertEquals(uninterrupted("a.db"), result)
        }

    @Test
    fun aRestartDoesNotAskForPagesItAlreadyHas() =
        runBlocking {
            val clock = FakeClock()
            val first = open("c.db", clock)
            first.useAccount(ALICE)
            val doomed = newServer()
            var itemCalls = 0
            doomed.respond = { if (!it.idsOnly && ++itemCalls == 4) throw ProcessDied() else null }
            assertThrows(Exception::class.java) {
                runBlocking { SyncWorld.engine(first, doomed, clock).run(session, ALICE, setOf(MOVIES)) }
            }
            first.close()
            opened -= first

            val second = open("c.db", clock)
            second.useAccount(ALICE)
            val server = newServer()
            SyncWorld.engine(second, server, clock).run(session, ALICE, setOf(MOVIES))

            // Three pages were saved (0, 100, 200): the restart begins at 300.
            assertEquals(300, server.itemCalls.first().startIndex)
        }

    @Test
    fun aRestartKeepsTheOriginalWalkStartForThePostWalkPass() =
        runBlocking {
            val clock = FakeClock()
            val first = open("d.db", clock)
            first.useAccount(ALICE)
            val doomed = newServer()
            var itemCalls = 0
            doomed.respond = { if (!it.idsOnly && ++itemCalls == 3) throw ProcessDied() else null }
            assertThrows(Exception::class.java) {
                runBlocking { SyncWorld.engine(first, doomed, clock).run(session, ALICE, setOf(MOVIES)) }
            }
            first.close()
            opened -= first

            // Two hours later. An item saved in between must still be found, so the pass is measured from the first start.
            clock.instant += 2.hours
            val second = open("d.db", clock)
            second.useAccount(ALICE)
            val server = newServer()
            SyncWorld.engine(second, server, clock).run(session, ALICE, setOf(MOVIES))

            val pass = server.itemCalls.first { it.changedSince != null }
            assertEquals(EPOCH - 10.minutes, pass.changedSince)
            assertTrue(second.checkpoint(ALICE, MOVIES)?.run == EPOCH.toEpochMilliseconds())
        }

    @Test
    fun aRestartStartsFromTheSavedTotalNotFromScratch() =
        runBlocking {
            val clock = FakeClock()
            val first = open("e.db", clock)
            first.useAccount(ALICE)
            val doomed: PagedMediaServer = newServer()
            var itemCalls = 0
            doomed.respond = { if (!it.idsOnly && ++itemCalls == 3) throw ProcessDied() else null }
            assertThrows(Exception::class.java) {
                runBlocking { SyncWorld.engine(first, doomed, clock).run(session, ALICE, setOf(MOVIES)) }
            }
            first.close()
            opened -= first

            val second = open("e.db", clock)
            second.useAccount(ALICE)
            val server = newServer()
            SyncWorld.engine(second, server, clock).run(session, ALICE, setOf(MOVIES))

            // The saved checkpoint knew the total, so the restart needed no new count for this library.
            assertEquals(0, server.calls.count { it.idsOnly })
        }
}
