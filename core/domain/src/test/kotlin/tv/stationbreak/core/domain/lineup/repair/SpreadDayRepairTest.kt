// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.rules
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
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
        val before = airedBefore(day, repair.pinEndMs)
        val expected = layoutTail(day.settings, pool, day.n, day.films, day.weights, repair.pinEndMs, aired, before)
        assertEquals(expected.map { kept(it) }, repair.tail())
    }
}
