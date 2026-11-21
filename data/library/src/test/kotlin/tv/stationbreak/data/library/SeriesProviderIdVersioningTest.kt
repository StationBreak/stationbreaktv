// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR-0029: a series' provider ids are versioned on the keys the rule engine reads, and no others. */
class SeriesProviderIdVersioningTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))

    @After
    fun close() = repository.close()

    private suspend fun putIds(ids: Map<String, String>) = repository.putSeries(ALICE, SHOWS, show(ids))

    private fun show(ids: Map<String, String>) = series(hexId(500), providerIds = ids)

    // ADR-0029: only the ids the engine reads are versioned.
    @Test
    fun aNonEngineIdChange_writesNoVersionAndMovesNoGeneration() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = 0)
            putIds(mapOf("tvdb" to "5001"))
            val before = repository.contentGeneration(ALICE)
            val versions = repository.seriesVersions(ALICE).size

            putIds(mapOf("tvdb" to "5001", "tvrage" to "77", "zap2it" to "EP1"))

            assertEquals(before, repository.contentGeneration(ALICE))
            assertEquals(versions, repository.seriesVersions(ALICE).size)
            assertEquals("tvdb=5001;tvrage=77;zap2it=EP1", repository.currentSeries(ALICE).single().providerIds)
        }

    @Test
    fun anEmptyAnswerAfterEngineIds_keepsTheIdsAndWritesNothing() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = 0)
            putIds(mapOf("tvdb" to "5001", "imdb" to "tt1"))
            val before = repository.contentGeneration(ALICE)
            val versions = repository.seriesVersions(ALICE).size

            putIds(emptyMap())
            putIds(mapOf("tvrage" to "77"))

            assertEquals(before, repository.contentGeneration(ALICE))
            assertEquals(versions, repository.seriesVersions(ALICE).size)
            assertEquals("imdb=tt1;tvdb=5001", repository.currentSeries(ALICE).single().providerIds)
        }

    @Test
    fun aRealEngineIdChange_writesExactlyOneVersion() =
        runBlocking {
            repository.applySelection(ALICE, setOf(MOVIES, SHOWS), nowMs = 0)
            putIds(mapOf("tvdb" to "5001"))
            val before = repository.contentGeneration(ALICE)

            putIds(mapOf("tvdb" to "5002"))
            putIds(mapOf("tvdb" to "5002", "tvrage" to "77"))

            assertEquals(before + 1, repository.contentGeneration(ALICE))
            assertEquals(1, repository.seriesVersions(ALICE).size)
            assertEquals("tvdb=5001", repository.seriesVersions(ALICE).single().providerIds)
        }
}
