// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ListLibrarySource
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 06 task 3.3: a library that changes while the first sync walks it. `libraryItems` is stable only for a library
 * that does not change, so items added, removed or renamed mid-walk shift the offsets. The walk reads on until a page
 * comes back empty, and a post-walk changed-since pass, from the walk's start minus the margin, picks up what the walk
 * could have missed (a new item, a renamed one). The first sync still ends; the rest is the next incremental pass.
 */
class SizeChangeMidSyncTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val library = SyncWorld.source("m", 300)
    private val server = PagedMediaServer(listOf(movies), mapOf(MOVIES to library))

    @After
    fun close() = repository.close()

    private suspend fun run(): SyncOutcome {
        repository.useAccount(ALICE)
        return SyncWorld.engine(repository, server, clock, pageSize = 100).run(session, ALICE, setOf(MOVIES))
    }

    private suspend fun storedIds() = repository.stored(ALICE).map { it.item.id.value }.toSet()

    /** Changes the library just before the second page is served, as the server would while the walk is in flight. */
    private fun duringTheWalk(change: () -> Unit) {
        var changed = false
        server.respond = { call ->
            if (call.startIndex == 100 && call.changedSince == null && !call.idsOnly && !changed) {
                changed = true
                clock.instant += 1.minutes
                change()
            }
            null
        }
    }

    private fun newItem(number: Int): MediaItem = SyncWorld.item("new", number).copy(dateCreated = clock.now())

    @Test
    fun itemsAddedAtTheEndAreSavedAndTheWalkEndsOnAnEmptyPageNotOnTheOldTotal() =
        runBlocking {
            duringTheWalk {
                (0 until 30).forEach { library.items += newItem(it) }
            }

            val outcome = run()

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(330, storedIds().size)
            assertEquals(SyncState.Idle, repository.activeStatus().first()?.state)
            // Page one's total (300) is kept for progress, but the walk read on to the real end.
            assertEquals(300, repository.checkpoint(ALICE, MOVIES)?.totalCount)
            assertEquals(330, repository.checkpoint(ALICE, MOVIES)?.nextStartIndex)
        }

    @Test
    fun itemsAddedAtTheStartShiftOffsetsAndNothingIsLost() =
        runBlocking {
            duringTheWalk {
                library.items.addAll(0, (0 until 5).map(::newItem))
            }

            run()

            // Five items moved down across a page boundary and were read twice (an idempotent upsert), none was lost.
            assertEquals(305, storedIds().size)
            assertEquals(305, repository.rowCounts().items)
        }

    @Test
    fun aRenamedItemEndsUpWithItsNewName() =
        runBlocking {
            duringTheWalk {
                // Renamed, so it sorts elsewhere (moved to the end) and was saved just now.
                val renamed = library.items.removeAt(150).copy(name = "Renamed mid-walk")
                library.items += renamed
                library.saved[renamed.id] = clock.now()
            }

            run()

            assertEquals(300, storedIds().size)
            val names = repository.stored(ALICE).map { it.item.name }
            assertEquals(1, names.count { it == "Renamed mid-walk" })
        }

    @Test
    fun aRenamedItemThatMovedBehindTheWalkIsFoundByThePostWalkPass() =
        runBlocking {
            duringTheWalk {
                // Renamed so that it now sorts near the front, which the walk has already passed.
                val renamed = library.items.removeAt(250).copy(name = "Renamed to sort first")
                library.items.add(5, renamed)
                library.saved[renamed.id] = clock.now()
            }

            run()

            val stored = repository.stored(ALICE).associate { it.item.id.value to it.item.name }
            assertEquals(300, stored.size)
            assertEquals("Renamed to sort first", stored["m-00250"])
            val walked = server.itemCalls.filter { it.changedSince == null }.maxOf { it.number }
            val pass = server.itemCalls.first { it.changedSince != null }
            assertTrue("the pass runs after the walk", pass.number > walked)
        }

    @Test
    fun aLibraryThatShrinksAtTheEndStillFinishes() =
        runBlocking {
            duringTheWalk {
                repeat(80) { library.items.removeAt(library.items.lastIndex) }
            }

            val outcome = run()

            assertEquals(SyncOutcome.Completed, outcome)
            // Pages 0, 100 and 200 are read as far as they go; nothing is added back that is not there.
            assertEquals(220, storedIds().size)
            assertEquals(SyncState.Idle, repository.activeStatus().first()?.state)
        }

    @Test
    fun theFirstSyncEndsWithAChangedSincePassFromTheWalksStartMinusTheMargin() =
        runBlocking {
            run()

            val changedSince = server.itemCalls.filter { it.changedSince != null }
            assertTrue(changedSince.isNotEmpty())
            assertEquals(EPOCH - 10.minutes, changedSince.first().changedSince)
            // It comes after every page of the walk.
            val lastWalk = server.itemCalls.indexOfLast { it.changedSince == null }
            assertTrue(server.itemCalls.indexOfFirst { it.changedSince != null } > lastWalk)
        }

    @Test
    fun anEmptyLibraryIsAFinishedLibrary() =
        runBlocking {
            val empty = PagedMediaServer(listOf(movies), mapOf(MOVIES to ListLibrarySource()))
            repository.useAccount(ALICE)

            val outcome = SyncWorld.engine(repository, empty, clock).run(session, ALICE, setOf(MOVIES))

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(emptySet<ItemId>(), repository.pendingLibraries(ALICE))
        }
}
