// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.model.schedule.TitleWeights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CuratedWalkTest {
    private fun settings(
        alignToGrid: Boolean = false,
        repeatGapMs: Long = 3 * MS_HOUR,
        pairUpToMs: Long = 35 * MS_MINUTE,
    ): SchedulerSettings =
        testSettings(
            OrderingMode.Curated,
            alignToGrid = alignToGrid,
            repeatGapMs = repeatGapMs,
            pairUpToMs = pairUpToMs,
        )

    private fun walk(
        pool: List<ScheduleItem>,
        seedN: ULong = 1uL,
        settings: SchedulerSettings = settings(),
        films: FilmCollections = noFilms(),
        weights: TitleWeights = noWeights(),
    ): List<Run> = curatedRuns(pool, seedN, GroupRules(films, weights), settings)

    private fun bigPool(): List<ScheduleItem> =
        (1..10).flatMap { s -> (1..20).map { episodeOf(s, it, 22) } } + (1..20).map { filmOf(it, 100) }

    @Test
    fun itIsAPermutationOfThePool() {
        val pool = bigPool()
        val runs = walk(pool)
        val ids = runs.flat().map { it.id.value }
        assertEquals(pool.size, ids.size)
        assertEquals(pool.map { it.id.value }.sorted(), ids.sorted())
    }

    @Test
    fun itIsAPureFunctionOfPoolSeedMapAndWeights() {
        val pool = bigPool()
        val weights = TitleWeights.of(mapOf(seriesIdOf(2) to 3, filmIdOf(4) to 2))
        val films = FilmCollections.of(mapOf(filmIdOf(1) to filmIdOf(100), filmIdOf(2) to filmIdOf(100)))
        val first = walk(pool, 9uL, films = films, weights = weights)
        assertEquals(first, walk(pool.reversed(), 9uL, films = films, weights = weights))
        assertEquals(first, walk(pool, 9uL, films = films, weights = weights))
        assertNotEquals(first, walk(pool, 10uL, films = films, weights = weights))
        assertNotEquals(first, walk(pool, 9uL, films = films, weights = noWeights()))
    }

    @Test
    fun shortEpisodesAirInPairsInEpisodeOrder() {
        val pool = (1..4).map { episodeOf(1, it, 22) }
        val seed = seedStartingAt(seriesIdOf(1), 4, 0)
        val runs = walk(pool, seed)
        assertEquals(listOf(2, 2), runs.map { it.items.size })
        assertEquals(pool.map { it.id }, runs.flat().map { it.id })
        // A queue that starts at the third episode plays it, the fourth, then the first and the second.
        val rotated = walk(pool, seedStartingAt(seriesIdOf(1), 4, 2)).flat().map { it.episodeNumber }
        assertEquals(listOf(3, 4, 1, 2), rotated)
    }

    @Test
    fun longEpisodesAndFilmsAirAlone() {
        val pool = (1..4).map { episodeOf(1, it, 40) } + (1..3).map { filmOf(it, 20) }
        val runs = walk(pool)
        assertEquals(List(7) { 1 }, runs.map { it.items.size })
    }

    @Test
    fun aPairNeedsBothEpisodesShort() {
        val pool =
            listOf(episodeOf(1, 1, 22), episodeOf(1, 2, 40), episodeOf(1, 3, 22), episodeOf(1, 4, 22))
        val runs = walk(pool, seedStartingAt(seriesIdOf(1), 4, 0))
        // 22 + 40: no pair, the first airs alone. 40 + 22: no pair. 22 + 22: a pair.
        assertEquals(listOf(1, 1, 2), runs.map { it.items.size })
        // Exactly the limit pairs: both runtimes are at most pairUpToMs.
        val edge = listOf(episodeOf(1, 1, 35), episodeOf(1, 2, 35))
        assertEquals(listOf(2), walk(edge, seedStartingAt(seriesIdOf(1), 2, 0)).map { it.items.size })
    }

    @Test
    fun noGroupRepeatsWithinThreeHoursWhenOthersCan() {
        val pool = bigPool()
        val groups = GroupRules(noFilms(), noWeights())
        for (seed in 1uL..50uL) {
            val runs = walk(pool, seed, settings(alignToGrid = true))
            assertNoEarlyRepeat(runs, groups, settings(alignToGrid = true), "seed $seed")
        }
    }

    // A group airs again before the gap only when no other group with items left was ready.
    private fun assertNoEarlyRepeat(
        runs: List<Run>,
        groups: GroupRules,
        settings: SchedulerSettings,
        label: String,
    ) {
        val left = HashMap<CanonicalId, Int>()
        for (run in runs) for (item in run.items) left[groups.groupId(item)] = (left[groups.groupId(item)] ?: 0) + 1
        val last = HashMap<CanonicalId, Long>()
        var clock = 0L
        for (run in runs) {
            val group = groups.groupId(run.items.first())
            val earlier = last[group]
            if (earlier != null && clock - earlier < checkNotNull(settings.curated).repeatGapMs) {
                for ((other, count) in left) {
                    if (other == group || count == 0) continue
                    val seen = last[other]
                    val waiting = seen != null && clock - seen < checkNotNull(settings.curated).repeatGapMs
                    assertTrue(waiting, "$label: $other was ready at $clock")
                }
            }
            last[group] = clock
            left[group] = left.getValue(group) - run.items.size
            clock += Timeline.slotMs(settings, run)
        }
    }

    @Test
    fun aFranchiseIsOneGroup() {
        val films = (1..3).map { filmOf(it, 100, year = 1990 + it) }
        val others = (4..20).map { filmOf(it, 100) }
        val map = FilmCollections.of(films.associate { it.id to filmIdOf(100) })
        val pool = films + others
        val groups = GroupRules(map, noWeights())
        for (seed in 1uL..20uL) {
            val runs = walk(pool, seed, films = map)
            assertNoEarlyRepeat(runs, groups, settings(), "seed $seed")
            // The franchise plays in release order, from a rotated start.
            val order = runs.flat().filter { it.id in films.map { f -> f.id } }.map { it.productionYear }
            val rotations = listOf(listOf(1991, 1992, 1993), listOf(1992, 1993, 1991), listOf(1993, 1991, 1992))
            assertTrue(order in rotations, "seed $seed: $order")
        }
    }

    @Test
    fun aPoolShorterThanTheGapRelaxesToTheLongestGap() {
        val pool = (1..3).map { episodeOf(1, it, 40) } + (1..3).map { episodeOf(2, it, 40) }
        for (seed in 1uL..10uL) {
            val groups = walk(pool, seed).map { it.items.first().seriesId }
            assertEquals(6, groups.size)
            for (i in 1 until groups.size) assertNotEquals(groups[i - 1], groups[i], "seed $seed")
        }
    }

    @Test
    fun aSingleGroupPlaysStraightThrough() {
        val pool = (1..5).map { episodeOf(1, it, 50) }
        val runs = walk(pool, seedStartingAt(seriesIdOf(1), 5, 0))
        assertEquals(pool.map { it.id }, runs.flat().map { it.id })
        assertEquals(5, runs.size)
    }

    @Test
    fun heavierWeightsGetMoreAirtimeEarly() {
        val pool = (1..30).map { episodeOf(1, it, 40) } + (1..30).map { episodeOf(2, it, 40) }
        val weights = TitleWeights.of(mapOf(seriesIdOf(1) to 3))
        val always = settings(repeatGapMs = 0L)
        for (seed in 1uL..20uL) {
            val first = walk(pool, seed, always, weights = weights).take(20).map { it.items.first().seriesId }
            val heavy = first.count { it == seriesIdOf(1) }
            val light = first.count { it == seriesIdOf(2) }
            assertTrue(heavy > light + 4, "seed $seed: $heavy vs $light")
        }
    }

    @Test
    fun weightOneEverywhereIsTheFileOnlyReading() {
        val pool = bigPool()
        val plain = walk(pool, 3uL)
        val ones = TitleWeights.of(pool.associate { (it.seriesId ?: it.id) to 1 })
        assertEquals(plain, walk(pool, 3uL, weights = ones))
        assertEquals(plain, walk(pool, 3uL, weights = TitleWeights.of(emptyMap())))
    }

    @Test
    fun theTunablesAreRead() {
        val pool = bigPool()
        val standard = walk(pool, 5uL)
        // The gap binds when a heavy title would otherwise come straight back.
        val two = (1..2).flatMap { s -> (1..30).map { episodeOf(s, it, 40) } }
        val heavy = TitleWeights.of(mapOf(seriesIdOf(1) to 3))
        assertNotEquals(walk(two, 5uL, weights = heavy), walk(two, 5uL, settings(repeatGapMs = 0L), weights = heavy))
        assertNotEquals(standard, walk(pool, 5uL, settings(pairUpToMs = 10 * MS_MINUTE)))
        // 40-minute episodes pair when the limit allows them, and stay alone under the default.
        val forty = (1..4).map { episodeOf(1, it, 40) }
        assertEquals(List(4) { 1 }, walk(forty).map { it.items.size })
        assertEquals(listOf(2, 2), walk(forty, settings = settings(pairUpToMs = 40 * MS_MINUTE)).map { it.items.size })
    }

    @Test
    fun theWorkedExampleOfTheFormat() {
        // Keys modulo 5400000 of 0, 100, 200 and 300 give the starting passes 0, 600, 1200 and 1800.
        val a = (1..4).map { episodeOf(1, it, 22) }
        val b = (1..2).map { episodeOf(2, it, 45) }
        val c = (1..2).map { filmOf(it, 100) }
        val d = listOf(filmOf(9, 90))
        val groups =
            listOf(
                WalkGroup(seriesIdOf(1), 0uL, a, 1, true),
                WalkGroup(seriesIdOf(2), 100uL, b, 1, true),
                WalkGroup(filmIdOf(100), 200uL, c, 1, false),
                WalkGroup(filmIdOf(9), 300uL, d, 1, false),
            )
        val runs = walkGroups(groups, settings(alignToGrid = true, pairUpToMs = 35 * MS_MINUTE))
        val names =
            runs.map { run ->
                run.items.joinToString(" ") {
                    when (it.id) {
                        d.single().id -> {
                            "d"
                        }

                        else -> {
                            "abc"[listOf(a, b, c).indexOfFirst { g -> it in g }].toString() +
                                (listOf(a, b, c).first { g -> it in g }.indexOf(it) + 1)
                        }
                    }
                }
            }
        assertEquals(listOf("a1 a2", "b1", "c1", "d", "a3 a4", "b2", "c2"), names)
    }

    @Test
    fun aGroupIsReadyAtExactlyTheGap() {
        // P (weight 3) has the smaller pass after its first run, and its run was exactly one gap ago: it goes again.
        val p = (1..2).map { episodeOf(1, it, 60) }
        val q = (1..2).map { episodeOf(2, it, 60) }
        val heavy = WalkGroup(seriesIdOf(1), 0uL, p, 3, true)
        val light = WalkGroup(seriesIdOf(2), 5_000_000uL, q, 1, true)
        val groups = listOf(heavy, light)
        val runs = walkGroups(groups, settings(repeatGapMs = 60 * MS_MINUTE))
        assertEquals(listOf(1, 1, 2, 2), runs.map { checkNotNull(it.items.first().seriesId).value.take(16).toInt(16) })
        // One millisecond more, and P has to wait for Q.
        val later = walkGroups(groups, settings(repeatGapMs = 60 * MS_MINUTE + 1))
        assertEquals(listOf(1, 2, 1, 2), later.map { checkNotNull(it.items.first().seriesId).value.take(16).toInt(16) })
    }
}
