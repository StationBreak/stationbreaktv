// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.SchedulerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The planned end of each day (spec 08, Unit 3, Q2): when the next run would end after the next `S`, it is not started.
 * Instead one pass over the cycle's items, from that run's first item, places each eligible item that fits.
 * Cases are worked by hand on a 1-hour or 2-hour day that starts at minute 0.
 */
class EndFillTest {
    private fun settings(
        mode: OrderingMode,
        alignToGrid: Boolean = false,
        epochHours: Int = 1,
        blockLength: Int = 1,
        seed: Seed = Seed(1uL),
    ) = testSettings(
        orderingMode = mode,
        seed = seed,
        blockLength = blockLength,
        alignToGrid = alignToGrid,
        epochHours = epochHours,
        startMinuteOfDay = 0,
    )

    /** A seed for which `shuffle` puts [items] in the order [wanted] on day 0, so a hand-worked case can be written. */
    private fun shuffleSeedFor(
        base: SchedulerSettings,
        items: List<ScheduleItem>,
        wanted: String,
    ): SchedulerSettings {
        for (candidate in 1L..200_000L) {
            val trial = base.copy(seed = Seed(candidate.toULong()))
            val order = Orderings.shuffle(EpochPool.build(trial, items, 0L), Epochs.seedN(trial.seed, 0L))
            if (order.joinToString("") {
                    it.id.value
                        .first()
                        .toString()
                } == wanted
            ) {
                return trial
            }
        }
        fail("no seed gives the order $wanted")
    }

    // A series with A3 too long for the gap and A4 short enough: the group rule.
    private val seriesA =
        listOf(
            episode('1', 'e', 1, 1, 20),
            episode('2', 'e', 1, 2, 20),
            episode('3', 'e', 1, 3, 30),
            episode('4', 'e', 1, 4, 10),
        )

    @Test
    fun chronologicalDoesNotPlaceA4WhenA3DoesNotFit() {
        val built = buildDay(settings(OrderingMode.Chronological), seriesA, 0L)
        assertEquals(listOf("0-20:1", "20-40:2", "40-60:slate"), built.describe())
        assertEquals(2, built.regularRunCount)
    }

    @Test
    fun randomInOrderDoesNotPlaceA4WhenA3DoesNotFit() {
        val built = buildDay(settings(OrderingMode.RandomInOrder), seriesA, 0L)
        assertEquals(listOf("0-20:1", "20-40:2", "40-60:slate"), built.describe())
    }

    @Test
    fun shuffleDoesPlaceA4BecauseItHasNoGroupRule() {
        val s = shuffleSeedFor(settings(OrderingMode.Shuffle), seriesA, "1234")
        val built = buildDay(s, seriesA, 0L)
        // X3 (30 min) does not fit at minute 40; X4 (10 min) does. The pass then wraps to X1 and X2, which do not fit.
        assertEquals(listOf("0-20:1", "20-40:2", "40-50:4", "50-60:slate"), built.describe())
    }

    private val shortThenLong = listOf(movie('1', 10), movie('2', 10), movie('3', 10), movie('4', 50))

    @Test
    fun chronologicalStopsAtTheCycleEndInsteadOfWrapping() {
        // M4 (50 min) does not fit at minute 30. M1 would fit again, but an earlier item is never replayed after a
        // later one.
        val built = buildDay(settings(OrderingMode.Chronological), shortThenLong, 0L)
        assertEquals(listOf("0-10:1", "10-20:2", "20-30:3", "30-60:slate"), built.describe())
    }

    @Test
    fun shuffleWrapsAndFillsTheRestOfTheDay() {
        val s = shuffleSeedFor(settings(OrderingMode.Shuffle), shortThenLong, "1234")
        val built = buildDay(s, shortThenLong, 0L)
        assertEquals(
            listOf("0-10:1", "10-20:2", "20-30:3", "30-40:1", "40-50:2", "50-60:3"),
            built.describe(),
        )
    }

    @Test
    fun theShufflePassVisitsAtMostNItems() {
        // N = 3. M3 (50 min) does not fit at minute 20; the pass visits M3, M1, M2 and stops. It does not go round
        // again.
        val items = listOf(movie('1', 10), movie('2', 10), movie('3', 50))
        val s = shuffleSeedFor(settings(OrderingMode.Shuffle), items, "123")
        val built = buildDay(s, items, 0L)
        assertEquals(listOf("0-10:1", "10-20:2", "20-30:1", "30-40:2", "40-60:slate"), built.describe())
    }

    @Test
    fun placedItemsAreBlocksOfOneAndDoNotJoinRunGrouping() {
        // Runs of two: [1 2] = 40 min, [3 4] = 40 min, which does not fit at minute 40. M3 (30) does not fit, M4 (10)
        // does.
        val items = listOf(movie('1', 20), movie('2', 20), movie('3', 30), movie('4', 10))
        val built = buildDay(settings(OrderingMode.Chronological, blockLength = 2), items, 0L)
        assertEquals(listOf("0-20:1", "20-40:2", "40-50:4", "50-60:slate"), built.describe())
        assertEquals(1, built.regularRunCount)
    }

