// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import tv.stationbreak.core.scheduler.Epochs
import tv.stationbreak.core.scheduler.layoutTail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A selection repair over a day whose end is spread as station breaks (rules format version 5). */
internal class SpreadDayRepairTest : RepairTestBase() {
    private val offGrid = Timing(null, false, null, null, null, null, BroadcastDay(0, "06:00"))
    private val spreadRules = rules("Spread", Source.Genre("Comedy"), timing = offGrid).copy(formatVersion = 5)
    private val spread = custom("custom-spread", 650, spreadRules)
    private val comedies100 = (0 until 7).map { film(800 + it, "Comedy", minutes = 100) }

    @Test
    fun aRepairDuringABreakKeepsTheBreakAsABreak() {
        val rig = Rig(lineupOf(spread), comedies100 + comediesB, setOf(libA, libB))
        val day = rig.day(spread.id)
        val onAir =
            day.day.blocks.firstOrNull { it.content == ScheduleContent.Break && it.startMs > day.epochStartMs }
        assertNotNull(onAir, "the spread day has a break")
        val at = onAir.startMs + onAir.lengthMs / 2

        assertNotNull(rig.change(before = setOf(libA, libB), after = setOf(libA), at = at))

        val repair = rig.repairOf(spread.id)
        assertContiguous(repair)
        assertEquals(onAir.endMs, repair.pinEndMs, "the break on air is the pin")
        assertEquals(null, repair.pinLibrary, "a break names no library")
        val kept = KeptBlock(maxOf(onAir.startMs, repair.keptFromMs), onAir.endMs, KeptContent.Break)
        assertTrue(kept in repair.blocks, "the break is kept as a break, not a slate")
        assertTrue(repair.tailIds().none { it in ids(comediesB) }, "nothing of the removed library")
        assertTrue(repair.blocks.none { it.content == KeptContent.Slate }, "no slate on a spread day off the grid")
    }

    @Test
    fun aSpreadRepairTailHonoursWhatTheKeptWindowAired() {
        val rig = Rig(lineupOf(spread), comedies100 + comediesB, setOf(libA, libB))
        val day = rig.day(spread.id)
        val pool = day.pool.filter { it.id !in ids(comediesB) }

        assertNotNull(rig.change(before = setOf(libA, libB), after = setOf(libA)))

        val repair = rig.repairOf(spread.id)
        val aired = programmesBefore(day, repair.pinEndMs)
        val before = pastAirings(day, repair.pinEndMs)
        val expected = layoutTail(day.settings, pool, day.n, day.films, day.weights, repair.pinEndMs, aired, before)
        assertEquals(expected.map { kept(it) }, repair.tail())
    }

    @Test
    fun aSecondRepairCountsAShortTheFirstRepairKept() {
        val short = film(820, "Comedy", minutes = 15)
        val settings = spreadRules.toSchedulerSettings()
        val n = Epochs.epochNumber(settings, atMs)
        val end = Epochs.epochStart(settings, n + 1)
        // The first repair, at noon: the short on air 12:00-12:15 (its pin), then 100-minute films, then a break.
        val shortEnd = noonMs + 15 * minute
        val blocks = arrayListOf(KeptBlock(noonMs, shortEnd, KeptContent.Program(short.item.id, 0)))
        var t = shortEnd
        var i = 0
        while (t + 100 * minute <= end) {
            blocks += KeptBlock(t, t + 100 * minute, KeptContent.Program(comedies100[i++ % 7].item.id, 0))
            t += 100 * minute
        }
        if (t < end) blocks += KeptBlock(t, end, KeptContent.Break)
        val first =
            ChannelRepair(spread.id, n, end, noonMs + 5 * minute, 3, setOf(libA), noonMs, shortEnd, libA, blocks, null)
        val rig = Rig(lineupOf(spread), comedies100 + short, setOf(libA), TodayRepairs(selection = listOf(first)))
        // A second change at 15:10: its window starts at 15:00, after the short.
        val at = noonMs + 3 * hour + 10 * minute
        val day = rig.day(spread.id, at)
        runBlocking {
            val fills = Fills(rig.library, ACCOUNT)
            fills.live(KeptWindow.programmesOf(day, at))
            val kept = KeptWindow.of(day, at, 4, fills)
            val dayPool = fills.dayPool(day)
            assertTrue(dayPool.none { it.id == short.item.id }, "the recorded tail does not hold the short")
            assertTrue(day.pool.isEmpty(), "a repaired day made from its record has no pool as built")
            val past = kept.pastAirings(day, fills)
            val counted = past.any { it.item.id == short.item.id && it.endMs == shortEnd }
            assertTrue(counted, "the short counts, from its end")
            assertTrue(past.all { it.item.id in kept.aired })
        }
    }

    @Test
    fun aGoneProgrammeBeforeTheWindowDoesNotCountAsSeen() {
        val rig = Rig(lineupOf(spread), comedies100 + comediesB, setOf(libA, libB))
        val day = rig.day(spread.id)
        val from = noonMs
        val before = day.day.blocks.filter { it.endMs <= from && it.content is ScheduleContent.Program }
        val gone = (before.first().content as ScheduleContent.Program).itemId
        rig.gone(gone)
        runBlocking {
            val fills = Fills(rig.library, ACCOUNT)
            fills.live(KeptWindow.programmesOf(day, atMs))
            val kept = KeptWindow.of(day, atMs, 4, fills)
            assertTrue(gone in kept.aired, "still left out of the tail")
            assertTrue(kept.airings.none { it.itemId == gone }, "a gone programme was not seen")
            val live = before.mapNotNull { (it.content as ScheduleContent.Program).itemId.takeIf { id -> id != gone } }
            assertTrue(live.isNotEmpty() && live.all { id -> kept.airings.any { it.itemId == id } })
        }
    }

    @Test
    fun aBreakAtThePinAndABreakStartingTheTailBecomeOneBreak() {
        val window =
            KeptWindow(
                fromMs = 0L,
                pinEndMs = 20L,
                blocks = listOf(KeptBlock(0L, 10L, KeptContent.Slate), KeptBlock(10L, 20L, KeptContent.Break)),
                fixes = emptyList(),
                aired = emptySet(),
                airings = emptyList(),
                pinItem = null,
            )
        val programme = ScheduleBlock(30L, 40L, ScheduleContent.Program(comedies100[0].item.id))
        val joined = RepairSeam.of(window, listOf(ScheduleBlock(20L, 30L, ScheduleContent.Break), programme))
        assertEquals(listOf(KeptBlock(0L, 10L, KeptContent.Slate), KeptBlock(10L, 30L, KeptContent.Break)), joined.kept)
        assertEquals(listOf(programme), joined.tail)
        assertEquals(30L, joined.pinEndMs)
        // A programme at the start of the tail is not joined.
        val apart = RepairSeam.of(window, listOf(programme.copy(startMs = 20L)))
        assertEquals(window.blocks, apart.kept)
        assertEquals(20L, apart.pinEndMs)
        assertEquals(null, RepairSeam.of(null, listOf(programme)).pinEndMs)
    }
}
