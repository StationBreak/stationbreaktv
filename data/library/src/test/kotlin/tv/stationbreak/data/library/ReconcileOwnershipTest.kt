// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ListLibrarySource
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import tv.stationbreak.data.library.SyncWorld.shows

/**
 * Spec 26, schema 11: the ID reconcile of a library L keeps `item_also_listed` and the owners in step with L's
 * listing. The add half gives L a row for each listed item another library owns (owner re-checked in the
 * transaction); the removal half takes L's row away, only when both reads lack the item; an item L owns and drops moves
 * to a library that lists it (keeping its collection rows) or, with none, is tombstoned; a tombstone another library
 * owns that L lists is revived into L. The write is guarded by L's row like every other write. Invented names only.
 */
class ReconcileOwnershipTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val moviesSource = ListLibrarySource()
    private val showsSource = ListLibrarySource()
    private val server = PagedMediaServer(listOf(movies, shows), mapOf(MOVIES to moviesSource, SHOWS to showsSource))
    private val chosen = setOf(MOVIES, SHOWS)

    @After
    fun close() = repository.close()

    private suspend fun generation() = repository.contentGeneration(ALICE)

    private fun id(n: Int) = ItemId(hexId(n))

    // What each library lists on the server.
    private fun lists(
        source: ListLibrarySource,
        library: ItemId,
        vararg ns: Int,
    ) {
        source.items.clear()
        ns.forEach { source.items += sitem(it, library = library) }
    }

    private suspend fun reconcile(): SyncOutcome =
        StayCurrentWorld.engine(repository, server, clock).reconcile(session, ALICE, chosen).outcome

    @Test
    fun reconcileAddHalf_insertsOnlyForForeignOwner() =
        runBlocking {
            repository.applySelection(ALICE, chosen, nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1), sitem(2))
            repository.put(ALICE, SHOWS, sitem(3, library = SHOWS))
            lists(moviesSource, MOVIES, 1, 2)
            lists(showsSource, SHOWS, 1, 3)
            val before = generation()

            assertEquals(SyncOutcome.Completed, reconcile())

            assertEquals(listOf(id(1) to SHOWS), repository.alsoListed(ALICE))
            assertEquals("a row alone moves nothing", before, generation())
        }

    @Test
    fun reconcileAddHalf_rechecksTheOwnerInTheTransaction() =
        runBlocking {
            repository.applySelection(ALICE, chosen, nowMs = clock.nowMs)
            repository.put(ALICE, SHOWS, sitem(3, library = SHOWS))
            repository.put(ALICE, MOVIES, sitem(4))
            repository.tombstone(ALICE, listOf(id(4)))

            // The pass read 3 and 4 as owned elsewhere; by its write, SHOWS owns 3, and it takes 4 as a tombstone.
            val write = repository.applyReconcile(ALICE, SHOWS, alsoAdd = listOf(id(3), id(4)))

            assertTrue(write is ReconcileApplied.Written)
            assertTrue("never a row for the owner", repository.alsoListed(ALICE).isEmpty())
            assertEquals(SHOWS, repository.ownerOf(ALICE, 4))
        }

    @Test
    fun reconcileRemovalHalf_needsBothReads() =
        runBlocking {
            repository.applySelection(ALICE, chosen, nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1))
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS), sitem(3, library = SHOWS))
            lists(moviesSource, MOVIES, 1)
            lists(showsSource, SHOWS, 3)
            var showsReads = 0
            server.respond = { call ->
                val showsItemIds = call.idsOnly && !call.series && call.library.id == SHOWS
                if (showsItemIds && call.startIndex == 0) {
                    showsReads++
                    // The second read lists the item again: the first read alone is not enough.
                    if (showsReads == 2) showsSource.items += sitem(1, library = SHOWS)
                }
                null
            }

            assertEquals(SyncOutcome.Completed, reconcile())
            assertEquals("one read lacked it: kept", setOf(SHOWS), repository.alsoListersOf(ALICE, 1))

            server.respond = { null }
            lists(showsSource, SHOWS, 3)
            assertEquals(SyncOutcome.Completed, reconcile())
            assertTrue("both reads lacked it: gone", repository.alsoListed(ALICE).isEmpty())
            assertEquals(MOVIES, repository.ownerOf(ALICE, 1))
        }

    @Test
    fun reconcileOwnerDrop_movesToAlsoLister_membersKept_bumps() =
        runBlocking {
            repository.applySelection(ALICE, chosen, nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1), sitem(2))
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))
            repository.writeCollection(ALICE, ItemId(COLLECTION), "Invented Set", movies(hexId(1)))
            lists(moviesSource, MOVIES, 2)
            lists(showsSource, SHOWS, 1)
            clock.stepMs(1_000)
            val before = generation()

            assertEquals(SyncOutcome.Completed, reconcile())

            val row = repository.rowOf(ALICE, 1)!!
            assertEquals(SHOWS.value, row.libraryId)
            assertNull("moved, not tombstoned", row.deletedAtMs)
            assertTrue(repository.alsoListed(ALICE).isEmpty())
            assertTrue(repository.movedVersioned(ALICE, 1, MOVIES))
            assertEquals(
                "its collection rows stay",
                listOf(hexId(1)),
                repository.currentMembers(ALICE, COLLECTION).map { it.substringBefore(":") },
            )
            assertTrue(generation() > before)
        }

    @Test
    fun reconcileOwnerDrop_intoAnAdd_bumps() =
        runBlocking {
            repository.applySelection(ALICE, chosen + ADDS, mapOf(ADDS to STAMP), nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1), sitem(2))
            repository.upsertItems(ALICE, ADDS, STAMP, rewalk = false, listOf(sitem(1))).written()
            lists(moviesSource, MOVIES, 2)
            lists(showsSource, SHOWS)
            clock.stepMs(1_000)
            val before = generation()

            assertEquals(SyncOutcome.Completed, reconcile())

            assertEquals(ADDS, repository.ownerOf(ALICE, 1))
            assertTrue("the item left the air", generation() > before)
        }

    @Test
    fun reconcileOwnerDrop_noLister_tombstones() =
        runBlocking {
            repository.applySelection(ALICE, chosen, nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1), sitem(2))
            repository.writeCollection(ALICE, ItemId(COLLECTION), "Invented Set", movies(hexId(1)))
            lists(moviesSource, MOVIES, 2)
            lists(showsSource, SHOWS)

            assertEquals(SyncOutcome.Completed, reconcile())

            val row = repository.rowOf(ALICE, 1)!!
            assertEquals(MOVIES.value, row.libraryId)
            assertNotNull(row.deletedAtMs)
            assertTrue("its member row ended", repository.currentMembers(ALICE, COLLECTION).isEmpty())
        }

    @Test
    fun reconcile_listedForeignTombstone_revivesIntoLister() =
        runBlocking {
            repository.applySelection(ALICE, chosen, nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1), sitem(2))
            repository.put(ALICE, SHOWS, sitem(3, library = SHOWS))
            repository.tombstone(ALICE, listOf(id(1)))
            lists(moviesSource, MOVIES, 2)
            lists(showsSource, SHOWS, 1, 3)
            clock.stepMs(1_000)
            val before = generation()

            assertEquals(SyncOutcome.Completed, reconcile())

            val row = repository.rowOf(ALICE, 1)!!
            assertEquals(SHOWS.value, row.libraryId)
            assertNull("revived", row.deletedAtMs)
            assertTrue(repository.alsoListed(ALICE).isEmpty())
            assertTrue(repository.movedVersioned(ALICE, 1, MOVIES))
            assertTrue(generation() > before)
        }

    @Test
    fun reconcileWrite_heldMarkedOrMissingLibrary_refused() =
        runBlocking {
            repository.applySelection(ALICE, chosen + ADDS, mapOf(ADDS to STAMP), nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1))
            repository.put(ALICE, SHOWS, sitem(2, library = SHOWS))
            assertTrue(repository.revertUnproven(ALICE, ADDS, STAMP))
            val hold = mapOf(SHOWS to clock.nowMs + 60_000)
            repository.applySelection(ALICE, setOf(MOVIES, ADDS), holds = hold, nowMs = clock.nowMs)
            val before = generation()

            val held = repository.applyReconcile(ALICE, SHOWS, itemsGone = listOf(id(2)))
            val marked = repository.applyReconcile(ALICE, ADDS, alsoAdd = listOf(id(1)))
            val missing = repository.applyReconcile(ALICE, MORE, itemsGone = listOf(id(1)))

            assertEquals(ReconcileApplied.Refused, held)
            assertEquals(ReconcileApplied.Refused, marked)
            assertEquals(ReconcileApplied.Refused, missing)
            assertNull(repository.rowOf(ALICE, 2)!!.deletedAtMs)
            assertNull(repository.rowOf(ALICE, 1)!!.deletedAtMs)
            assertTrue(repository.alsoListed(ALICE).isEmpty())
            assertEquals(before, generation())
        }

    @Test
    fun reconcile_libraryHeldDuringThePass_cancelledNothingWritten() =
        runBlocking {
            repository.applySelection(ALICE, chosen, nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1), sitem(2))
            lists(moviesSource, MOVIES, 2)
            lists(showsSource, SHOWS)
            server.respond = { call ->
                if (call.idsOnly && call.series && call.library.id == MOVIES) {
                    runBlocking {
                        val hold = mapOf(MOVIES to clock.nowMs + 60_000)
                        repository.applySelection(ALICE, setOf(SHOWS), holds = hold, nowMs = clock.nowMs)
                    }
                }
                null
            }

            assertEquals(SyncOutcome.Cancelled, reconcile())

            assertNull("not tombstoned into a held library", repository.rowOf(ALICE, 1)!!.deletedAtMs)
        }

    // Review LOW-3: between L's read and L's write, M takes L's tombstone and tombstones it again. L's revive must not
    // bring it back under M (M's listing dropped it): L takes it over, as a listed tombstone another library owns.
    @Test
    fun reconcile_revive_onlyOfTheListersOwnTombstone() =
        runBlocking {
            repository.applySelection(ALICE, chosen, nowMs = clock.nowMs)
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS), sitem(3, library = SHOWS))
            repository.tombstone(ALICE, listOf(id(1)))
            lists(showsSource, SHOWS, 1, 3)
            var raced = false
            server.respond = { call ->
                val itemIds = call.idsOnly && !call.series && call.library.id == SHOWS
                if (!raced && itemIds) {
                    raced = true
                    runBlocking {
                        repository.put(ALICE, MOVIES, sitem(1))
                        repository.tombstone(ALICE, listOf(id(1)))
                    }
                }
                null
            }

            val outcome = StayCurrentWorld.engine(repository, server, clock).reconcile(session, ALICE, setOf(SHOWS))

            assertEquals(SyncOutcome.Completed, outcome.outcome)
            val row = repository.rowOf(ALICE, 1)!!
            assertEquals("never revived under the other owner", SHOWS.value, row.libraryId)
            assertNull(row.deletedAtMs)
            assertTrue(repository.movedVersioned(ALICE, 1, MOVIES))
        }

    // Review LOW-4: the summary counts what the write did, not what the pass read.
    @Test
    fun reconcileSummary_countsWhatWasWritten() =
        runBlocking {
            repository.applySelection(ALICE, chosen, nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1), sitem(2), sitem(4))
            repository.put(ALICE, SHOWS, sitem(4, library = SHOWS))
            lists(moviesSource, MOVIES, 2)
            lists(showsSource, SHOWS, 4)
            var raced = false
            server.respond = { call ->
                val itemIds = call.idsOnly && !call.series && call.library.id == MOVIES
                if (!raced && itemIds) {
                    raced = true
                    // Another pass tombstones item 1 first: this write finds it already gone.
                    runBlocking { repository.tombstone(ALICE, listOf(id(1))) }
                }
                null
            }

            val result = StayCurrentWorld.engine(repository, server, clock).reconcile(session, ALICE, setOf(MOVIES))

            assertEquals(SyncOutcome.Completed, result.outcome)
            assertEquals(ReconcileSummary(tombstoned = 0, moved = 1), result.summary)
        }

    // #495's write is guarded the same way, and tombstones only what each removed library still owns.
    @Test
    fun removedWrite_heldLibraryRefused_andOnlyOwnedRowsTombstoned() =
        runBlocking {
            repository.applySelection(ALICE, chosen + MORE, nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1))
            repository.put(ALICE, SHOWS, sitem(2, library = SHOWS))
            repository.put(ALICE, MORE, sitem(3, library = MORE))
            repository.applySelection(ALICE, chosen, holds = mapOf(MORE to clock.nowMs + 60_000), nowMs = clock.nowMs)

            val both = mapOf(MORE to listOf(id(3)), SHOWS to listOf(id(2)))
            val refused = repository.applyRemoved(ALICE, both, emptyList())
            assertEquals(ReconcileApplied.Refused, refused)
            assertNull(repository.rowOf(ALICE, 2)!!.deletedAtMs)
            assertNull(repository.rowOf(ALICE, 3)!!.deletedAtMs)

            // Item 1 is MOVIES's, so SHOWS's removal leaves it live.
            val write = repository.applyRemoved(ALICE, mapOf(SHOWS to listOf(id(1), id(2))), emptyList())
            assertEquals(ReconcileApplied.Written(1, 0, 0, 0), write)
            assertNull(repository.rowOf(ALICE, 1)!!.deletedAtMs)
            assertNotNull(repository.rowOf(ALICE, 2)!!.deletedAtMs)
        }

    private companion object {
        const val STAMP = 7L
        val COLLECTION = hexId(0xC001)
    }
}
