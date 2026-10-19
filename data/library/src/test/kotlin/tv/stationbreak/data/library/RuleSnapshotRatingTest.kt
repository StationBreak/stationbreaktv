// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 10 task 3.3 (rules-format rule 4 treats them differently): a null, an empty and a blank rating stay three
 * distinct values through the snapshot, on an item and on a series. The snapshot normalises nothing.
 */
class RuleSnapshotRatingTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val start = clock.nowMs
    private val ratings = listOf<String?>(null, "", " ", "  ", "R", "r")

    @After
    fun close() = repository.close()

    @Test
    fun itemRatingsStayAsStored() =
        runBlocking {
            repository.select(ALICE, setOf(RULE_LIBRARY))
            ratings.forEachIndexed { n, rating ->
                repository.put(ALICE, RULE_LIBRARY, episodeOf(n + 1, null, rating = rating))
            }

            val snapshot = repository.ruleSnapshot(ALICE, setOf(RULE_LIBRARY), at(start + 1))

            assertEquals(ratings, snapshot.items.map { it.officialRating })
        }

    @Test
    fun seriesRatingsStayAsStored() =
        runBlocking {
            repository.select(ALICE, setOf(RULE_LIBRARY))
            ratings.forEachIndexed { n, rating ->
                repository.put(ALICE, RULE_LIBRARY, episodeOf(n + 1, 900 + n))
                repository.putSeries(ALICE, RULE_LIBRARY, series(hexId(900 + n), rating))
            }

            val snapshot = repository.ruleSnapshot(ALICE, setOf(RULE_LIBRARY), at(start + 1))

            assertEquals(ratings, snapshot.series.values.map { it.officialRating })
        }

    @Test
    fun aRatingEditedFromNullToBlankToEmptyIsReadAsItWasHeld() =
        runBlocking {
            repository.select(ALICE, setOf(RULE_LIBRARY))
            val cutoffs = ArrayList<Long>()
            for (rating in listOf<String?>(null, " ", "")) {
                repository.put(ALICE, RULE_LIBRARY, episodeOf(1, null, rating = rating))
                clock.stepMs(10_000)
                cutoffs += clock.nowMs - 1
            }

            val seen =
                cutoffs.map {
                    repository
                        .ruleSnapshot(ALICE, setOf(RULE_LIBRARY), at(it))
                        .items
                        .single()
                        .officialRating
                }

            assertEquals(listOf<String?>(null, " ", ""), seen)
        }
}
