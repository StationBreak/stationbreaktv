// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ordering.splitmix64
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A generated pool, with the channel seed, the epoch seed and the run length it is ordered with. */
private class Case(
    val pool: List<ScheduleItem>,
    val seed: Seed,
    val seedN: ULong,
    val blockLength: Int,
)

private const val MAX_POOL = 12
private const val MAX_BLOCK = 4

/**
 * Items with few dates, series and seasons, so that ties on every part of the chronological key happen often. Movies
 * and episodes, dated by premiere or by production year or not at all, and an episode may have no series, season or
 * number.
 */
private fun SeededGen.pool(size: Int): List<ScheduleItem> {
    val series = List(SERIES) { CanonicalId.parse(hexId()) }
    val seen = HashSet<String>()
    val items = ArrayList<ScheduleItem>()
    while (items.size < size) {
        val id = CanonicalId.parse(hexId())
        if (!seen.add(id.value)) continue
        items += item(id, series)
    }
    return items
}

private fun SeededGen.item(
    id: CanonicalId,
    series: List<CanonicalId>,
): ScheduleItem {
    val movie = chance(MOVIE_PERCENT)
    val dateKind = nextInt(DATE_KINDS)
    return ScheduleItem(
        id = id,
        kind = if (movie) ScheduleItemKind.Movie else ScheduleItemKind.Episode,
        runtimeMs = MS_MINUTE * nextInt(MIN_RUNTIME..MAX_RUNTIME),
        createdAtMs = 0L,
        deletedAtMs = null,
        premiereDate = if (dateKind == 0) pick(PREMIERES) else null,
        productionYear = if (dateKind == 1) pick(YEARS) else null,
        seriesId = if (movie || chance(NO_SERIES_PERCENT)) null else pick(series),
        seasonNumber = if (movie || chance(NO_NUMBER_PERCENT)) null else nextInt(SEASONS),
        episodeNumber = if (movie || chance(NO_NUMBER_PERCENT)) null else nextInt(1..EPISODES),
    )
}

private fun SeededGen.case(): Case {
    val seed = Seed(nextULong())
    return Case(pool(nextInt(0..MAX_POOL)), seed, splitmix64(nextULong()), nextInt(1..MAX_BLOCK))
}

private const val SERIES = 3
private const val MOVIE_PERCENT = 40
private const val DATE_KINDS = 3
private const val NO_SERIES_PERCENT = 15
private const val NO_NUMBER_PERCENT = 10
private const val SEASONS = 3
private const val EPISODES = 4
private const val MIN_RUNTIME = 5
private const val MAX_RUNTIME = 120
private val PREMIERES = listOf("2019-12-25", "2020-01-01", "2020-01-02", "2020-01-08")
private val YEARS = listOf(987, 2019, 2020)

private fun List<ScheduleItem>.ids(): List<String> = map { it.id.value }

private fun <T> List<T>.without(element: T): List<T> = filter { it != element }

/**
 * Property tests (spec 08, Unit 2) on 1,000 generated cases each, from a deterministic generator on `splitmix64`. A
 * failure prints its seed.
 */
class OrderingPropertyTest {
    @Test
    fun theGeneratorIsDeterministicAndVaried() {
        val a = SeededGen(7uL)
        val b = SeededGen(7uL)
        assertEquals(List(5) { a.nextULong() }, List(5) { b.nextULong() })
        assertEquals(splitmix64(7uL), SeededGen(7uL).nextULong())
        assertEquals(32, SeededGen(1uL).hexId().length)
        val sizes = (0uL..50uL).map { SeededGen(it).case().pool.size }.toSet()
        assertTrue(sizes.size > 5, "pool sizes are varied: $sizes")
    }

