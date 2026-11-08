// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session

/**
 * Spec 26, Part 4 (schema 11, Amendment 4): the old mark-fetched is three writes. `markWalked` (the first sync's walk
 * end) fetches the library and clears a rewalk mark, whether or not a first sync runs. `markUnlisted` (a chosen library
 * the server no longer lists) fetches only an unmarked one: a marked library stays pending and marked. `finishRewalk`
 * fetches a marked one, clears its mark and writes its coverage, except while a first sync runs. None touches a
 * library being added or held. Invented libraries only.
 */
class MarkFetchedSplitTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val held = ItemId(hexId(0xB005))
    private val adding = ItemId(hexId(0xB003))

    @After
    fun close() = repository.close()

    private suspend fun row(library: ItemId): LibraryEntity {
        val rows = repository.dao.libraries("server-1", "alice")
        return rows.single { it.libraryId == library.value }
    }

    // SHOWS is chosen and marked for a rewalk (its unproven add reverted); MOVIES is chosen and unmarked.
    private suspend fun marked() {
        repository.applySelection(ALICE, setOf(MOVIES, SHOWS), mapOf(SHOWS to 3L), nowMs = clock.nowMs)
        assertTrue(repository.revertUnproven(ALICE, SHOWS, 3L))
    }

    @Test
    fun markWalked_clearsMark_evenWhileFirstSyncRunning() =
        runBlocking {
            marked()
            repository.beginFirstSync(ALICE)

            repository.markWalked(ALICE, SHOWS)

            assertTrue(row(SHOWS).fetched)
            assertFalse(row(SHOWS).rewalkPending)
            assertNull("the first sync's walk end writes no coverage", row(SHOWS).coveredFromMs)
        }

    @Test
    fun markUnlisted_skipsMarkedRow() =
        runBlocking {
            marked()

            repository.markUnlisted(ALICE, SHOWS)

            assertFalse("still pending", row(SHOWS).fetched)
            assertTrue("and marked", row(SHOWS).rewalkPending)
        }

    @Test
    fun markUnlisted_fetchesUnmarked() =
        runBlocking {
            marked()

            repository.markUnlisted(ALICE, MOVIES)

            assertTrue(row(MOVIES).fetched)
            assertFalse(row(MOVIES).rewalkPending)
        }

    @Test
    fun finishRewalk_firstSyncRunning_writesNothing() =
        runBlocking {
            marked()
            repository.beginFirstSync(ALICE)
            val stored = row(SHOWS)

            assertEquals(RewalkFinish.FirstSyncRunning, repository.finishRewalk(ALICE, SHOWS, RUN))

            assertEquals(stored, row(SHOWS))
        }

    @Test
    fun finishRewalk_unmarked_NotMarked() =
        runBlocking {
            marked()
            val stored = row(MOVIES)

            assertEquals(RewalkFinish.NotMarked, repository.finishRewalk(ALICE, MOVIES, RUN))
            assertEquals(RewalkFinish.NotMarked, repository.finishRewalk(ALICE, ItemId(hexId(0xB0FF)), RUN))

            assertEquals(stored, row(MOVIES))
            assertEquals(RewalkFinish.Finished, repository.finishRewalk(ALICE, SHOWS, RUN))
            assertEquals(
                "a second finish finds no mark",
                RewalkFinish.NotMarked,
                repository.finishRewalk(ALICE, SHOWS, RUN),
            )
        }

    @Test
    fun none_touchStampedOrHeld() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, held, adding), mapOf(adding to 4L), nowMs = clock.nowMs)
            repository.applySelection(
                ALICE,
                setOf(MOVIES, adding),
                holds = mapOf(held to clock.nowMs + 60_000),
                nowMs = clock.nowMs,
            )
            val before = listOf(row(held), row(adding))
            val generation = repository.contentGeneration(ALICE)

            for (library in listOf(held, adding)) {
                repository.markWalked(ALICE, library)
                repository.markUnlisted(ALICE, library)
                assertEquals(RewalkFinish.NotMarked, repository.finishRewalk(ALICE, library, RUN))
            }

            assertEquals(before, listOf(row(held), row(adding)))
            assertEquals("none of them moves the generation", generation, repository.contentGeneration(ALICE))
        }

    // The first sync's walk of a marked library is a walk from the start with no stamp: its end clears the mark.
    @Test
    fun firstSync_walksAMarkedLibrary_andClearsTheMark() =
        runBlocking {
            repository.useAccount(ALICE)
            marked()
            val server =
                PagedMediaServer(
                    listOf(movies, SyncWorld.shows),
                    mapOf(MOVIES to SyncWorld.source("m", 3), SHOWS to SyncWorld.source("s", 2, SHOWS)),
                )

            val outcome = SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES, SHOWS))

            assertEquals(SyncOutcome.Completed, outcome)
            assertTrue(row(SHOWS).fetched)
            assertFalse(row(SHOWS).rewalkPending)
            assertEquals(2, repository.stored(ALICE).count { it.library == SHOWS })
        }

    private companion object {
        const val RUN = 1_699_000_000_000L
    }
}
