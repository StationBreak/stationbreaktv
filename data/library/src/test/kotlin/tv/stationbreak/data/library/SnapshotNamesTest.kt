// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.model.ItemId

/**
 * Spec 23, D8, E4: the rule snapshot holds the name of an item, a series and a collection as this TV held it before the
 * cut-off, so a rename is a new version and moves the content generation. Invented names only.
 */
class SnapshotNamesTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val libraries = setOf(MOVIES)
    private val show = 900
    private val box = ItemId(hexId(1_000))

    @After
    fun close() = repository.close()

    private suspend fun snapshotAt(cutoffMs: Long) = repository.ruleSnapshot(ALICE, libraries, at(cutoffMs))

    private suspend fun putFilm(name: String) = repository.put(ALICE, MOVIES, sitem(1).copy(name = name))

    private suspend fun putEpisode() = repository.put(ALICE, MOVIES, episodeOf(2, show, library = MOVIES))

    private suspend fun putShow(name: String) = repository.putSeries(ALICE, MOVIES, series(hexId(show), name = name))

    private suspend fun putBox(name: String) = repository.writeCollection(ALICE, box, name, movies(hexId(1)))

    @Test
    fun theAsOfReadGivesTheNameHeldAtTheCutoff() =
        runBlocking {
            repository.applySelection(ALICE, libraries, nowMs = 0)
            putFilm("Film 1")
            putEpisode()
            putShow("Show A")
            putBox("Box Set 1 Collection")
            clock.stepMs(10_000)
            val cutoff = clock.nowMs
            clock.stepMs(10_000)
            putFilm("Film 1 Renamed")
            putShow("Show A Renamed")
            putBox("Box Set 1 Renamed Collection")

            val before = snapshotAt(cutoff)
            assertEquals("Film 1", before.items.single { it.item.id == canon(1) }.name)
            assertEquals("Show A", before.series.getValue(canon(show)).name)
            assertEquals("Box Set 1 Collection", before.collections.getValue(canon(1_000)).name)
            // The episode keeps the name the sync stored for it.
            assertEquals("Name of 2", before.items.single { it.item.id == canon(2) }.name)

            val after = snapshotAt(clock.nowMs + 1)
            assertEquals("Film 1 Renamed", after.items.single { it.item.id == canon(1) }.name)
            assertEquals("Show A Renamed", after.series.getValue(canon(show)).name)
            assertEquals("Box Set 1 Renamed Collection", after.collections.getValue(canon(1_000)).name)
        }

    @Test
    fun aRenameIsANewVersion() =
        runBlocking {
            repository.applySelection(ALICE, libraries, nowMs = 0)
            putFilm("Film 1")
            putShow("Show A")
            putBox("Box Set 1 Collection")
            clock.stepMs(10_000)

            putFilm("Film 1 Renamed")
            putShow("Show A Renamed")
            putBox("Box Set 1 Renamed Collection")

            val item = repository.versions(ALICE).single()
            assertEquals("Film 1", item.name)
            assertEquals("Film 1 Renamed", repository.currentRows(ALICE).single().name)
            val series = repository.seriesVersions(ALICE).single()
            assertEquals("Show A", series.name)
            assertEquals("Show A Renamed", repository.currentSeries(ALICE).single().name)
            val collection = repository.collectionVersions(ALICE).single()
            assertEquals("Box Set 1 Collection", collection.name)
            assertEquals("Box Set 1 Renamed Collection", repository.collections(ALICE).single().name)
            repository.assertIntervalsContiguous(ALICE)
        }

    @Test
    fun aRenameMovesTheContentGeneration() =
        runBlocking {
            repository.applySelection(ALICE, libraries, nowMs = 0)
            putFilm("Film 1")
            putShow("Show A")
            putBox("Box Set 1 Collection")
            clock.stepMs(10_000)

            var generation = repository.contentGeneration(ALICE)
            putFilm("Film 1 Renamed")
            assertNotEquals("an item rename", generation, repository.contentGeneration(ALICE))
            generation = repository.contentGeneration(ALICE)
            putShow("Show A Renamed")
            assertNotEquals("a series rename", generation, repository.contentGeneration(ALICE))
            generation = repository.contentGeneration(ALICE)
            putBox("Box Set 1 Renamed Collection")
            assertNotEquals("a collection rename", generation, repository.contentGeneration(ALICE))
            generation = repository.contentGeneration(ALICE)
            putBox("Box Set 1 Renamed Collection")
            assertEquals("the same name again", generation, repository.contentGeneration(ALICE))
        }

    @Test
    fun aCollectionFirstSeenAfterTheCutoffTakesItsOldestVersionsName() =
        runBlocking {
            repository.applySelection(ALICE, libraries, nowMs = 0)
            putFilm("Film 1")
            val cutoff = clock.nowMs - 60_000
            putBox("Box Set 1 Collection")
            clock.stepMs(10_000)
            putBox("Box Set 1 Second Collection")
            clock.stepMs(10_000)
            putBox("Box Set 1 Third Collection")

            // Case (c): nothing older than the cut-off, so the oldest version's name, not the current one.
            assertEquals("Box Set 1 Collection", snapshotAt(cutoff).collections.getValue(canon(1_000)).name)
            // A collection with one lifecycle and no version takes the current row's name.
            val lone = ItemId(hexId(1_001))
            repository.writeCollection(ALICE, lone, "Box Set 2 Collection", movies(hexId(1)))
            assertEquals("Box Set 2 Collection", snapshotAt(cutoff).collections.getValue(canon(1_001)).name)
        }

    @Test
    fun aRenameOnlyCollectionWritesAVersionWithTheOldName() =
        runBlocking {
            putBox("Box Set 1 Collection")
            clock.stepMs(10_000)
            val membersBefore = repository.collectionMembers(ALICE)

            val write = repository.writeCollection(ALICE, box, "Box Set 1 Renamed Collection", movies(hexId(1)))

            assertNotNull("a rename takes a stamp", write.stamp)
            assertEquals(0, write.added)
            assertEquals(0, write.removed)
            val version = repository.collectionVersions(ALICE).single()
            assertEquals("Box Set 1 Collection", version.name)
            assertEquals(write.stamp, version.supersededAtMs)
            val row = repository.collections(ALICE).single()
            assertEquals("Box Set 1 Renamed Collection", row.name)
            assertEquals(write.stamp, row.seenFromMs)
            assertNull(row.deletedAtMs)
            assertEquals("the members are not touched", membersBefore, repository.collectionMembers(ALICE))
        }
}
