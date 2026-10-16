// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** `buildDay` and `Day.whatsOn` (spec 08, Unit 3): the block at `t`, the offset into it, and the next `k` blocks. */
class WhatsOnTest {
    // Day 0 of a 3-hour chronological channel starting at minute 0:
    // 0-20:1 20-30:slate 30-55:2 55-60:slate 60-95:3 95-120:slate 120-140:1 140-150:slate 150-175:2 175-180:slate
    private val day =
        buildDay(
            testSettings(
                orderingMode = OrderingMode.Chronological,
                alignToGrid = true,
                epochHours = 3,
                startMinuteOfDay = 0,
            ),
            listOf(movie('1', 20), movie('2', 25), movie('3', 35)),
            0L,
        )

    private fun minutes(m: Int) = m * MS_MINUTE

    private fun ScheduleBlock.label(): String = "${startMs / MS_MINUTE}-${endMs / MS_MINUTE}"

    @Test
    fun theDayKnowsItsEpoch() {
        assertEquals(0L, day.n)
        assertEquals(0L, day.startMs)
        assertEquals(minutes(180), day.endMs)
    }

    @Test
    fun theBlockContainingTIsReturnedWithTheOffsetIntoTheMedia() {
        val at = day.whatsOn(minutes(10) + 500, 0)
        assertEquals("0-20", at.block.label())
        assertEquals(ScheduleContent.Program(idOf('1')), at.block.content)
        assertEquals(minutes(10) + 500, at.offsetMs)
    }

    @Test
    fun anEdgeBelongsToTheLaterBlock() {
        val edge = day.whatsOn(minutes(30), 0)
        assertEquals("30-55", edge.block.label())
        assertEquals(0L, edge.offsetMs)
        val justBefore = day.whatsOn(minutes(30) - 1, 0)
        assertEquals("20-30", justBefore.block.label())
        val atStart = day.whatsOn(0L, 0)
        assertEquals("0-20", atStart.block.label())
    }

    @Test
    fun aSlateHasNoOffset() {
        val slate = day.whatsOn(minutes(25), 1)
        assertEquals(ScheduleContent.Slate, slate.block.content)
        assertNull(slate.offsetMs)
    }

    @Test
    fun theLastMillisecondOfTheDayIsInTheLastBlock() {
        val last = day.whatsOn(minutes(180) - 1, 3)
        assertEquals("175-180", last.block.label())
        assertEquals(emptyList(), last.next)
    }

    @Test
    fun theNextBlocksAreTheFollowingOnesOfThisDayOnly() {
        assertEquals(emptyList(), day.whatsOn(0L, 0).next)
        assertEquals(listOf("20-30", "30-55"), day.whatsOn(0L, 2).next.map { it.label() })
        assertEquals(listOf("175-180"), day.whatsOn(minutes(150), 5).next.map { it.label() })
        assertEquals(9, day.whatsOn(0L, 100).next.size)
    }

    @Test
    fun anOffsetAddsTheBlocksMediaStartOffset() {
        // Version 1 always has offset 0, but the formula is mediaStartOffset + (t - wallStart).
        val withOffset =
            Day(
                n = 0L,
                startMs = 0L,
                endMs = 100L,
                blocks =
                    listOf(
                        ScheduleBlock(0L, 40L, ScheduleContent.Program(idOf('1'), 5_000L)),
                        ScheduleBlock(40L, 100L, ScheduleContent.Slate),
                    ),
                startRunIndex = 0,
                regularRunCount = 1,
                cycleRunCount = 1,
            )
        assertEquals(5_017L, withOffset.whatsOn(17L, 0).offsetMs)
    }

    @Test
    fun aTimeOutsideTheDayIsAnError() {
        assertFailsWith<IllegalArgumentException> { day.whatsOn(-1L, 1) }
        assertFailsWith<IllegalArgumentException> { day.whatsOn(minutes(180), 1) }
        assertFailsWith<IllegalArgumentException> { day.whatsOn(0L, -1) }
    }

    @Test
    fun anEmptyDayIsOneSlate() {
        val empty = buildDay(testSettings(startMinuteOfDay = 0, epochHours = 2), emptyList(), 0L)
        val at = empty.whatsOn(minutes(61), 4)
        assertEquals("0-120", at.block.label())
        assertNull(at.offsetMs)
        assertEquals(emptyList(), at.next)
    }

    @Test
    fun aNegativeDayAnswersToo() {
        val s =
            testSettings(orderingMode = OrderingMode.Chronological, alignToGrid = false, epochHours = 1, startMinuteOfDay = 0)
        val before1970 = buildDay(s, listOf(movie('1', 20)), -3L)
        assertEquals(minutes(-180), before1970.startMs)
        val at = before1970.whatsOn(minutes(-180) + minutes(25), 1)
        assertEquals(minutes(-160), at.block.startMs)
        assertEquals(minutes(5), at.offsetMs)
    }

    @Test
    fun itAgreesWithALinearScanOnGeneratedDays() {
        forAllCases("whatsOn against a linear scan", 0x0A12uL) { gen ->
            val case = generateCase(gen)
            val built = buildDay(case.settings, case.items, case.n)
            val times =
                built.blocks.flatMap { listOf(it.startMs, it.endMs - 1) } +
                    List(5) { built.startMs + (gen.nextULong() % (built.endMs - built.startMs).toULong()).toLong() }
            for (t in times) {
                val at = built.whatsOn(t, 3)
                val index = built.blocks.indexOfFirst { it.contains(t) }
                assertEquals(built.blocks[index], at.block)
                assertEquals(built.blocks.drop(index + 1).take(3), at.next)
                val content = at.block.content
                val expectedOffset = if (content is ScheduleContent.Program) content.mediaStartOffsetMs + (t - at.block.startMs) else null
                assertEquals(expectedOffset, at.offsetMs)
            }
        }
    }
}
