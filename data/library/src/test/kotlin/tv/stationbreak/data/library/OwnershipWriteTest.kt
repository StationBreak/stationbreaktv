// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 26, schema 11: who owns an item that more than one library lists, on the write path. The first writer owns it;
 * a chosen writer takes it from an adding owner; any writer takes a tombstone; otherwise the owner stays and the writer
 * gets an `item_also_listed` row. A non-owner's write still updates the fields, versioned under the owner. A change to
 * the also-listed rows alone moves nothing. Invented libraries and names only.
 */
class OwnershipWriteTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))

    @After
    fun close() = repository.close()

    private suspend fun generation() = repository.contentGeneration(ALICE)

    private suspend fun stamped(
        library: tv.stationbreak.core.model.ItemId,
        stamp: Long,
        vararg items: tv.stationbreak.core.model.MediaItem,
    ) = repository.upsertItems(ALICE, library, stamp, rewalk = false, items.toList()).written()

    @Test
    fun firstWriterOwns_ownerRewrite_noRow() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)

            repository.put(ALICE, MOVIES, sitem(1))
            repository.put(ALICE, MOVIES, sitem(1, runtime = 60.minutes))

            assertEquals(MOVIES, repository.ownerOf(ALICE, 1))
            assertTrue(repository.alsoListed(ALICE).isEmpty())
        }

    @Test
    fun nonOwnerWrite_updatesFieldsVersionedUnderOwner() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1))
            clock.stepMs(1_000)
            val before = generation()

            repository.put(ALICE, SHOWS, sitem(1, runtime = 60.minutes, library = SHOWS))

            val row = repository.rowOf(ALICE, 1)!!
            assertEquals("the owner stays", MOVIES.value, row.libraryId)
            assertEquals("the fields are the writer's", 60.minutes.inWholeMilliseconds, row.runtimeMs)
            assertEquals(setOf(SHOWS), repository.alsoListersOf(ALICE, 1))
            val version = repository.versions(ALICE).single()
            assertEquals("versioned under the owner", MOVIES.value, version.libraryId)
            assertEquals(row.seenFromMs, version.supersededAtMs)
            assertTrue("a chosen item changed", generation() > before)
        }

    @Test
    fun alsoRowChangeAlone_noBump() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1))
            val before = generation()

            repository.put(ALICE, SHOWS, sitem(1))

            assertEquals(setOf(SHOWS), repository.alsoListersOf(ALICE, 1))
            assertEquals("only the also-listed row changed", before, generation())
            assertTrue(repository.versions(ALICE).isEmpty())
        }

    @Test
    fun chosenTakesFromAddingOwner_bumps() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, ADDS), mapOf(ADDS to STAMP), nowMs = clock.nowMs)
            stamped(ADDS, STAMP, sitem(1, library = ADDS))
            clock.stepMs(1_000)
            val before = generation()

            repository.put(ALICE, MOVIES, sitem(1))

            assertEquals(MOVIES, repository.ownerOf(ALICE, 1))
            assertEquals("the adding owner keeps a row", setOf(ADDS), repository.alsoListersOf(ALICE, 1))
            assertTrue("the move is versioned", repository.movedVersioned(ALICE, 1, ADDS))
            assertTrue("the item went on air", generation() > before)
        }

    @Test
    fun chosenWriterAgainstChosenOrHeldOwner_ownerStays() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS, MORE), nowMs = clock.nowMs)
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))
            repository.put(ALICE, MOVIES, sitem(1))
            repository.put(ALICE, MORE, sitem(2, library = MORE))
            repository.applySelection(
                ALICE,
                setOf(MOVIES, SHOWS),
                holds = mapOf(MORE to clock.nowMs + 60_000),
                nowMs = clock.nowMs,
            )

            repository.put(ALICE, MOVIES, sitem(2))

            assertEquals("a chosen owner stays", SHOWS, repository.ownerOf(ALICE, 1))
            assertEquals(setOf(MOVIES), repository.alsoListersOf(ALICE, 1))
            assertEquals("a held owner stays", MORE, repository.ownerOf(ALICE, 2))
            assertEquals(setOf(MOVIES), repository.alsoListersOf(ALICE, 2))
        }

    @Test
    fun addingVsAdding_ownerStays() =
        runBlocking {
            repository.applySelection(
                ALICE,
                setOf(ADDS, MORE),
                mapOf(ADDS to STAMP, MORE to STAMP + 1),
                nowMs = clock.nowMs,
            )
            stamped(ADDS, STAMP, sitem(1, library = ADDS))

            stamped(MORE, STAMP + 1, sitem(1, library = MORE))

            assertEquals(ADDS, repository.ownerOf(ALICE, 1))
            assertEquals(setOf(MORE), repository.alsoListersOf(ALICE, 1))
        }

    @Test
    fun tombstoneTakeover_deletesListerRow_neverInsertsOldOwner() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1))
            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))
            repository.tombstone(
                ALICE,
                listOf(
                    tv.stationbreak.core.model
                        .ItemId(hexId(1)),
                ),
            )
            clock.stepMs(1_000)
            val before = generation()

            repository.put(ALICE, SHOWS, sitem(1, library = SHOWS))

            val row = repository.rowOf(ALICE, 1)!!
            assertEquals("the lister takes the tombstone", SHOWS.value, row.libraryId)
            assertNull("revived", row.deletedAtMs)
            assertTrue("no row for the old owner, none for the new one", repository.alsoListed(ALICE).isEmpty())
            assertTrue(repository.movedVersioned(ALICE, 1, MOVIES))
            assertTrue(generation() > before)
        }

    @Test
    fun tombstoneTakeover_byAnAddingWriter() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, ADDS), mapOf(ADDS to STAMP), nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1))
            repository.tombstone(
                ALICE,
                listOf(
                    tv.stationbreak.core.model
                        .ItemId(hexId(1)),
                ),
            )

            stamped(ADDS, STAMP, sitem(1, library = ADDS))

            assertEquals(ADDS, repository.ownerOf(ALICE, 1))
            assertNull(repository.rowOf(ALICE, 1)!!.deletedAtMs)
            assertTrue(repository.alsoListed(ALICE).isEmpty())
        }

    // 4.4a-i LOW-4 under the new model: a library marked for a rewalk is chosen for every ownership rule.
    @Test
    fun markedWriter_isChosen_takesFromAddingOwner() =
        runBlocking {
            repository.applySelection(
                ALICE,
                setOf(MOVIES, ADDS, SHOWS),
                mapOf(ADDS to STAMP, SHOWS to STAMP + 1),
                nowMs = clock.nowMs,
            )
            stamped(ADDS, STAMP, sitem(1, library = ADDS))
            assertTrue(repository.revertUnproven(ALICE, SHOWS, STAMP + 1))

            val rewalkPage = listOf(sitem(1, library = SHOWS))
            repository.upsertItems(ALICE, SHOWS, stamp = null, rewalk = true, rewalkPage).written()

            assertEquals(SHOWS, repository.ownerOf(ALICE, 1))
            assertEquals(setOf(ADDS), repository.alsoListersOf(ALICE, 1))
        }

    @Test
    fun d8_addSeriesWrite_bumpsOnlyWhenOutsideEpisodeNames() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, ADDS), mapOf(ADDS to STAMP), nowMs = clock.nowMs)
            repository.put(ALICE, MOVIES, sitem(1))
            val show = hexId(0x5E01)

            var before = generation()
            repository.upsertSeries(ALICE, ADDS, STAMP, rewalk = false, listOf(series(show))).written()
            assertEquals("no episode names it", before, generation())

            stamped(ADDS, STAMP, sepisode(2, show))
            before = generation()
            repository.upsertSeries(ALICE, ADDS, STAMP, rewalk = false, listOf(series(show, rating = "TV-14")))
            assertEquals("only the add's own episodes name it", before, generation())

            repository.put(ALICE, MOVIES, sepisode(3, dashedUpper(show)))
            before = generation()
            repository.upsertSeries(ALICE, ADDS, STAMP, rewalk = false, listOf(series(show, rating = "TV-PG")))
            assertTrue("a chosen episode names it, in another spelling", generation() > before)
        }

    @Test
    fun d8_heldEpisodeCounts() =
        runBlocking {
            repository.applySelection(ALICE, setOf(SHOWS, ADDS), mapOf(ADDS to STAMP), nowMs = clock.nowMs)
            val show = hexId(0x5E02)
            repository.put(ALICE, SHOWS, sepisode(1, show))
            val hold = mapOf(SHOWS to clock.nowMs + 60_000)
            repository.applySelection(ALICE, setOf(ADDS), holds = hold, nowMs = clock.nowMs)
            val before = generation()

            repository.upsertSeries(ALICE, ADDS, STAMP, rewalk = false, listOf(series(show))).written()

            assertTrue("a held episode is outside the adds", generation() > before)
        }

    @Test
    fun unstampedSeriesWrite_alwaysBumps() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES), nowMs = clock.nowMs)
            val before = generation()

            repository.putSeries(ALICE, MOVIES, series(hexId(0x5E03)))

            assertTrue(generation() > before)
        }

    private companion object {
        const val STAMP = 7L
    }
}
