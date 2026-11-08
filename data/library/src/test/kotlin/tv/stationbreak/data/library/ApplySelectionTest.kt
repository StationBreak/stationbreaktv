// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.execSQL
import androidx.room.useWriterConnection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * other is kept while its hold lies ahead and deleted otherwise; a stamp it did not have makes it walk again from the
 * start; the content generation moves only when a library's rows go that was not being added. The first sync uses it
 * and never lands a library being added. Invented libraries and names only.
 */
class ApplySelectionTest {
    private val clock = FakeClock()
    private val opened = TestLibraries.inMemoryWithDatabase(clock, signedIn = listOf(ALICE))
    private val repository = opened.first
    private val database = opened.second
    private val extra = ItemId(hexId(0xB003))
    private val gone = ItemId(hexId(0xB004))

    @After
    fun close() = repository.close()

    private suspend fun rows() = repository.dao.libraries("server-1", "alice").associateBy { ItemId(it.libraryId) }

    private suspend fun row(library: ItemId) = rows()[library]

    private suspend fun generation() = repository.contentGeneration(ALICE)

    // A row as an older build of this branch could leave it, held and stamped: the code no longer writes one.
    private suspend fun stampHeld(
        library: ItemId,
        stamp: Long,
    ) = database.useWriterConnection {
        it.execSQL("UPDATE library SET addStamp = $stamp WHERE libraryId = '${library.value}' AND heldUntilMs IS NOT NULL")
    }

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
        addStamps: Map<ItemId, Long> = emptyMap(),
        holds: Map<ItemId, Long> = emptyMap(),
    ) = repository.applySelection(ALICE, keep, addStamps, holds, clock.nowMs)

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
            assertTrue("a re-tick with no stamp keeps what was fetched", shows.fetched)
            assertNull(shows.addStamp)
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

            apply(setOf(MOVIES, SHOWS), addStamps = mapOf(SHOWS to 7L))

            val shows = row(SHOWS)!!
            assertFalse("a stamp it did not have makes it pending", shows.fetched)
            assertEquals(7L, shows.addStamp)
            assertNull(shows.heldUntilMs)
            assertNull("and its walk starts again", repository.checkpoint(ALICE, SHOWS))
            assertEquals("its rows stay until the walk replaces them", 1, storedIn(SHOWS).size)
            assertTrue(SHOWS in repository.pendingLibraries(ALICE))
        }

    @Test
    fun theSameStampAgainChangesNothingAndNoStampKeepsTheOneItHas() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS), addStamps = mapOf(SHOWS to 7L))
            // The walk of the add saved a checkpoint; the same stamp again must not throw it away.
            repository.writePage(ALICE, SHOWS, listOf(sitem(2, library = SHOWS)), 1, 1, run = clock.nowMs)

            apply(setOf(MOVIES, SHOWS), addStamps = mapOf(SHOWS to 7L))
            assertNotNull(repository.checkpoint(ALICE, SHOWS))
            apply(setOf(MOVIES, SHOWS))
            assertEquals(7L, row(SHOWS)?.addStamp)
            assertNotNull(repository.checkpoint(ALICE, SHOWS))

            apply(setOf(MOVIES, SHOWS), addStamps = mapOf(SHOWS to 8L))
            assertEquals(8L, row(SHOWS)?.addStamp)
            assertNull("a new stamp walks again", repository.checkpoint(ALICE, SHOWS))
        }

    @Test
    fun anEarlierLibraryAddedAgainIsNewAndPendingWithItsStamp() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            apply(setOf(MOVIES))
            assertNull(row(SHOWS))

            apply(setOf(MOVIES, SHOWS), addStamps = mapOf(SHOWS to 9L))

            val shows = row(SHOWS)!!
            assertFalse(shows.fetched)
            assertEquals(9L, shows.addStamp)
            assertTrue(storedIn(SHOWS).isEmpty())
        }

    @Test
    fun aStampForALibraryOutsideKeepIsIgnored() =
        runBlocking {
            apply(setOf(MOVIES), addStamps = mapOf(SHOWS to 3L))

            assertEquals(setOf(MOVIES), rows().keys)
        }

    @Test
    fun applySelection_tickDoesNotBump_deleteAndExpiryBumpOnce() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(MOVIES, 1)
            fill(SHOWS, 2)
            val start = generation()

            apply(setOf(MOVIES, SHOWS, extra), addStamps = mapOf(extra to 1L))
            assertEquals("a tick with a stamp", start, generation())
            apply(setOf(MOVIES, SHOWS, extra, gone))
            assertEquals("a tick with no stamp", start, generation())
            apply(setOf(MOVIES, SHOWS, gone))
            assertEquals("an add dropped before it held a row", start, generation())

            apply(setOf(MOVIES, SHOWS, gone, extra), addStamps = mapOf(extra to 2L))
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
            apply(setOf(MOVIES, extra), addStamps = mapOf(extra to 1L))
            repository.put(ALICE, MOVIES, sitem(1))
            val before = generation()

            repository.put(ALICE, extra, sitem(1, library = extra))

            assertEquals("the chosen library lost the item", before + 1, generation())
        }

    // Whole-part fix (data MED-1): a series row feeds the episodes of other libraries by its id, so a series write
    // moves the generation whatever library holds the row, an add included.
    @Test
    fun aSharedSeriesOwnedByALibraryBeingAddedMovesTheGenerationWhenItsRatingChanges() =
        runBlocking {
            val show = 910
            apply(setOf(MOVIES, extra), addStamps = mapOf(extra to 1L))
            repository.put(ALICE, MOVIES, episodeOf(1, show, library = MOVIES))
            repository.putSeries(ALICE, extra, series(hexId(show), "TV-PG"))
            assertEquals(extra.value, repository.currentSeries(ALICE).single().libraryId)
            val before = generation()

            repository.putSeries(ALICE, extra, series(hexId(show), "TV-MA"))

            assertEquals("the chosen library's episodes read the new rating", before + 1, generation())
        }

    // --- Landing an add (whole-part fix, arch MED-3 / data MED-2) ---------------------------------------------------

    @Test
    fun landAdd_withItsStamp_clearsIt_marksItFetched_andMovesTheGenerationOnce() =
        runBlocking {
            apply(setOf(MOVIES, extra), addStamps = mapOf(extra to 7L))
            // A chosen library's row first, so the generation has left its first value (a bump is then +1).
            fill(MOVIES, 1)
            repository.writePage(ALICE, extra, listOf(sitem(3, library = extra)), 1, 1, run = clock.nowMs)
            repository.markFetched(ALICE, extra)
            assertTrue("markFetched never lands an add", extra in repository.pendingLibraries(ALICE))
            val before = generation()

            assertTrue(repository.landAdd(ALICE, extra, 7L))

            val landed = checkNotNull(row(extra))
            assertNull(landed.addStamp)
            assertTrue(landed.fetched)
            assertFalse(extra in repository.pendingLibraries(ALICE))
            assertEquals("once", before + 1, generation())
            assertFalse("a second landing finds no stamp", repository.landAdd(ALICE, extra, 7L))
            assertEquals(before + 1, generation())
        }

    @Test
    fun landAdd_withAnotherStamp_changesNothing() =
        runBlocking {
            apply(setOf(MOVIES, extra), addStamps = mapOf(extra to 7L))
            // A chosen library's row first, so the generation has left its first value (a bump is then +1).
            fill(MOVIES, 1)
            val before = generation()
            val stored = row(extra)

            assertFalse(repository.landAdd(ALICE, extra, 6L))
            assertFalse("a library with no row", repository.landAdd(ALICE, gone, 7L))

            assertEquals(stored, row(extra))
            assertEquals(before, generation())
        }

    @Test
    fun afterLanding_pageWritesToTheLibraryMoveTheGenerationAgain() =
        runBlocking {
            apply(setOf(MOVIES, extra), addStamps = mapOf(extra to 7L))
            // A chosen library's row first, so the generation has left its first value (a bump is then +1).
            fill(MOVIES, 1)
            repository.writePage(ALICE, extra, listOf(sitem(3, library = extra)), 1, 1, run = clock.nowMs)
            val beforeLanding = generation()
            repository.landAdd(ALICE, extra, 7L)
            val landed = generation()
            assertEquals(beforeLanding + 1, landed)

            repository.put(ALICE, extra, sitem(4, library = extra))

            assertEquals("a landed library is a chosen one", landed + 1, generation())
        }

    @Test
    fun holdingALibraryBeingAddedClearsItsStamp() =
        runBlocking {
            apply(setOf(MOVIES, extra), addStamps = mapOf(extra to 7L))
            repository.writePage(ALICE, extra, listOf(sitem(3, library = extra)), 1, 1, run = clock.nowMs)

            apply(setOf(MOVIES), holds = mapOf(extra to clock.nowMs + 60_000))

            val held = checkNotNull(row(extra))
            assertEquals(clock.nowMs + 60_000, held.heldUntilMs)
            assertNull("a held library is never adding", held.addStamp)
            assertFalse("so it can never land", repository.landAdd(ALICE, extra, 7L))
        }

    @Test
    fun theFirstSyncNeverKeepsAHeldRowByItsStamp() =
        runBlocking {
            apply(setOf(MOVIES, extra), addStamps = mapOf(extra to 7L))
            repository.writePage(ALICE, extra, listOf(sitem(3, library = extra)), 1, 1, run = clock.nowMs)
            apply(setOf(MOVIES), holds = mapOf(extra to clock.nowMs + 1_000))
            // A row left with both (as an older build of this branch could write) is kept only by its hold.
            stampHeld(extra, 7L)
            assertEquals(7L, row(extra)?.addStamp)
            clock.stepMs(1_000)

            repository.applySelectionKeepingAdds(ALICE, setOf(MOVIES), clock.nowMs)

            assertNull("the hold ended, so it went: its stamp kept nothing", row(extra))
        }

    @Test
    fun landAdd_onAHeldRowThatStillHasTheStamp_changesNothing() =
        runBlocking {
            apply(setOf(MOVIES, extra), addStamps = mapOf(extra to 7L))
            fill(MOVIES, 1)
            repository.writePage(ALICE, extra, listOf(sitem(3, library = extra)), 1, 2, run = clock.nowMs)
            apply(setOf(MOVIES), holds = mapOf(extra to clock.nowMs + 60_000))
            stampHeld(extra, 7L)
            val before = generation()
            val stored = row(extra)

            assertFalse(repository.landAdd(ALICE, extra, 7L))

            assertEquals(stored, row(extra))
            assertEquals(before, generation())
        }

    @Test
    fun landAdd_forAnAccountNotSignedIn_changesNothing() =
        runBlocking {
            apply(setOf(MOVIES, extra), addStamps = mapOf(extra to 7L))
            fill(MOVIES, 1)
            val before = generation()
            val stored = row(extra)
            database.useWriterConnection { it.execSQL("DELETE FROM active_account") }

            assertFalse(repository.landAdd(ALICE, extra, 7L))

            assertEquals(stored, row(extra))
            assertEquals(before, generation())
        }

    // Round 2 (data MED-A): an untick of a partly walked add holds it; the sync must not then call it fetched.
    @Test
    fun aHeldPartlyWalkedAddIsNeverMarkedFetchedByTheSync() =
        runBlocking {
            val server = server(movies to films, extraLibrary to extras)
            apply(setOf(MOVIES, extra), addStamps = mapOf(extra to 1L))
            repository.writePage(ALICE, extra, listOf(sitem(3, library = extra)), 1, 2, run = clock.nowMs)
            apply(setOf(MOVIES), holds = mapOf(extra to clock.nowMs + 60_000))

            val outcome = SyncWorld.engine(repository, server, clock).run(session, ALICE, setOf(MOVIES))

            assertEquals(SyncOutcome.Completed, outcome)
            assertFalse("held, so never fetched", checkNotNull(row(extra)).fetched)
            assertTrue("and still pending", extra in repository.pendingLibraries(ALICE))
        }

    // A held library's rows are a day's; re-ticked (so it is an add) and dropped again, they go with a bump.
    @Test
    fun aHeldLibraryReTickedAndDroppedBeforeItLandsStillMovesTheGeneration() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            apply(setOf(MOVIES), holds = mapOf(SHOWS to clock.nowMs + 60_000))
            apply(setOf(MOVIES, SHOWS), addStamps = mapOf(SHOWS to 3L))
            val before = generation()

            apply(setOf(MOVIES))

            assertNull(row(SHOWS))
            assertEquals(before + 1, generation())
        }

    // Fix round 2 (Vimes). The purge's forward-jump guard: a clock far past the last success expires no hold.
    @Test
    fun aClockFarAheadOfTheLastSuccessExpiresNoHoldAndANormalLaterCallDoes() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            repository.finishSync(ALICE)
            val until = clock.nowMs + 60_000
            apply(setOf(MOVIES), holds = mapOf(SHOWS to until))
            val before = generation()
            val guard = HistoryRetention().millis - HistoryRetention.SAFE_LOOKBACK_MS
            val lastSuccess = clock.nowMs

            clock.atMs(lastSuccess + guard + 1)
            apply(setOf(MOVIES))
            assertEquals("the hold is kept", until, row(SHOWS)?.heldUntilMs)
            assertEquals("and its rows", 1, storedIn(SHOWS).size)
            assertEquals(before, generation())

            clock.atMs(lastSuccess + guard)
            apply(setOf(MOVIES))
            assertNull("at the guard the clock is trusted, and the hold has ended", row(SHOWS))
            assertEquals(before + 1, generation())
        }

    // Fix round 2 (Vimes). A series listed in two libraries has one row, in the library that wrote it last; dropping
    // that library must not take the series from the other one's episodes.
    @Test
    fun aSharedSeriesOutlivesTheLibraryThatWroteItLast() =
        runBlocking {
            val show = 900
            apply(setOf(MOVIES, SHOWS))
            repository.put(ALICE, MOVIES, episodeOf(1, show, library = MOVIES), episodeOf(2, show, library = MOVIES))
            repository.put(ALICE, SHOWS, episodeOf(3, show, library = SHOWS))
            val shared = series(hexId(show), "TV-PG", listOf("Drama"), studios = listOf("Studio A"))
            repository.putSeries(ALICE, MOVIES, shared)
            repository.putSeries(ALICE, SHOWS, shared)
            assertEquals(SHOWS.value, repository.currentSeries(ALICE).single().libraryId)
            // A series only SHOWS names goes with it.
            repository.putSeries(ALICE, SHOWS, series(hexId(901)))
            val versions = repository.seriesVersions(ALICE).size

            apply(setOf(MOVIES))

            val kept = repository.currentSeries(ALICE).single()
            assertEquals(hexId(show), kept.seriesId)
            assertEquals(MOVIES.value, kept.libraryId)
            assertEquals("the move takes no version", versions, repository.seriesVersions(ALICE).size)
            val held = repository.ruleSnapshot(ALICE, setOf(MOVIES), at(clock.nowMs + 1)).series.getValue(canon(show))
            assertEquals("TV-PG", held.officialRating)
            assertEquals(listOf("Drama"), held.genres)
            assertEquals(listOf("Studio A"), held.studios)
        }

    @Test
    fun aSharedSeriesOfAnExpiredHoldGoesToTheSmallestLibraryThatStays() =
        runBlocking {
            val show = 900
            apply(setOf(MOVIES, SHOWS, extra))
            repository.put(ALICE, extra, episodeOf(1, show, library = extra))
            repository.put(ALICE, MOVIES, episodeOf(2, show, library = MOVIES))
            repository.putSeries(ALICE, SHOWS, series(hexId(show)))
            apply(setOf(MOVIES, extra), holds = mapOf(SHOWS to clock.nowMs + 1_000))

            clock.stepMs(1_000)
            apply(setOf(MOVIES, extra))

            assertNull(row(SHOWS))
            assertEquals(MOVIES.value, repository.currentSeries(ALICE).single().libraryId)
        }

    // Fix round 2 (Vimes). The backfill writes provider ids into every row: it must not rewrite the side tables too.
    @Test
    fun aProviderIdOnlyChangeLeavesTheSideRowsUntouched() =
        runBlocking {
            apply(setOf(MOVIES))
            val film =
                sitem(1).copy(genres = listOf("Drama", "Comedy"), tags = listOf("t"), studios = listOf("Studio A"))
            repository.put(ALICE, MOVIES, film)
            val other = sitem(2).copy(genres = listOf("Drama"), tags = listOf("t"), studios = listOf("S"))
            repository.put(ALICE, MOVIES, other)
            val before = repository.sideRowIds(ALICE, hexId(1))
            assertEquals(4, before.size)

            repository.put(ALICE, MOVIES, film.copy(providerIds = mapOf("imdb" to "tt0000001")))
            assertEquals("no side row rewritten", before, repository.sideRowIds(ALICE, hexId(1)))

            repository.put(ALICE, MOVIES, film.copy(providerIds = mapOf("imdb" to "tt0000001"), tags = listOf("u")))
            val after = repository.sideRowIds(ALICE, hexId(1))
            val keptBefore = before.take(2) + before.last()
            assertEquals("the genres and the studio are kept", keptBefore, after.take(2) + after.last())
            assertNotEquals("the changed tag list is rewritten", before[2], after[2])
            assertEquals(
                listOf("u"),
                repository
                    .stored(ALICE)
                    .single { it.item.id.value == hexId(1) }
                    .item.tags,
            )
        }

    // Fix round 2 (Vimes). A stamp starts an add; a chosen library that has landed is not one to add.
    @Test
    fun aNewStampForALandedChosenLibraryIsRefusedAndWritesNothing() =
        runBlocking {
            apply(setOf(MOVIES, SHOWS))
            fill(SHOWS, 2)
            val rowsBefore = rows()

            val refused = runCatching { apply(setOf(MOVIES, SHOWS), addStamps = mapOf(SHOWS to 1L)) }

            assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
            assertEquals(rowsBefore, rows())
            assertNotNull(repository.checkpoint(ALICE, SHOWS))
            // A pending chosen library with no stamp has landed in the set too: refused the same way.
            assertTrue(runCatching { apply(setOf(MOVIES, SHOWS), addStamps = mapOf(MOVIES to 1L)) }.isFailure)
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
            apply(setOf(MOVIES, extra, gone), addStamps = mapOf(extra to 1L, gone to 2L))

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
            apply(setOf(MOVIES, extra), addStamps = mapOf(extra to 4L), holds = mapOf(SHOWS to until))
            val before = generation()

            // The first sync knows only the chosen set: the held library and the add under way both stay.
            assertEquals(SyncOutcome.Completed, engine.run(session, ALICE, setOf(MOVIES)))

            assertEquals(until, row(SHOWS)?.heldUntilMs)
            assertEquals(2, storedIn(SHOWS).size)
            assertEquals(4L, row(extra)?.addStamp)
            assertEquals(before, generation())

            clock.stepMs(60_000)
            assertEquals(SyncOutcome.Completed, engine.run(session, ALICE, setOf(MOVIES)))
            assertNull("the hold ended", row(SHOWS))
            assertTrue(storedIn(SHOWS).isEmpty())
            assertEquals(4L, row(extra)?.addStamp)
            assertTrue(generation() > before)
        }
}
