// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSeries

/**
 * Spec 10 task 3.3: the series of a `RuleSnapshot` are those a returned episode points to, as this TV held them before
 * the cut-off, with the as-of cases of spec 09: (a) the current row if it was first seen before the cut-off, (b) the
 * version current just before it, (c) the oldest version. A tombstoned series is kept until `deletedAt < cutoff`.
 */
class RuleSnapshotSeriesTest {
    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    private val start = clock.nowMs
    private val show = 900

    @After
    fun close() = repository.close()

    private suspend fun open() = repository.select(ALICE, setOf(RULE_LIBRARY))

    private suspend fun episodes(vararg numbers: Pair<Int, Int?>) {
        numbers.forEach { (n, series) -> repository.put(ALICE, RULE_LIBRARY, episodeOf(n, series)) }
    }

    private suspend fun putShow(
        n: Int = show,
        rating: String? = null,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) = repository.putSeries(ALICE, RULE_LIBRARY, series(hexId(n), rating, genres, tags))

    private suspend fun seriesAt(cutoffMs: Long): Map<CanonicalId, RuleSeries> {
        val libraries = setOf(RULE_LIBRARY)
        return repository.ruleSnapshot(ALICE, libraries, at(cutoffMs)).series
    }

    private fun held(
        n: Int,
        rating: String? = null,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) = RuleSeries(canon(n), rating, genres, tags)

    @Test
    fun aSeriesEditedAfterTheCutoffIsReturnedAsItWasAndThenAsItIs() =
        runBlocking {
            open()
            episodes(1 to show)
            putShow(rating = "PG", genres = listOf("Drama"), tags = listOf("slow"))
            clock.stepMs(10_000)
            val cutoff = clock.nowMs
            clock.stepMs(10_000)
            putShow(rating = "R", genres = listOf("Drama", "Crime"), tags = listOf("fast"))

            // Case (b): the version current just before the cut-off.
            assertEquals(mapOf(canon(show) to held(show, "PG", listOf("Drama"), listOf("slow"))), seriesAt(cutoff))
            // Case (a): the current row, first seen before this cut-off.
            val later = clock.nowMs + 1
            val edited = held(show, "R", listOf("Drama", "Crime"), listOf("fast"))
            assertEquals(mapOf(canon(show) to edited), seriesAt(later))
        }

    @Test
    fun aSeriesEditSeenExactlyAtTheCutoffIsNotInThatDaysSnapshot() =
        runBlocking {
            open()
            episodes(1 to show)
            putShow(rating = "PG")
            clock.stepMs(10_000)
            val cutoff = clock.nowMs
            putShow(rating = "R")

            assertEquals(mapOf(canon(show) to held(show, "PG")), seriesAt(cutoff))
            assertEquals(mapOf(canon(show) to held(show, "R")), seriesAt(cutoff + 1))
        }

    @Test
    fun aSeriesFirstSeenAfterTheCutoffIsReturnedAsItFirstWasNotAsItIsNow() =
        runBlocking {
            open()
            episodes(1 to show)
            val cutoff = clock.nowMs
            clock.stepMs(10_000)
            putShow(rating = "PG", genres = listOf("Drama"))
            clock.stepMs(10_000)
            putShow(rating = "R", genres = listOf("Crime"))

            // Case (c): the oldest version this TV holds, because nothing older exists.
            assertEquals(mapOf(canon(show) to held(show, "PG", listOf("Drama"))), seriesAt(cutoff))
        }

    @Test
    fun aTombstonedSeriesIsKeptUntilItsDeletionIsBeforeTheCutoff() =
        runBlocking {
            open()
            episodes(1 to show)
            putShow(rating = "PG")
            clock.stepMs(10_000)
            val deletedAt = clock.nowMs
            repository.tombstoneSeries(ALICE, listOf(ItemId(hexId(show))))

            assertEquals(setOf(canon(show)), seriesAt(deletedAt - 1).keys)
            assertEquals(setOf(canon(show)), seriesAt(deletedAt).keys)
            assertEquals(emptySet<Any>(), seriesAt(deletedAt + 1).keys)
        }

    @Test
    fun aSeriesDeletedAfterTheCutoffAndRevivedIsStillThereThatDay() =
        runBlocking {
            open()
            episodes(1 to show)
            putShow(rating = "PG")
            clock.stepMs(10_000)
            val cutoff = clock.nowMs
            clock.stepMs(10_000)
            repository.tombstoneSeries(ALICE, listOf(ItemId(hexId(show))))

            assertEquals(mapOf(canon(show) to held(show, "PG")), seriesAt(cutoff))
        }

    @Test
    fun aSeriesWithNoReturnedEpisodeIsAbsent() =
        runBlocking {
            open()
            putShow(rating = "PG")
            putShow(n = 901, rating = "R")
            episodes(1 to 901)

            assertEquals(setOf(canon(901)), seriesAt(start + 1).keys)
        }

    @Test
    fun aSeriesWhoseOnlyEpisodesAreOutsideTheSelectedLibrariesIsAbsent() =
        runBlocking {
            val other = ItemId(hexId(0xA002))
            repository.select(ALICE, setOf(RULE_LIBRARY, other))
            putShow()
            repository.put(ALICE, other, episodeOf(2, show, library = other))

            val snapshot = repository.ruleSnapshot(ALICE, setOf(RULE_LIBRARY), at(start + 1))

            assertEquals(emptyList<Any>(), snapshot.items)
            assertEquals(emptyMap<Any, Any>(), snapshot.series)
        }

    @Test
    fun anEpisodeWhoseSeriesIsUnknownIsKeptAndHasNoSeriesEntry() =
        runBlocking {
            open()
            episodes(1 to show, 2 to null)

            val snapshot = repository.ruleSnapshot(ALICE, setOf(RULE_LIBRARY), at(start + 1))

            assertEquals(listOf(canon(1), canon(2)), snapshot.items.map { it.item.id })
            assertEquals(emptyMap<Any, Any>(), snapshot.series)
        }

    // The 8-4-4-4-12 form of an ID, as the server may spell it.
    private fun dashed(hex: String) =
        listOf(hex.substring(0, 8), hex.substring(8, 12), hex.substring(12, 16), hex.substring(16, 20), hex.substring(20))
            .joinToString("-")

    @Test
    fun anEpisodeWhoseSeriesIsSpelledDifferentlyFromTheStoredSeriesStillGetsIt() =
        runBlocking {
            open()
            val spellings = listOf(dashed(hexId(show)).uppercase(), dashed(hexId(show)), hexId(show).uppercase())
            spellings.forEachIndexed { n, spelling ->
                repository.put(ALICE, RULE_LIBRARY, episodeOf(n + 1, show).copy(seriesId = ItemId(spelling)))
            }
            putShow(rating = "PG")

            assertEquals(mapOf(canon(show) to held(show, "PG")), seriesAt(start + 1))
        }

    @Test
    fun aSeriesStoredUnderADashedSpellingIsFoundByACanonicalEpisode() =
        runBlocking {
            open()
            episodes(1 to show)
            repository.putSeries(ALICE, RULE_LIBRARY, series(dashed(hexId(show)), "PG"))

            assertEquals(mapOf(canon(show) to held(show, "PG")), seriesAt(start + 1))
        }

    @Test
    fun theSeriesAreInCanonicalIdOrderWithOneEntryEach() =
        runBlocking {
            open()
            episodes(1 to 903, 2 to 901, 3 to 903, 4 to 902)
            listOf(903, 901, 902).forEach { putShow(n = it) }

            assertEquals(listOf(canon(901), canon(902), canon(903)), seriesAt(start + 1).keys.toList())
        }
}
