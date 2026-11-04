// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleContent.Program
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CuratedDayTest {
    private fun settings(
        alignToGrid: Boolean,
        hours: Int = 24,
        seed: Seed = Seed(5uL),
    ) = testSettings(OrderingMode.Curated, seed = seed, alignToGrid = alignToGrid, epochHours = hours)

    private fun pool(): List<ScheduleItem> {
        val series = (1..4).flatMap { s -> (1..40).map { episodeOf(s, it, 22) } }
        return series + (1..8).map { filmOf(it, 95) }
    }

    private fun programs(day: Day): List<Program> = day.blocks.mapNotNull { it.content as? Program }

    @Test
    fun aCuratedDayStartsAtRunZeroEachDay() {
        val settings = settings(alignToGrid = true)
        for (n in -3L..30L) {
            val day = buildDay(settings, pool(), n, noFilms(), noWeights(), noKeeps())
            assertEquals(0, day.startRunIndex, "day $n")
            val walk = curatedRuns(pool(), Epochs.seedN(settings.seed, n), noGroups(), settings)
            assertEquals(
                walk
                    .first()
                    .items
                    .first()
                    .id,
                programs(day).first().itemId,
                "day $n",
            )
            assertEquals(day.startMs, day.blocks.first().startMs)
        }
    }

    @Test
    fun theGapRestartsEachDay() {
        // Two series only: within a day they alternate (nothing is ready, the longest gap goes first), but the walk
        // of the next day starts afresh with no memory of how the day before ended.
        val two = (1..2).flatMap { s -> (1..60).map { episodeOf(s, it, 40) } }
        val settings = settings(alignToGrid = false)
        var carried = 0
        for (n in 0L..60L) {
            val today = buildDay(settings, two, n, noFilms(), noWeights(), noKeeps())
            val tomorrow = buildDay(settings, two, n + 1, noFilms(), noWeights(), noKeeps())
            val fresh = curatedRuns(two, Epochs.seedN(settings.seed, n + 1), noGroups(), settings)
            assertEquals(
                fresh
                    .first()
                    .items
                    .first()
                    .id,
                programs(tomorrow).first().itemId,
                "day ${n + 1}",
            )
            val lastToday = two.first { it.id == programs(today).last().itemId }.seriesId
            val firstTomorrow = two.first { it.id == programs(tomorrow).first().itemId }.seriesId
            if (lastToday == firstTomorrow) carried++
        }
        assertTrue(carried > 0, "a title that ends a day may start the next")
    }

    @Test
    fun aCuratedDayTilesItsEpoch() {
        for (align in listOf(true, false)) {
            val settings = settings(alignToGrid = align)
            for (n in -30L..29L) {
                val day = buildDay(settings, pool(), n, noFilms(), noWeights(), noKeeps())
                assertEquals(day.startMs, day.blocks.first().startMs)
                assertEquals(day.endMs, day.blocks.last().endMs)
                for (i in 1 until day.blocks.size) {
                    assertEquals(day.blocks[i - 1].endMs, day.blocks[i].startMs, "align $align day $n block $i")
                }
                assertTrue(day.blocks.all { it.endMs > it.startMs })
            }
        }
    }

    @Test
    fun thePlannedEndWrapsLikeShuffle() {
        // One-hour days of three films: when the next film in the cycle does not fit, the fill goes on from the start
        // of the cycle, so a short film at the start of the cycle may air a second time.
        val films = listOf(filmOf(1, 5), filmOf(2, 30), filmOf(3, 45))
        var wrapped = false
        for (seed in 1uL..200uL) {
            val settings = settings(alignToGrid = false, hours = 1, seed = Seed(seed))
            val day = buildDay(settings, films, 0L, noFilms(), noWeights(), noKeeps())
            val ids = programs(day).map { it.itemId }
            if (ids.size != ids.toSet().size) wrapped = true
        }
        assertTrue(wrapped)
    }
}