    @Test
    fun theGridGapBeforeAPlacedItemIsASlate() {
        // 30-minute grid. M2 (80 min) would start at 60 and end at 140 > 120. M3 (20 min) fits at 60.
        val items = listOf(movie('1', 50), movie('2', 80), movie('3', 20))
        val built = buildDay(settings(OrderingMode.Chronological, alignToGrid = true, epochHours = 2), items, 0L)
        assertEquals(listOf("0-50:1", "50-60:slate", "60-80:3", "80-120:slate"), built.describe())
    }

    @Test
    fun runsLeftOutOfTheCycleAreNotVisitedByTheFill() {
        // Runs of two: [1 2] = 100 min; [3 4] = 125 min is longer than the 120-minute epoch, so it is left out and its
        // items (M4 is only 10 min) are not visited by the fill.
        val items = listOf(movie('1', 50), movie('2', 50), movie('3', 115), movie('4', 10))
        val built = buildDay(settings(OrderingMode.Chronological, epochHours = 2, blockLength = 2), items, 0L)
        assertEquals(listOf("0-50:1", "50-100:2", "100-120:slate"), built.describe())
    }

    @Test
    fun aMoviesOnlyPoolLeavesALongSlate() {
        val items = listOf(movie('1', 120), movie('2', 110))
        val built = buildDay(settings(OrderingMode.Chronological, alignToGrid = true, epochHours = 3), items, 0L)
        assertEquals(listOf("0-120:1", "120-180:slate"), built.describe())
    }

    @Test
    fun nothingIsCutAndNothingRunsPastTheBoundary() {
        val s = settings(OrderingMode.Chronological, alignToGrid = true, epochHours = 1)
        val built = buildDay(s, listOf(movie('1', 45)), 0L)
        assertEquals(listOf("0-45:1", "45-60:slate"), built.describe())
        assertTrue(built.blocks.all { it.endMs <= built.endMs })
    }

    // --- The single pass against "first fit, repeat" ----------------------------------------------------------------

    @Test
    fun theSinglePassMatchesFirstFitRepeatOn1000GeneratedCases() {
        var placedByFill = 0
        var placedByFillInOrderedModes = 0
        forAllCases("end fill against first fit, repeat", 0xE4DF111uL) { gen ->
            val case = generateCase(gen)
            val expected = DayOracle.blocks(case.settings, case.items, case.n)
            val actual = buildDay(case.settings, case.items, case.n)
            val label = "settings ${case.settings}, n ${case.n}, items ${case.items.map {
                it.id.value.take(
                    4,
                ) to it.runtimeMs / MS_MINUTE
            }}"
            assertEquals(expected, actual.blocks, label)

            val cycle =
                Timeline.cycle(
                    case.settings,
                    EpochPool.build(case.settings, case.items, case.n),
                    Epochs.seedN(case.settings.seed, case.n),
                )
            val regularItems =
                (0 until actual.regularRunCount).sumOf { cycle[(actual.startRunIndex + it) % cycle.size].items.size }
            val filled = actual.blocks.count { it.content is ScheduleContent.Program } - regularItems
            if (filled > 0) placedByFill++
            if (filled > 0 && case.settings.orderingMode != OrderingMode.Shuffle) placedByFillInOrderedModes++
        }
        // The generator reaches the interesting branch in every mode family, so the comparison is not vacuous.
        assertTrue(placedByFill >= 20, "only $placedByFill cases placed items in the end fill")
        assertTrue(placedByFillInOrderedModes >= 10, "only $placedByFillInOrderedModes ordered-mode cases filled")
    }
}

/** A generated channel: settings, items and a day number. */
internal class GeneratedCase(
    val settings: SchedulerSettings,
    val items: List<ScheduleItem>,
    val n: Long,
)

/** Mixed modes, grids, offsets, series and runtimes, with epochs short enough for the end of the day to matter. */
internal fun generateCase(gen: SeededGen): GeneratedCase {
    val grid = gen.pick(listOf(15, 30, 60))
    val settings =
        testSettings(
            orderingMode = gen.pick(OrderingMode.entries),
            seed = Seed(gen.nextULong()),
            blockLength = gen.pick(listOf(1, 1, 2, 3)),
            alignToGrid = gen.chance(60),
            gridMinutes = grid,
            epochHours = gen.pick(listOf(1, 2, 3, 4, 6)),
            utcOffsetMinutes = gen.nextInt(-48..56) * 15,
            startMinuteOfDay = grid * gen.nextInt(1440 / grid),
            graceHours = gen.nextInt(0..12),
        )
    val count = gen.nextInt(1..14)
    val seriesIds = listOf('a', 'b', 'c')
    val items =
        List(count) { index ->
            val minutes = gen.pick(listOf(5, 10, 15, 20, 25, 30, 45, 60, 90, 120, 200)) + gen.nextInt(0..4)
            val id = gen.hexId()
            if (gen.chance(60)) {
                val series = idOf(gen.pick(seriesIds))
                ScheduleItem(
                    id = CanonicalId.parse(id),
                    kind = ScheduleItemKind.Episode,
                    runtimeMs = minutes * MS_MINUTE,
                    createdAtMs = LONG_AGO,
                    deletedAtMs = null,
                    premiereDate = null,
                    productionYear = null,
                    seriesId = series,
                    seasonNumber = 1 + index / 5,
                    episodeNumber = 1 + index,
                )
            } else {
                testItem(CanonicalId.parse(id), runtimeMs = minutes * MS_MINUTE, createdAtMs = LONG_AGO)
            }
        }
    return GeneratedCase(settings, items, gen.nextInt(-5..5).toLong())
}
