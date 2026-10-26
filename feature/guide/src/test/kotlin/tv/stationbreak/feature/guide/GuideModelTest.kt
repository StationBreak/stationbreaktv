// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelRow
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.id
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import kotlin.time.Instant

class GuideModelTest {
    private fun window(
        vararg blocks: AiringBlock,
        complete: Boolean = true,
    ) = ScheduleWindow(
        listOf(
            ChannelRow(
                ChannelAiring(CH5, 5, "Test Channel 5", Instant.fromEpochMilliseconds(T0), null),
                blocks.toList(),
                T0 + 48 * 60 * MIN,
            ),
        ),
        complete,
    )

    private fun cells(
        window: ScheduleWindow,
        nowMin: Long,
    ) = buildGuideModel(window, T0 + nowMin * MIN).rows.single().cells

    @Test
    fun originIsNowFlooredToHalfHour() {
        val model = buildGuideModel(window(program(0, 120, 1), complete = false), T0 + 74 * MIN)
        assertEquals(T0 + 60 * MIN, model.originMs)
        assertEquals(T0 + 120 * MIN, model.endMs)
        assertFalse(model.complete)
        assertEquals("Test Channel 5", model.rows.single().name)
        assertEquals(5, model.rows.single().number)
    }

    @Test
    fun endedCellsAreDropped() {
        val result = cells(window(program(0, 60, 1), program(60, 90, 2), program(90, 120, 3)), nowMin = 65)
        // Origin is minute 60: the block ending at 60 is gone, the one starting at 60 stays.
        assertEquals(listOf(T0 + 60 * MIN, T0 + 90 * MIN), result.map { it.startMs })
    }

    @Test
    fun programContinuingAcrossDayBoundaryIsOneCell() {
        val first = program(0, 60, 1, mediaStartMs = 0)
        val second = program(60, 120, 1, mediaStartMs = 60 * MIN)
        val result = cells(window(first, second), nowMin = 0)
        assertEquals(1, result.size)
        assertEquals(T0, result.single().startMs)
        assertEquals(T0 + 120 * MIN, result.single().endMs)
        assertEquals(id(1), result.single().itemId)
    }

    @Test
    fun backToBackRepeatIsTwoCells() {
        // The same item again, restarting at its own start: a repeat, not a continuation.
        val result = cells(window(program(0, 60, 1), program(60, 120, 1)), nowMin = 0)
        assertEquals(2, result.size)
    }

    @Test
    fun shortCellsAreCompact() {
        val result = cells(window(program(0, 9, 1), program(9, 19, 2), program(19, 60, 3)), nowMin = 0)
        assertEquals(listOf(true, false, false), result.map { it.compact })
    }

    @Test
    fun slateIsASlateCell() {
        val result = cells(window(slate(0, 30), program(30, 60, 2)), nowMin = 0)
        assertEquals(CellKind.Slate, result[0].kind)
        assertNull(result[0].title)
        assertNull(result[0].itemId)
        assertNull(result[0].info)
        assertEquals(CellKind.Program, result[1].kind)
        assertEquals("Test Movie 2", result[1].title)
        assertNotNull(result[1].info)
    }
}
