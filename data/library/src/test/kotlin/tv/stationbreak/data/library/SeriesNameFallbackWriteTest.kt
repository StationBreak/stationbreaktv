// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #607: the server's own series name is display only. An existing row gets it on its next write, and that write is no
 * new version and does not move the content generation: nothing a schedule or a rule reads has changed.
 */
class SeriesNameFallbackWriteTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)

    @After
    fun close() = repository.close()

    @Test
    fun aLaterWriteFillsTheSeriesNameWithoutMakingAVersionOrMovingTheGeneration() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES), nowMs = 0)
            repository.put(ALICE, MOVIES, sitem(1))
            val before = repository.currentRows(ALICE).single()
            assertEquals(null, before.seriesName)
            val generation = repository.contentGeneration(ALICE)
            clock.stepMs(10_000)

            repository.put(ALICE, MOVIES, sitem(1).copy(seriesName = "Invented Show"))

            val after = repository.currentRows(ALICE).single()
            assertEquals("Invented Show", after.seriesName)
            assertTrue("no version is made", repository.versions(ALICE).isEmpty())
            assertEquals("the schedule stamp stays", before.seenFromMs, after.seenFromMs)
            assertEquals("the generation stays", generation, repository.contentGeneration(ALICE))
        }

    @Test
    fun theSeriesNameIsNotAScheduleField() {
        val held = sitem(1).toTestEntity()

        assertTrue(held.sameScheduleFields(held.copy(seriesName = "Invented Show")))
    }

    private fun tv.stationbreak.core.model.MediaItem.toTestEntity() =
        ItemEntity(
            serverId = "server-1",
            userId = "alice",
            itemId = id.value,
            libraryId = MOVIES.value,
            kind = kind.name,
            name = name,
            parentId = null,
            seriesId = null,
            seasonNumber = null,
            episodeNumber = null,
            runtimeMs = null,
            createdAtMs = null,
            premiereDate = null,
            productionYear = null,
            officialRating = null,
            deletedAtMs = null,
            seenFromMs = 0,
            firstSeenMs = 0,
        )
}
