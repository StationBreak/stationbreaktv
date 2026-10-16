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

/**
 * The continuity guarantee (spec 08, Unit 3, Granny H1) for `chronological` and `randomInOrder` with an unchanged pool.
 *
 * Runs of the cycle are numbered along the continuous playout, forever: absolute run `k` is cycle run `k mod B` in lap
 * `k div B`. A day that starts at cycle run `j` with `S` in lap `L` starts at absolute run `L * B + j`. The day plays
 * `r = regularRunCount` runs from there, so `u = start + r` is the first run not started in the regular layout.
 * The next day starts at absolute run `j'`. The guarantee: `j' >= u` (no run of the regular layout is played again) and
 * the runs skipped are exactly `u..j'-1`, which add up to no more than the time left unused at the end of the day plus
 * the lag `delta` of day n. With every slot equal to one grid slot, `delta` is 0 and nothing is skipped.
 */
class ContinuityTest {
    private class Channel(
        val settings: SchedulerSettings,
        val items: List<ScheduleItem>,
        val firstDay: Long,
    )

    /** A generated channel whose days all have at least one run in the cycle (the maths needs a cycle to be in). */
    private fun generate(
        gen: SeededGen,
        oneGridSlotEach: Boolean,
    ): Channel {
        while (true) {
            val channel = generateAny(gen, oneGridSlotEach)
            val s = channel.settings
            if (Timeline.cycle(s, EpochPool.build(s, channel.items, 0L), Epochs.seedN(s.seed, 0L)).isNotEmpty()) return channel
        }
    }

    private fun generateAny(
        gen: SeededGen,
        oneGridSlotEach: Boolean,
    ): Channel {
        val grid = gen.pick(listOf(15, 30, 60))
        val epochHours = gen.pick(listOf(1, 2, 3, 4, 6, 8, 12, 24))
        val settings =
            testSettings(
                orderingMode = gen.pick(listOf(OrderingMode.Chronological, OrderingMode.RandomInOrder)),
                seed = Seed(gen.nextULong()),
                blockLength = if (oneGridSlotEach) 1 else gen.pick(listOf(1, 1, 2, 3)),
                alignToGrid = oneGridSlotEach || gen.chance(70),
                gridMinutes = grid,
                epochHours = epochHours,
                utcOffsetMinutes = gen.nextInt(-48..56) * 15,
                startMinuteOfDay = grid * gen.nextInt(1440 / grid),
            )
        val items =
            List(gen.nextInt(2..12)) {
                val minutes = if (oneGridSlotEach) gen.nextInt(1..grid) else gen.pick(listOf(7, 22, 30, 41, 55, 62, 90, 118, 150))
                val id = CanonicalId.parse(gen.hexId())
                if (gen.chance(50)) {
                    ScheduleItem(
                        id = id,
                        kind = ScheduleItemKind.Episode,
                        runtimeMs = minutes * MS_MINUTE,
                        createdAtMs = LONG_AGO,
                        deletedAtMs = null,
                        premiereDate = null,
                        productionYear = null,
                        seriesId = idOf(gen.pick(listOf('a', 'b'))),
                        seasonNumber = 1,
                        episodeNumber = it + 1,
                    )
                } else {
                    testItem(id, runtimeMs = minutes * MS_MINUTE, createdAtMs = LONG_AGO)
                }
            }
        return Channel(settings, items, gen.nextInt(-4..4).toLong())
    }

    /** What the test needs to know about one day of a channel with a fixed pool. */
    private class Position(
        val day: Day,
        val absoluteStart: Long,
        val lag: Long,
        val cycle: List<Run>,
        val slots: List<Long>,
    ) {
        val firstUnstarted: Long get() = absoluteStart + day.regularRunCount
    }

    private fun position(
        settings: SchedulerSettings,
        items: List<ScheduleItem>,
        n: Long,
    ): Position {
        val day = buildDay(settings, items, n)
        val cycle = Timeline.cycle(settings, EpochPool.build(settings, items, n), Epochs.seedN(settings.seed, n))
        val slots = cycle.map { DayOracle.slot(settings, it) }
        val total = slots.sum()
        val sOnClock = day.startMs + settings.offsetMs
        val pos = sOnClock.mod(total)
        val before = slots.take(day.startRunIndex).sum()
        return Position(
            day = day,
            absoluteStart = sOnClock.floorDiv(total) * cycle.size + day.startRunIndex,
            lag = pos - before,
            cycle = cycle,
            slots = slots,
        )
    }

