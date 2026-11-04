// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The continuity of the version 2 orders over consecutive days, through the real `buildDay` with caps and the
 * film-to-collection map (spec 22). Runs are numbered along the continuous playout as in `ContinuityTest`: a day that
 * starts at cycle run `j` in lap `L` starts at absolute run `L * B + j`, and the next day must start at or after the
 * first run this day did not start.
 */
class ContinuityV2Test {
    private class BuiltDay(
        val n: Long,
        val day: Day,
        val cycle: List<Run>,
        val absoluteStart: Long,
    ) {
        val firstUnstarted: Long get() = absoluteStart + day.regularRunCount

        /** The items of the runs the regular layout started, before the planned end. */
        val regularItems: List<ScheduleItem>
            get() = (0 until day.regularRunCount).flatMap { cycle[(day.startRunIndex + it) % cycle.size].items }
    }

    private fun build(
        settings: SchedulerSettings,
        fixture: V2Fixture,
        n: Long,
    ): BuiltDay {
        val groups = GroupRules(fixture.films, noWeights())
        val pool = EpochPool.build(settings, fixture.items, n, noKeeps())
        val cycle = Timeline.cycle(settings, pool, Epochs.seedN(settings.seed, n), groups)
        val day = buildDay(settings, fixture.items, n, fixture.films, noWeights(), noKeeps())
        val lap = (day.startMs + settings.offsetMs).floorDiv(cycle.sumOf { Timeline.slotMs(settings, it) })
        return BuiltDay(n, day, cycle, lap * cycle.size + day.startRunIndex)
    }

    private fun days(
        settings: SchedulerSettings,
        fixture: V2Fixture,
        first: Long,
        count: Int,
    ) = (0 until count).map { build(settings, fixture, first + it) }

    private fun assertNoRunReplayed(built: List<BuiltDay>) {
        for ((today, tomorrow) in built.zipWithNext()) {
            assertTrue(
                tomorrow.absoluteStart >= today.firstUnstarted,
                "day ${today.n}: day ${tomorrow.n} starts at run ${tomorrow.absoluteStart}, " +
                    "before the first unstarted run ${today.firstUnstarted}",
            )
        }
    }

    private fun capExceeds(
        sequence: List<ScheduleItem>,
        fixture: V2Fixture,
        settings: SchedulerSettings,
    ): Int {
        val rules = GroupRules(fixture.films, noWeights())
        val caps = checkNotNull(settings.caps)
        var run = 0
        var exceeds = 0
        for (index in sequence.indices) {
            val same = index > 0 && rules.groupId(sequence[index - 1]) == rules.groupId(sequence[index])
            run = if (same) run + 1 else 1
            if (run > rules.cap(sequence[index], caps)) exceeds++
        }
        return exceeds
    }

    private fun big(seed: ULong) = SeededGen(seed).v2Fixture(series = 100, episodes = 40, films = 220, collections = 30)

    @Test
    fun rotationNextDayStartsAtOrAfterTheFirstUnstartedRun() {
        val fixture = big(1uL)
        for (aligned in listOf(true, false)) {
            assertNoRunReplayed(days(V2Order.Rotation.settings(0xC0FFEEuL, aligned), fixture, 1000L, 60))
        }
    }

    @Test
    fun inOrderNextDayStartsAtOrAfterTheFirstUnstartedRun() {
        val fixture = big(2uL)
        for (aligned in listOf(true, false)) {
            assertNoRunReplayed(days(V2Order.InOrder.settings(0xC0FFEEuL, aligned), fixture, 1000L, 60))
        }
    }

    @Test
    fun noRunIsReplayedAtASeamInSmallPoolsThatWrap() {
        val gen = SeededGen(77uL)
        var cases = 0
        repeat(20) {
            val fixture = gen.v2Fixture(2 + gen.nextInt(4), 3 + gen.nextInt(8), 3 + gen.nextInt(6), 2)
            for (order in listOf(V2Order.Rotation, V2Order.InOrder)) {
                for (aligned in listOf(true, false)) {
                    assertNoRunReplayed(days(order.settings(gen.nextULong(), aligned), fixture, 5000L, 40))
                    cases++
                }
            }
        }
        assertEquals(80, cases)
    }

    // The 60 joined days never reach the cycle's wrap on this fixture (the wrap is exempt from the cap: ruling 2).
    @Test
    fun rotationRegularLayoutNeverExceedsACap() {
        val fixture = big(1uL)
        for (aligned in listOf(true, false)) {
            val settings = V2Order.Rotation.settings(0xC0FFEEuL, aligned)
            val regular = days(settings, fixture, 1000L, 60).flatMap { it.regularItems }
            assertEquals(0, capExceeds(regular, fixture, settings), "aligned=$aligned")
        }
    }

    @Test
    fun cappedShuffleExceedsNoCapInTheRegularLayout() {
        val fixture = big(3uL)
        for (aligned in listOf(true, false)) {
            val settings = V2Order.CappedShuffle.settings(0xBEEFuL, aligned)
            for (built in days(settings, fixture, 2000L, 60)) {
                assertEquals(0, capExceeds(built.regularItems, fixture, settings), "aligned=$aligned day ${built.n}")
            }
        }
    }

    @Test
    fun sixtyDaysOfCappedShuffleOpenDifferently() {
        val fixture = big(3uL)
        val settings = V2Order.CappedShuffle.settings(0xBEEFuL, aligned = true)

        val openings = days(settings, fixture, 2000L, 60).map { it.regularItems.take(OPENING).map { item -> item.id } }

        assertEquals(60, openings.toSet().size)
    }

    @Test
    fun rotationAndInOrderLeaveNoSlatesWithAlignToGridFalse() {
        val fixture = big(4uL)
        for (order in listOf(V2Order.Rotation, V2Order.InOrder)) {
            for (built in days(order.settings(0xFACEuL, aligned = false), fixture, 3000L, 30)) {
                val slates =
                    built.day.blocks
                        .withIndex()
                        .filter { it.value.content == ScheduleContent.Slate }
                // Only the closing slate of a day that ends short of a whole program may be there.
                assertTrue(slates.all { it.index == built.day.blocks.lastIndex }, "$order day ${built.n}")
            }
        }
    }

    @Test
    fun inOrderPlaysTheOrderedCycleThroughTheDayFromItsStartRun() {
        // With collections too: In order ignores the map, so a collection's films still play individually.
        for (collections in listOf(0, 3)) {
            val fixture = SeededGen(9uL).v2Fixture(series = 6, episodes = 9, films = 8, collections = collections)
            assertEquals(collections == 0, fixture.films.isEmpty, "the fixture has collection films: $collections")
            for (blockLength in listOf(1, 2)) {
                val settings = V2Order.InOrder.settings(5uL, aligned = false, blockLength = blockLength)
                for (built in days(settings, fixture, 700L, 12)) {
                    val cycleItems = built.cycle.flatMap { it.items }
                    val first = built.cycle.take(built.day.startRunIndex).sumOf { it.items.size }
                    val pool = EpochPool.build(settings, fixture.items, built.n, noKeeps())
                    assertEquals(Orderings.inOrder(pool), cycleItems, "the cycle is the in-order walk, day ${built.n}")
                    val played =
                        built.day.blocks
                            .map { (it.content as? ScheduleContent.Program)?.itemId }
                            .take(built.regularItems.size)
                    val expected = built.regularItems.indices.map { cycleItems[(first + it) % cycleItems.size].id }
                    assertEquals(expected, played, "collections $collections, block $blockLength, day ${built.n}")
                }
            }
        }
    }

    private companion object {
        const val OPENING = 5
    }
}
