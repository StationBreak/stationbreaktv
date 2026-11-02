// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.Caps
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CappedShuffleTest {
    /** A seeded pool: some series, some single films, and some films in collections. */
    private class Pool(
        val items: List<ScheduleItem>,
        collections: Map<CanonicalId, CanonicalId>,
    ) {
        val rules = GroupRules(FilmCollections.of(collections))
    }

    private fun film(id: CanonicalId) =
        ScheduleItem(
            id = id,
            kind = ScheduleItemKind.Movie,
            runtimeMs = 30 * MS_MINUTE,
            createdAtMs = 0L,
            deletedAtMs = null,
            premiereDate = null,
            productionYear = null,
            seriesId = null,
            seasonNumber = null,
            episodeNumber = null,
        )

    private fun episode(
        id: CanonicalId,
        series: CanonicalId,
        number: Int,
    ) = film(id).copy(kind = ScheduleItemKind.Episode, seriesId = series, seasonNumber = 1, episodeNumber = number)

    private fun SeededGen.id() = CanonicalId.parse(hexId())

    private fun SeededGen.series(episodes: Int): List<ScheduleItem> {
        val series = id()
        return (1..episodes).map { episode(id(), series, it) }
    }

    private fun SeededGen.films(count: Int): List<ScheduleItem> = (1..count).map { film(id()) }

    private fun SeededGen.pool(): Pool {
        val items = ArrayList<ScheduleItem>()
        repeat(nextInt(0..6)) { items += series(nextInt(1..12)) }
        items += films(nextInt(0..6))
        val map = HashMap<CanonicalId, CanonicalId>()
        repeat(nextInt(0..3)) {
            val collection = id()
            for (member in films(nextInt(1..5))) {
                items += member
                map[member.id] = collection
            }
        }
        return Pool(items, map)
    }

    private fun SeededGen.caps() = Caps(nextInt(1..5), nextInt(1..3))

    /** The plain walk, the oracle: the first item in shuffle order whose group may go on, else the first item. */
    private fun plainWalk(
        pool: List<ScheduleItem>,
        seedN: ULong,
        caps: Caps,
        rules: GroupRules,
    ): List<ScheduleItem> {
        val left = Orderings.shuffle(pool, seedN).toMutableList()
        val out = ArrayList<ScheduleItem>()
        var last: CanonicalId? = null
        var run = 0
        while (left.isNotEmpty()) {
            val legal = left.indexOfFirst { rules.groupId(it) != last || run < rules.cap(it, caps) }
            val pick = left.removeAt(legal.coerceAtLeast(0))
            run = if (rules.groupId(pick) == last) run + 1 else 1
            last = rules.groupId(pick)
            out += pick
        }
        return out
    }

    /** The lengths of the runs of one group in [order]. */
    private fun runLengths(
        order: List<ScheduleItem>,
        rules: GroupRules,
    ): List<Int> {
        val lengths = ArrayList<Int>()
        for ((index, item) in order.withIndex()) {
            if (index > 0 && rules.groupId(order[index - 1]) == rules.groupId(item)) {
                lengths[lengths.size - 1]++
            } else {
                lengths += 1
            }
        }
        return lengths
    }

    private fun longestRun(
        order: List<ScheduleItem>,
        rules: GroupRules,
    ) = runLengths(order, rules).maxOrNull() ?: 0

    /** The items of [after] (the order without [removed]) outside a longest common subsequence with [before]. */
    private fun displaced(
        before: List<ScheduleItem>,
        after: List<ScheduleItem>,
        removed: ScheduleItem,
    ): Int {
        // Both are permutations of the same items, so the longest common subsequence is the longest increasing run of
        // positions in [before], read in the order of [after].
        val position = before.filter { it != removed }.withIndex().associate { (index, item) -> item.id to index }
        val tails = ArrayList<Int>()
        for (item in after) {
            val at = position.getValue(item.id)
            val slot = tails.binarySearch(at).let { if (it < 0) -(it + 1) else it }
            if (slot == tails.size) tails += at else tails[slot] = at
        }
        return after.size - tails.size
    }

    /** The most items [walk] displaces over [REMOVALS] removals of one item, with caps 3 and 1 and no collections. */
    private fun worstDisplacement(
        items: List<ScheduleItem>,
        seedN: ULong,
        removalSeed: ULong,
        walk: (List<ScheduleItem>, ULong, Caps, GroupRules) -> List<ScheduleItem>,
    ): Int {
        val gen = SeededGen(removalSeed)
        val before = walk(items, seedN, Caps(3, 1), noGroups())
        var worst = 0
        repeat(REMOVALS) {
            val removed = gen.pick(items)
            worst = maxOf(worst, displaced(before, walk(items - removed, seedN, Caps(3, 1), noGroups()), removed))
        }
        return worst
    }

    private fun capped(
        items: List<ScheduleItem>,
        seedN: ULong,
        caps: Caps,
        rules: GroupRules,
    ) = cappedShuffle(items, seedN, caps, rules)

    @Test
    fun withoutCapsItIsExactlyTheV1Shuffle() {
        forAllCases("no caps is v1", 0x9001uL, cases = 100) { gen ->
            val built = gen.pool()
            val seedN = gen.nextULong()
            val block = gen.nextInt(1..4)
            val settings = testSettings(OrderingMode.Shuffle, Seed(gen.nextULong()), block)

            assertEquals(
                Runs.form(Orderings.shuffle(built.items, seedN), block),
                Runs.cycle(settings, built.items, seedN, built.rules),
            )
        }
    }

    @Test
    fun withCapsTheRunsAreTheCappedShuffleCutIntoBlocks() {
        forAllCases("caps cut the capped shuffle", 0x9002uL, cases = 100) { gen ->
            val built = gen.pool()
            val seedN = gen.nextULong()
            val block = gen.nextInt(1..4)
            val caps = gen.caps()
            val settings = testSettings(OrderingMode.Shuffle, Seed(gen.nextULong()), block, caps = caps)

            assertEquals(
                Runs.form(cappedShuffle(built.items, seedN, caps, built.rules), block),
                Runs.cycle(settings, built.items, seedN, built.rules),
            )
        }
    }

    @Test
    fun itIsAPermutationAndAPureFunctionOfPoolSeedAndCaps() {
        forAllCases("a pure permutation", 0x9003uL, cases = 50) { gen ->
            val built = gen.pool()
            val seedN = gen.nextULong()
            val caps = gen.caps()
            val order = cappedShuffle(built.items, seedN, caps, built.rules)

            assertEquals(built.items.sortedBy { it.id.value }, order.sortedBy { it.id.value })
            assertEquals(order, cappedShuffle(built.items.reversed(), seedN, caps, built.rules))
            assertEquals(
                order,
                cappedShuffle(Orderings.shuffle(built.items, gen.nextULong()), seedN, caps, built.rules),
            )
        }
    }

    @Test
    fun withinAGroupTheOrderIsTheV1Shuffles() {
        forAllCases("in-group order is v1", 0x9004uL, cases = 300) { gen ->
            val built = gen.pool()
            val seedN = gen.nextULong()
            val order = cappedShuffle(built.items, seedN, gen.caps(), built.rules)
            val v1 = Orderings.shuffle(built.items, seedN)

            assertEquals(v1.groupBy { built.rules.groupId(it) }, order.groupBy { built.rules.groupId(it) })
        }
    }

    @Test
    fun theCapHoldsWheneverAnotherGroupHasItemsLeft() {
        forAllCases("the cap holds", 0x9005uL, cases = 1000) { gen ->
            val built = gen.pool()
            val rules = built.rules
            val caps = gen.caps()
            val order = cappedShuffle(built.items, gen.nextULong(), caps, rules)

            var run = 0
            for ((index, item) in order.withIndex()) {
                run = if (index > 0 && rules.groupId(order[index - 1]) == rules.groupId(item)) run + 1 else 1
                if (run <= rules.cap(item, caps)) continue
                val rest = order.subList(index, order.size)
                assertTrue(rest.all { rules.groupId(it) == rules.groupId(item) }, "position $index runs past its cap")
            }
        }
    }

    @Test
    fun aBalancedPoolEqualsThePlainWalk() {
        forAllCases("balanced is plain", 0x9006uL, cases = 20) { gen ->
            val items = (1..20).flatMap { gen.series(10) } + gen.films(40)
            val seedN = gen.nextULong()

            assertEquals(
                plainWalk(items, seedN, Caps(3, 1), noGroups()),
                cappedShuffle(items, seedN, Caps(3, 1), noGroups()),
            )
        }
    }

    @Test
    fun aSingleGroupPlaysStraightThrough() {
        val gen = SeededGen(0x9007uL)
        val episodes = gen.series(20)

        val order = cappedShuffle(episodes, 11uL, Caps(3, 1), noGroups())

        assertEquals(Orderings.shuffle(episodes, 11uL), order)
    }

    @Test
    fun aDominantGroupsForcedRunIsTheLowerBound() {
        val gen = SeededGen(0x9008uL)
        val items = gen.series(100) + gen.films(10)
        // d - cap * m = 100 - 3 * 10.
        for (seedN in 1uL..50uL) {
            val order = cappedShuffle(items, seedN, Caps(3, 1), noGroups())

            assertEquals(70, longestRun(order, noGroups()), "seed $seedN")
            assertEquals(70, runLengths(order, noGroups()).last(), "seed $seedN")
        }
    }

    @Test
    fun clumpedFilmsAtTheFrontOfTheShuffleAreStillSpaced() {
        // Search the film IDs until the version 1 shuffle puts at least 4 of the 10 films in its first 8 slots (about
        // 1 pool in 470). The plain walk then uses them up back to back, and the forced run at the end grows past the
        // bound.
        val seedN = 5uL
        val episodes = SeededGen(0x9009uL).series(100)
        val items =
            (0uL until 10_000uL)
                .asSequence()
                .map { episodes + SeededGen(0x9109uL + it).films(10) }
                .firstOrNull { pool -> Orderings.shuffle(pool, seedN).take(8).count { it.seriesId == null } >= 4 }
        assertNotNull(items, "no pool clumps the films within the search")

        val plain = longestRun(plainWalk(items, seedN, Caps(3, 1), noGroups()), noGroups())
        assertTrue(plain > 70, "the plain walk's longest run is $plain")
        assertEquals(70, longestRun(cappedShuffle(items, seedN, Caps(3, 1), noGroups()), noGroups()))
    }

    @Test
    fun anAlmostBalancedPoolEndsWithoutAForcedTail() {
        forAllCases("almost balanced", 0x900AuL, cases = 50) { gen ->
            // 30 <= 3 * (10 + 1): the caps can be kept to the end.
            val items = gen.series(30) + gen.films(10)

            assertTrue(longestRun(cappedShuffle(items, gen.nextULong(), Caps(3, 1), noGroups()), noGroups()) <= 3)
        }
    }

    @Test
    fun theFormatsWorkedExample() {
        // docs/rules-format.md, The capped shuffle: show A of five (cap 2) and the films f and g, which the version 1
        // shuffle puts in the order f A g A A A A.
        val gen = SeededGen(0x900BuL)
        val episodes = gen.series(5)
        val f = film(gen.id())
        val g = film(gen.id())
        val items = episodes + f + g
        val seedN =
            (1uL..10_000uL).first { seed ->
                val shuffled = Orderings.shuffle(items, seed)
                shuffled[0] == f && shuffled[2] == g
            }
        val a = Orderings.shuffle(episodes, seedN)

        assertEquals(listOf(f, a[0], g, a[1], a[2], a[3], a[4]), plainWalk(items, seedN, Caps(2, 1), noGroups()))
        assertEquals(listOf(a[0], f, a[1], a[2], g, a[3], a[4]), cappedShuffle(items, seedN, Caps(2, 1), noGroups()))
    }

    @Test
    fun removingOneItemDisplacesFewOthers() {
        val balanced = SeededGen(0x900CuL)
        val balancedItems = (1..20).flatMap { balanced.series(10) } + balanced.films(40)
        val dominant = SeededGen(0x900DuL)
        val dominantItems = dominant.series(100) + dominant.films(10)

        val balancedWorst = worstDisplacement(balancedItems, 21uL, 0x900EuL, ::capped)
        val dominantWorst = worstDisplacement(dominantItems, 22uL, 0x900FuL, ::capped)
        // The plain walk's numbers, for comparison in the PR text: the same removals.
        val balancedPlain = worstDisplacement(balancedItems, 21uL, 0x900EuL, ::plainWalk)
        val dominantPlain = worstDisplacement(dominantItems, 22uL, 0x900FuL, ::plainWalk)
        println("Worst displaced of $REMOVALS removals, capped: balanced $balancedWorst, dominant $dominantWorst")
        println("Worst displaced of $REMOVALS removals, plain: balanced $balancedPlain, dominant $dominantPlain")

        assertTrue(balancedWorst <= 1, "balanced pool: $balancedWorst displaced")
        assertTrue(dominantWorst <= 15, "dominant pool: $dominantWorst displaced")
    }

    private companion object {
        const val REMOVALS = 60
    }
}