    @Test
    fun withAnUnchangedPoolNoRunIsRepeatedAndTheSkippedRunsAreExactlyUToJMinusOne() {
        var seamsWithASkip = 0
        forAllCases("continuity", 0xC0471uL) { gen ->
            val channel = generate(gen, oneGridSlotEach = false)
            val s = channel.settings
            var today = position(s, channel.items, channel.firstDay)
            for (step in 1..4) {
                val tomorrow = position(s, channel.items, channel.firstDay + step)
                val b = today.cycle.size
                assertEquals(today.cycle, tomorrow.cycle, "the pool is unchanged, so the cycle is")

                // The regular layout of day n played absolute runs [start, u): check the blocks say so.
                val played =
                    (0 until today.day.regularRunCount).flatMap {
                        today.cycle[
                            (today.absoluteStart + it)
                                .mod(
                                    b.toLong(),
                                ).toInt(),
                        ].items
                    }
                val programs = today.day.blocks.filter { it.content is ScheduleContent.Program }
                assertEquals(
                    played.map { ScheduleContent.Program(it.id) },
                    programs.take(played.size).map { it.content },
                    "the regular layout plays the runs from the start run on",
                )
                // Day n+1 starts at run j' exactly at its S.
                val firstOfTomorrow = tomorrow.cycle[tomorrow.absoluteStart.mod(b.toLong()).toInt()].items.first()
                assertEquals(
                    ScheduleContent.Program(firstOfTomorrow.id),
                    tomorrow.day.blocks
                        .first()
                        .content,
                )

                val u = today.firstUnstarted
                val jNext = tomorrow.absoluteStart
                assertTrue(jNext >= u, "run ${u - 1} or earlier would be played again: u = $u, j' = $jNext")
                // The skipped runs are u..j'-1; they fit in the unused time plus the lag.
                val skippedSlots = (u until jNext).sumOf { today.slots[it.mod(b.toLong()).toInt()] }
                val regularSlots =
                    (0 until today.day.regularRunCount).sumOf {
                        today.slots[
                            (today.absoluteStart + it)
                                .mod(
                                    b.toLong(),
                                ).toInt(),
                        ]
                    }
                val unused = today.day.endMs - (today.day.startMs + regularSlots)
                assertTrue(skippedSlots <= unused + today.lag, "skipped $skippedSlots ms > unused $unused ms + lag ${today.lag} ms")
                if (jNext > u) seamsWithASkip++
                today = tomorrow
            }
        }
        assertTrue(seamsWithASkip > 0, "the generator never produced a seam with a skip")
    }

    @Test
    fun theLagIsLessThanTheSlotOfTheRunTheDayStartsAt() {
        forAllCases("continuity lag bound", 0x1A6uL) { gen ->
            val channel = generate(gen, oneGridSlotEach = false)
            for (step in 0..3) {
                val p = position(channel.settings, channel.items, channel.firstDay + step)
                assertTrue(p.lag >= 0 && p.lag < p.slots[p.day.startRunIndex], "lag ${p.lag}, slot ${p.slots[p.day.startRunIndex]}")
            }
        }
    }

    @Test
    fun whenEverySlotIsOneGridSlotNothingIsSkipped() {
        forAllCases("continuity without skips", 0x5106uL) { gen ->
            val channel = generate(gen, oneGridSlotEach = true)
            val s = channel.settings
            var today = position(s, channel.items, channel.firstDay)
            assertEquals(0L, today.lag)
            for (step in 1..4) {
                val tomorrow = position(s, channel.items, channel.firstDay + step)
                assertEquals(0L, tomorrow.lag)
                assertEquals(today.firstUnstarted, tomorrow.absoluteStart, "a skip at the seam, or a repeat")
                today = tomorrow
            }
        }
    }

    @Test
    fun equalSlotsAloneAreNotEnough() {
        // Three 90-minute movies on a 30-minute grid: every slot is 90 minutes, but that is three grid slots, not one.
        // A 4-hour day starting at 00:30 begins 30 minutes into run 0 of the continuous playout (lag 30), plays runs 0
        // and 1 (180 minutes), cannot fit run 2 in the last 60 minutes, and the next day starts at run 0 of the next lap:
        // run 2 is skipped (reference, 2026-10-16: equal slots with a lag).
        val s =
            testSettings(
                orderingMode = OrderingMode.Chronological,
                alignToGrid = true,
                gridMinutes = 30,
                epochHours = 4,
                startMinuteOfDay = 30,
            )
        val items = listOf(movie('1', 90), movie('2', 90), movie('3', 90))
        val today = position(s, items, 0L)
        assertEquals(30L * MS_MINUTE, today.lag)
        assertEquals(2L, today.firstUnstarted)
        assertEquals(listOf("30-120:1", "120-210:2", "210-270:slate"), today.day.describe())
        val tomorrow = position(s, items, 1L)
        assertEquals(3L, tomorrow.absoluteStart)
        assertEquals(0L, tomorrow.lag)
    }
}