    @Test
    fun removingAnItemLeavesTheOthersInTheSameShuffleOrder() {
        forAllCases("shuffle removal", baseSeed = 0x5348uL) { gen ->
            val case = gen.case()
            val full = Orderings.shuffle(case.pool, case.seedN)
            for (removed in case.pool) {
                val rest = Orderings.shuffle(case.pool.without(removed), case.seedN)
                assertEquals(full.without(removed).ids(), rest.ids(), "removed ${removed.id}")
            }
        }
    }

    @Test
    fun theOrderOfEveryModeDoesNotDependOnTheOrderThePoolIsGivenIn() {
        forAllCases("input order", baseSeed = 0x494EuL) { gen ->
            val case = gen.case()
            val reversed = case.pool.reversed()
            assertEquals(
                Orderings.shuffle(case.pool, case.seedN).ids(),
                Orderings.shuffle(reversed, case.seedN).ids(),
            )
            assertEquals(Orderings.chronological(case.pool).ids(), Orderings.chronological(reversed).ids())
            assertEquals(
                Orderings.randomInOrderTurns(case.pool, case.seed, case.blockLength).map { it.ids() },
                Orderings.randomInOrderTurns(reversed, case.seed, case.blockLength).map { it.ids() },
            )
        }
    }

    @Test
    fun anInsertionIntoAChronologicalPoolIsAStableSort() {
        forAllCases("chronological insertion", baseSeed = 0x4348uL) { gen ->
            val case = gen.case()
            if (case.pool.isEmpty()) return@forAllCases
            val inserted = case.pool.last()
            val before = case.pool.without(inserted)
            val orderWith = Orderings.chronological(case.pool).ids()
            // The others keep their relative order, so the new item lands exactly where the key puts it ...
            assertEquals(Orderings.chronological(before).ids(), orderWith.filter { it != inserted.id.value })
            // ... and it sits after every item whose key is smaller and before every item whose key is larger.
            val comparator = Orderings.chronologicalKey
            val position = orderWith.indexOf(inserted.id.value)
            for ((index, id) in orderWith.withIndex()) {
                val other = case.pool.first { it.id.value == id }
                if (other === inserted) continue
                val expected = comparator.compare(other, inserted) < 0
                assertEquals(expected, index < position, "${other.id} against ${inserted.id}")
            }
        }
    }

    @Test
    fun chronologicalOrderIsSortedByTheKey() {
        forAllCases("chronological sorted", baseSeed = 0x4353uL) { gen ->
            val case = gen.case()
            val order = Orderings.chronological(case.pool)
            val comparator = Orderings.chronologicalKey
            for (pair in order.zipWithNext()) assertTrue(comparator.compare(pair.first, pair.second) < 0)
        }
    }

    @Test
    fun removingTheLastItemOfAGroupLeavesTheOthersInTheSameRandomInOrderOrder() {
        forAllCases("randomInOrder removal", baseSeed = 0x5249uL) { gen ->
            val case = gen.case()
            val full = Orderings.randomInOrder(case.pool, case.seed, case.blockLength)
            val groups = Orderings.groups(case.pool, case.seed)
            for (removed in groups.map { it.items.last() }) {
                val rest = Orderings.randomInOrder(case.pool.without(removed), case.seed, case.blockLength)
                assertEquals(full.without(removed).ids(), rest.ids(), "removed ${removed.id}")
            }
        }
    }

    @Test
    fun removingAnyItemKeepsTheGroupsAndEachGroupsItemsInOrder() {
        forAllCases("randomInOrder groups", baseSeed = 0x4752uL) { gen ->
            val case = gen.case()
            val groups = Orderings.groups(case.pool, case.seed)
            for (removed in case.pool) {
                val rest = Orderings.groups(case.pool.without(removed), case.seed)
                val expected =
                    groups
                        .map { it.groupId.value to it.items.without(removed).ids() }
                        .filter { it.second.isNotEmpty() }
                assertEquals(expected, rest.map { it.groupId.value to it.items.ids() }, "removed ${removed.id}")
            }
        }
    }
}
