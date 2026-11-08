// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.execSQL
import androidx.room.useWriterConnection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.data.library.SyncWorld.session

/**
 * Spec 26, Part 4 (schema 11): every page write is guarded, inside its transaction, by the library row it writes to.
 * A stamped write (an add's walk) needs that stamp and no hold; an unstamped one needs no stamp and no hold; a missing
 * row refuses too, before the foreign key could throw. A refused write writes nothing (no item, no series, no
 * checkpoint) and moves no generation. A rewalk's write also refuses while the account runs a first sync; any other
 * write goes on. Invented libraries and names only.
 */
class StampedWritesTest {
    private val clock = FakeClock()
    private val opened = TestLibraries.inMemoryWithDatabase(clock, signedIn = listOf(ALICE))
    private val repository = opened.first
    private val database = opened.second
    private val adding = ItemId(hexId(0xB003))
    private val addingLibrary = Library(adding, "Invented Additions", LibraryKind.TvShows)

    @After
    fun close() = repository.close()

    private suspend fun choose() =
        repository.applySelection(
            ALICE,
            setOf(MOVIES, adding),
            mapOf(adding to STAMP),
            nowMs = clock.nowMs,
        )

    private suspend fun generation() = repository.contentGeneration(ALICE)

    private suspend fun page(
        library: ItemId,
        stamp: Long?,
        rewalk: Boolean = false,
        n: Int = 1,
    ) = repository.writePage(ALICE, library, stamp, rewalk, listOf(sitem(n, library = library)), 1, 1, run = 5)

    private suspend fun storedIn(library: ItemId) = repository.stored(ALICE).filter { it.library == library }

    // A row a chosen library already had, so the generation has left its first value.
    private suspend fun chosenRow() = repository.put(ALICE, MOVIES, sitem(100))

    private suspend fun assertNothingWritten(
        library: ItemId,
        generation: Long,
    ) {
        assertTrue("no item", storedIn(library).isEmpty())
        assertNull("no checkpoint", repository.checkpoint(ALICE, library))
        assertTrue("no series", repository.currentSeries(ALICE).none { it.libraryId == library.value })
        assertEquals("no bump", generation, generation())
    }

    @Test
    fun stampedWrite_matchingStamp_writes() =
        runBlocking {
            choose()

            val write = page(adding, STAMP)

            assertEquals(PageWrite(1, 0, 0), write.written().items)
            assertEquals(1, storedIn(adding).size)
            assertNotNull(repository.checkpoint(ALICE, adding))
        }

    @Test
    fun stampedWrite_otherStamp_returnsMismatch_writesNothing() =
        runBlocking {
            choose()
            chosenRow()
            val before = generation()

            assertEquals(PageResult.StampMismatch, page(adding, STAMP - 1))

            assertNothingWritten(adding, before)
        }

    @Test
    fun stampedWrite_heldRow_mismatch() =
        runBlocking {
            choose()
            repository.applySelection(
                ALICE,
                setOf(MOVIES),
                holds = mapOf(adding to clock.nowMs + 60_000),
                nowMs = clock.nowMs,
            )
            val before = generation()

            assertEquals("the hold cleared the stamp", PageResult.StampMismatch, page(adding, STAMP))
            // A row an older build could leave held and stamped: the hold alone refuses it.
            database.useWriterConnection {
                it.execSQL("UPDATE library SET addStamp = $STAMP WHERE libraryId = '${adding.value}'")
            }
            assertEquals("held and stamped", PageResult.StampMismatch, page(adding, STAMP))

            assertNothingWritten(adding, before)
        }

    @Test
    fun unstampedWrite_stampedRow_mismatch() =
        runBlocking {
            choose()
            val before = generation()

            assertEquals(PageResult.StampMismatch, page(adding, stamp = null))
            val items = repository.upsertItems(ALICE, adding, stamp = null, rewalk = false, listOf(sitem(2)))
            assertEquals(PageResult.StampMismatch, items)

            assertNothingWritten(adding, before)
        }

