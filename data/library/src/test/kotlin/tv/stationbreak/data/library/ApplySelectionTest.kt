// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ListLibrarySource
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows

/**
 * Spec 26, Part 3: `applySelection` is the one writer of the cached library set. A library in `keep` has no hold; any
 * other is kept while its hold lies ahead and deleted otherwise; a token it did not have makes it walk again from the
 * start; the content generation moves only when a library's rows go that was not being added. The first sync uses it
 * and never lands a library being added. Invented libraries and names only.
 */
class ApplySelectionTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val extra = ItemId(hexId(0xB003))
    private val gone = ItemId(hexId(0xB004))

    @After
    fun close() = repository.close()

    private suspend fun rows() = repository.dao.libraries("server-1", "alice").associateBy { ItemId(it.libraryId) }

    private suspend fun row(library: ItemId) = rows()[library]

    private suspend fun generation() = repository.contentGeneration(ALICE)

    // One item in [library] and its checkpoint, as a page of the first sync leaves them; then the library is fetched.
    private suspend fun fill(
        library: ItemId,
        n: Int,
    ) {
        repository.writePage(ALICE, library, listOf(sitem(n, library = library)), 1, 1, run = clock.nowMs)
        repository.markFetched(ALICE, library)
    }

    private suspend fun storedIn(library: ItemId) = repository.stored(ALICE).filter { it.library == library }

    private suspend fun apply(
        keep: Set<ItemId>,
        addTokens: Map<ItemId, Long> = emptyMap(),
        holds: Map<ItemId, Long> = emptyMap(),
    ) = repository.applySelection(ALICE, keep, addTokens, holds, clock.nowMs)

    @Test
    fun applySelection_keepsUnexpiredHold_dropsExpired() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(MOVIES, 1)
            fill(SHOWS, 2)
            val until = clock.nowMs + 60_000

            apply(setOf(MOVIES), holds = mapOf(SHOWS to until))

            assertEquals(until, row(SHOWS)?.heldUntilMs)
            assertEquals("a held library keeps its rows", 1, storedIn(SHOWS).size)
            assertNull(row(MOVIES)?.heldUntilMs)

            clock.stepMs(59_999)
            apply(setOf(MOVIES))
            assertEquals("a call that names no hold keeps the one it has", until, row(SHOWS)?.heldUntilMs)
            assertEquals(1, storedIn(SHOWS).size)

            clock.stepMs(1)
            apply(setOf(MOVIES))
            assertNull("a hold at now has expired", row(SHOWS))
            assertTrue(storedIn(SHOWS).isEmpty())
            assertNull(repository.checkpoint(ALICE, SHOWS))
            assertEquals(1, storedIn(MOVIES).size)
        }

    @Test
    fun aHoldAlreadyPastOrForALibraryNotStoredHoldsNothing() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)

            apply(setOf(MOVIES), holds = mapOf(SHOWS to clock.nowMs, extra to clock.nowMs + 60_000))

            assertNull(row(SHOWS))
            assertNull("a hold never makes a library", row(extra))
        }

    @Test
    fun applySelection_reTickClearsHold() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            apply(setOf(MOVIES), holds = mapOf(SHOWS to clock.nowMs + 60_000))

            apply(setOf(MOVIES, SHOWS))

            val shows = row(SHOWS)!!
            assertNull(shows.heldUntilMs)
            assertTrue("a re-tick with no token keeps what was fetched", shows.fetched)
            assertNull(shows.addToken)
            assertNotNull(repository.checkpoint(ALICE, SHOWS))
            assertEquals(1, storedIn(SHOWS).size)
            // The hold is gone: a later untick with no hold drops the library.
            apply(setOf(MOVIES))
            assertNull(row(SHOWS))
        }

    @Test
    fun aHoldNamedForALibraryInKeepIsIgnored() =
        runBlocking {
            apply(setOf(MOVIES), holds = mapOf(MOVIES to clock.nowMs + 60_000))

            assertNull(row(MOVIES)?.heldUntilMs)
        }

    @Test
    fun retickHeldLibrary_rewalks() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            apply(setOf(MOVIES), holds = mapOf(SHOWS to clock.nowMs + 60_000))

            apply(setOf(MOVIES, SHOWS), addTokens = mapOf(SHOWS to 7L))

            val shows = row(SHOWS)!!
            assertFalse("a token it did not have makes it pending", shows.fetched)
            assertEquals(7L, shows.addToken)
            assertNull(shows.heldUntilMs)
            assertNull("and its walk starts again", repository.checkpoint(ALICE, SHOWS))
            assertEquals("its rows stay until the walk replaces them", 1, storedIn(SHOWS).size)
            assertTrue(SHOWS in repository.pendingLibraries(ALICE))
        }

    @Test
    fun theSameTokenAgainChangesNothingAndNoTokenKeepsTheOneItHas() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS), addTokens = mapOf(SHOWS to 7L))
            // The walk of the add saved a checkpoint; the same token again must not throw it away.
            repository.writePage(ALICE, SHOWS, listOf(sitem(2, library = SHOWS)), 1, 1, run = clock.nowMs)

            apply(setOf(MOVIES, SHOWS), addTokens = mapOf(SHOWS to 7L))
            assertNotNull(repository.checkpoint(ALICE, SHOWS))
            apply(setOf(MOVIES, SHOWS))
            assertEquals(7L, row(SHOWS)?.addToken)
            assertNotNull(repository.checkpoint(ALICE, SHOWS))

            apply(setOf(MOVIES, SHOWS), addTokens = mapOf(SHOWS to 8L))
            assertEquals(8L, row(SHOWS)?.addToken)
            assertNull("a new token walks again", repository.checkpoint(ALICE, SHOWS))
        }

    @Test
    fun anEarlierLibraryAddedAgainIsNewAndPendingWithItsToken() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            apply(setOf(MOVIES))
            assertNull(row(SHOWS))

            apply(setOf(MOVIES, SHOWS), addTokens = mapOf(SHOWS to 9L))

            val shows = row(SHOWS)!!
            assertFalse(shows.fetched)
            assertEquals(9L, shows.addToken)
            assertTrue(storedIn(SHOWS).isEmpty())
        }

    @Test
    fun aTokenForALibraryOutsideKeepIsIgnored() =
        runBlocking {
            apply(setOf(MOVIES), addTokens = mapOf(SHOWS to 3L))

            assertEquals(setOf(MOVIES), rows().keys)
        }

    @Test
    fun applySelection_tickDoesNotBump_deleteAndExpiryBumpOnce() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(MOVIES, 1)
            fill(SHOWS, 2)
            val start = generation()

            apply(setOf(MOVIES, SHOWS, extra), addTokens = mapOf(extra to 1L))
            assertEquals("a tick with a token", start, generation())
            apply(setOf(MOVIES, SHOWS, extra, gone))
            assertEquals("a tick with no token", start, generation())
            apply(setOf(MOVIES, SHOWS, gone))
            assertEquals("an add dropped before it held a row", start, generation())

            apply(setOf(MOVIES, SHOWS, gone, extra), addTokens = mapOf(extra to 2L))
            repository.writePage(ALICE, extra, listOf(sitem(3, library = extra)), 1, 1, run = clock.nowMs)
            assertEquals("a page of a library being added", start, generation())
            apply(setOf(MOVIES, SHOWS, gone))
            assertEquals("an add dropped after its walk wrote rows: they may be a day's", start + 1, generation())

            apply(setOf(MOVIES, SHOWS, gone), holds = mapOf(MOVIES to clock.nowMs + 10_000))
            apply(setOf(SHOWS, gone), holds = mapOf(MOVIES to clock.nowMs + 10_000))
            assertEquals("a hold set", start + 1, generation())

            apply(setOf(MOVIES))
            assertEquals("the two chosen libraries deleted in one call: one bump", start + 2, generation())

            apply(setOf(SHOWS), holds = mapOf(MOVIES to clock.nowMs + 10_000))
            assertEquals("a hold set", start + 2, generation())
            clock.stepMs(5_000)
            apply(setOf(SHOWS))
            assertEquals("a hold not yet ended", start + 2, generation())
            clock.stepMs(5_000)
            apply(setOf(SHOWS))
            assertEquals("a hold that expired", start + 3, generation())
            apply(setOf(SHOWS))
            assertEquals("nothing left to delete", start + 3, generation())
        }

    @Test
    fun providerIdsOnlyChange_doesNotBumpGeneration() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            val film = sitem(1).copy(providerIds = mapOf("imdb" to "tt0000001", "tmdb" to "101"))
            repository.put(ALICE, MOVIES, film)
            repository.putSeries(ALICE, SHOWS, series(hexId(500)).copy(providerIds = mapOf("tvdb" to "5001")))
            val before = generation()
            val versions = repository.versions(ALICE).size
            val seriesVersions = repository.seriesVersions(ALICE).size

            val changed = film.copy(providerIds = mapOf("imdb" to "tt0000002"))
            repository.put(ALICE, MOVIES, changed)
            repository.putSeries(ALICE, SHOWS, series(hexId(500)).copy(providerIds = mapOf("tvdb" to "5002")))

            assertEquals(before, generation())
            assertEquals("no version for provider ids", versions, repository.versions(ALICE).size)
            assertEquals(seriesVersions, repository.seriesVersions(ALICE).size)
            assertEquals("imdb=tt0000002", repository.currentRows(ALICE).single().providerIds)
            assertEquals(
                mapOf("imdb" to "tt0000002"),
                repository
                    .stored(ALICE)
                    .single()
                    .item.providerIds,
            )
            assertEquals("tvdb=5002", repository.currentSeries(ALICE).single().providerIds)

            // A field the scheduler reads still moves it.
            repository.put(ALICE, MOVIES, changed.copy(name = "Renamed Invented Film"))
            assertEquals(before + 1, generation())
        }

    @Test
    fun aRowThatMovesFromAChosenLibraryIntoOneBeingAddedMovesTheGeneration() =
        runBlocking {
            apply(setOf(MOVIES, extra), addTokens = mapOf(extra to 1L))
            repository.put(ALICE, MOVIES, sitem(1))
            val before = generation()

            repository.put(ALICE, extra, sitem(1, library = extra))

            assertEquals("the chosen library lost the item", before + 1, generation())
        }

    // A held library's rows are a day's; re-ticked (so it is an add) and dropped again, they go with a bump.
    @Test
    fun aHeldLibraryReTickedAndDroppedBeforeItLandsStillMovesTheGeneration() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            apply(setOf(MOVIES), holds = mapOf(SHOWS to clock.nowMs + 60_000))
            apply(setOf(MOVIES, SHOWS), addTokens = mapOf(SHOWS to 3L))
            val before = generation()

            apply(setOf(MOVIES))

            assertNull(row(SHOWS))
            assertEquals(before + 1, generation())
        }

    @Test
    fun anAccountNotSignedInGetsNoRows() =
        runBlocking {
            repository.applySelection(BOB, setOf(MOVIES), nowMs = clock.nowMs)

            assertTrue(repository.dao.libraries("server-1", "bob").isEmpty())
        }

    // The first sync.

    private val films = SyncWorld.source("m", 3)
    private val episodes = SyncWorld.source("e", 2, SHOWS)
    private val extras = SyncWorld.source("x", 2, extra)
    private val extraLibrary = Library(extra, "Invented Extra", LibraryKind.Movies)

    private fun server(vararg libraries: Pair<Library, ListLibrarySource>) = SyncWorld.server(*libraries)

    @Test
    fun firstSync_neverMarksAddingFetched() =
        runBlocking {
            val server = server(movies to films, extraLibrary to extras)
            // extra is being added and listed; gone is being added and not listed (the "pending but not listed" step).
            apply(setOf(MOVIES, extra, gone), addTokens = mapOf(extra to 1L, gone to 2L))

            val outcome = SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES, extra, gone))

            assertEquals(SyncOutcome.Completed, outcome)
            assertEquals(setOf(extra, gone), repository.pendingLibraries(ALICE))
            assertEquals("the add was walked", 2, storedIn(extra).size)
            assertTrue(row(MOVIES)!!.fetched)
        }

    @Test
    fun firstSync_usesApplySelection_keepsHeldAndAdding() =
        runBlocking {
            val server = server(movies to films, shows to episodes, extraLibrary to extras)
            val engine = SyncWorld.engine(repository, server, clock)
            assertEquals(SyncOutcome.Completed, engine.run(session, ALICE, setOf(MOVIES, SHOWS)))
            val until = clock.nowMs + 60_000
            apply(setOf(MOVIES, extra), addTokens = mapOf(extra to 4L), holds = mapOf(SHOWS to until))
            val before = generation()

            // The first sync knows only the chosen set: the held library and the add under way both stay.
            assertEquals(SyncOutcome.Completed, engine.run(session, ALICE, setOf(MOVIES)))

            assertEquals(until, row(SHOWS)?.heldUntilMs)
            assertEquals(2, storedIn(SHOWS).size)
            assertEquals(4L, row(extra)?.addToken)
            assertEquals(before, generation())

            clock.stepMs(60_000)
            assertEquals(SyncOutcome.Completed, engine.run(session, ALICE, setOf(MOVIES)))
            assertNull("the hold ended", row(SHOWS))
            assertTrue(storedIn(SHOWS).isEmpty())
            assertEquals(4L, row(extra)?.addToken)
            assertTrue(generation() > before)
        }
}