    @Test
    fun unstampedWrite_heldRow_mismatch() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))
            repository.applySelection(
                ALICE,
                setOf(MOVIES),
                holds = mapOf(SHOWS to clock.nowMs + 60_000),
                nowMs = clock.nowMs,
            )
            val before = generation()
            val rows = repository.currentRows(ALICE)

            val write =
                repository.upsertItems(
                    ALICE,
                    SHOWS,
                    stamp = null,
                    rewalk = false,
                    listOf(sitem(2, library = SHOWS)),
                )

            assertEquals("a held library's rows are kept, never written", PageResult.StampMismatch, write)
            assertEquals(rows, repository.currentRows(ALICE))
            assertEquals(before, generation())
        }

    @Test
    fun write_missingLibraryRow_mismatch_noConstraintThrow() =
        runBlocking {
            choose()
            val unknown = ItemId(hexId(0xB0FF))

            assertEquals(PageResult.StampMismatch, page(unknown, stamp = null))
            assertEquals(PageResult.StampMismatch, page(unknown, STAMP))
            val series = repository.upsertSeries(ALICE, unknown, stamp = null, rewalk = false, listOf(series("s-1")))
            assertEquals(PageResult.StampMismatch, series)

            assertEquals(0, repository.rowCounts().items)
            assertEquals(0, repository.rowCounts().series)
        }

    @Test
    fun rewalkWrite_firstSyncRunning_writesNothing() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            chosenRow()
            repository.beginFirstSync(ALICE)
            val before = generation()

            assertEquals("writePage", PageResult.FirstSyncRunning, page(SHOWS, stamp = null, rewalk = true))
            val items =
                repository.upsertItems(
                    ALICE,
                    SHOWS,
                    stamp = null,
                    rewalk = true,
                    listOf(sitem(2, library = SHOWS)),
                )
            assertEquals("upsertItems", PageResult.FirstSyncRunning, items)
            val series = repository.upsertSeries(ALICE, SHOWS, stamp = null, rewalk = true, listOf(series("s-1")))
            assertEquals("upsertSeries", PageResult.FirstSyncRunning, series)
            val hidden = hiddenSeriesRead(SHOWS, rewalk = true)
            assertEquals("the hidden-series write", PageResult.FirstSyncRunning, hidden.refused)

            assertNothingWritten(SHOWS, before)
        }

    @Test
    fun normalWrite_firstSyncRunning_stillWrites() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            repository.beginFirstSync(ALICE)

            page(SHOWS, stamp = null).written()
            repository.upsertSeries(ALICE, SHOWS, stamp = null, rewalk = false, listOf(series("s-1"))).written()
            val hidden = hiddenSeriesRead(SHOWS, rewalk = false)

            assertNull(hidden.refused)
            assertEquals(1, storedIn(SHOWS).size)
            assertEquals(2, repository.currentSeries(ALICE).count { it.libraryId == SHOWS.value })
        }

    @Test
    fun mismatchSeries_upsertSeries_writesNothing() =
        runBlocking {
            choose()
            chosenRow()
            val before = generation()

            val write = repository.upsertSeries(ALICE, adding, stamp = null, rewalk = false, listOf(series("s-1")))
            val hidden = hiddenSeriesRead(adding, rewalk = false)

            assertEquals(PageResult.StampMismatch, write)
            assertEquals(PageResult.StampMismatch, hidden.refused)
            assertNothingWritten(adding, before)
        }

    // LOW-3: a refused hidden-series write leaves the read's memory as it was, so the next pass asks the same again.
    @Test
    fun refusedHiddenSeriesWrite_leavesTheMemoryAlone() =
        runBlocking {
            choose()
            val memory = HiddenSeriesMemory()
            memory.remember(ALICE, adding, setOf(ItemId("s-ghost")))
            memory.moveOffset(ALICE, adding, 5)
            val server = FakeMediaServer().apply { seriesByIdResult = ServerResult.Success(series("s-hidden")) }

            val read =
                server.readHiddenSeries(
                    session,
                    repository,
                    ALICE,
                    addingLibrary,
                    setOf(ItemId("s-hidden")),
                    memory,
                    confirmAbsent = true,
                    stamp = null,
                    rewalk = false,
                )

            assertEquals(PageResult.StampMismatch, read.refused)
            assertEquals("nothing forgotten", setOf(ItemId("s-ghost")), memory.absent(ALICE, adding))
            assertEquals("no offset moved", 5, memory.offset(ALICE, adding))
        }

    // The hidden-series read of [library] finding one series by ID, and storing it under no stamp.
    private suspend fun hiddenSeriesRead(
        library: ItemId,
        rewalk: Boolean,
    ): HiddenSeriesRead {
        val server = FakeMediaServer().apply { seriesByIdResult = ServerResult.Success(series("s-hidden")) }
        val target = if (library == adding) addingLibrary else Library(library, "Invented", LibraryKind.TvShows)
        return server.readHiddenSeries(
            session,
            repository,
            ALICE,
            target,
            setOf(ItemId("s-hidden")),
            HiddenSeriesMemory(),
            confirmAbsent = false,
            stamp = null,
            rewalk = rewalk,
        )
    }

    private companion object {
        const val STAMP = 7L
    }
}
