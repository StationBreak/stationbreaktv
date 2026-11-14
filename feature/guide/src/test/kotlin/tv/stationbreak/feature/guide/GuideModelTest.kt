// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelRow
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.episodeInfo
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.id
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.stationBreak
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.ChannelId
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
    fun continuingBlocksAreOneCell() {
        val first = program(0, 60, 1, mediaStartMs = 0)
        val second = program(60, 120, 1, mediaStartMs = 60 * MIN)
        val result = cells(window(first, second), nowMin = 0)
        assertEquals(1, result.size)
        assertEquals(T0, result.single().startMs)
        assertEquals(T0 + 120 * MIN, result.single().endMs)
        assertEquals(id(1), result.single().itemId)
    }

    @Test
    fun sameItemNotBackToBackIsTwoCells() {
        // The offset continues, but a gap separates the blocks.
        val first = program(0, 60, 1, mediaStartMs = 0)
        val second = program(70, 120, 1, mediaStartMs = 60 * MIN)
        assertEquals(2, cells(window(first, second), nowMin = 0).size)
    }

    @Test
    fun episodeCellTitleIsTheSeriesName() {
        val episode = program(0, 30, 3, info = episodeInfo(3, "Test Series", 1, 2))
        assertEquals("Test Series", cells(window(episode), nowMin = 0).single().title)
    }

    @Test
    fun backToBackRepeatIsTwoCells() {
        // The same item again, restarting at its own start: a repeat, not a continuation.
        val result = cells(window(program(0, 60, 1), program(60, 120, 1)), nowMin = 0)
        assertEquals(2, result.size)
    }

    @Test
    fun aBreakBlockBecomesAnUnmergedBreakCell() {
        // A programme split around a break, and two breaks back to back: each break is its own cell, and the
        // programme's halves are not joined across it.
        val result =
            cells(
                window(
                    program(0, 28, 1, mediaStartMs = 0),
                    stationBreak(28, 30),
                    stationBreak(30, 31),
                    program(31, 60, 1, mediaStartMs = 28 * MIN),
                ),
                nowMin = 0,
            )
        assertEquals(
            listOf(CellKind.Program, CellKind.Break, CellKind.Break, CellKind.Program),
            result.map { it.kind },
        )
        assertEquals(
            listOf(T0 + 28 * MIN, T0 + 30 * MIN),
            result.filter { it.kind == CellKind.Break }.map { it.startMs },
        )
        val brk = result[1]
        assertEquals(T0 + 30 * MIN, brk.endMs)
        assertNull(brk.title)
        assertNull(brk.itemId)
        assertNull(brk.info)
    }

    @Test
    fun aBreakIsUpNextToTheProgramPastTheBreaksAfterIt() {
        val result =
            cells(
                window(
                    program(0, 28, 1),
                    stationBreak(28, 29),
                    stationBreak(29, 31),
                    program(31, 60, 2),
                    stationBreak(60, 61),
                    slate(61, 90),
                ),
                nowMin = 0,
            )
        val breaks = result.filter { it.kind == CellKind.Break }
        assertEquals(listOf(T0 + 31 * MIN, T0 + 31 * MIN, null), breaks.map { it.upNextMs })
        assertNull("only a break has one", result.first().upNextMs)
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

    @Test
    fun goneProgram_isUnavailableCell() {
        val cell = cells(window(program(0, 30, 1, info = null)), nowMin = 0)[0]
        assertEquals(CellKind.Unavailable, cell.kind)
        assertEquals(id(1), cell.itemId)
        assertNull(cell.title)
        assertNull(cell.info)
    }

    private fun channelRow(
        id: String,
        number: Int,
        name: String,
        recipeKey: String? = null,
    ) = ChannelRow(
        ChannelAiring(ChannelId(id), number, name, Instant.fromEpochMilliseconds(T0), null, recipeKey = recipeKey),
        listOf(program(0, 120, 1)),
        T0 + 48 * 60 * MIN,
    )

    @Test
    fun aStarterRowCarriesItsMarkKey() {
        val window = ScheduleWindow(listOf(channelRow("ch-a", 5, "Little Farm", "little-farm")), true)
        assertEquals("little-farm", buildGuideModel(window, T0).rows.single().markKey)
    }

    @Test
    fun aCustomRowHasNoMarkKey() {
        val window = ScheduleWindow(listOf(channelRow("ch-a", 620, "Mine")), true)
        assertNull(buildGuideModel(window, T0).rows.single().markKey)
    }

    @Test
    fun initialsAreAssignedAcrossAllRowsOfTheModel() {
        // Two channels in one band whose names would both start "CM": alone each gets "CM"; together one differs.
        val rows = listOf(channelRow("ch-a", 620, "Cool Movies"), channelRow("ch-b", 621, "Cozy Mysteries"))
        val together = buildGuideModel(ScheduleWindow(rows, true), T0).rows.map { it.initials }
        val alone = rows.map { buildGuideModel(ScheduleWindow(listOf(it), true), T0).rows.single().initials }
        assertEquals(listOf("CM", "CM"), alone)
        assertNotEquals(together[0], together[1])
    }
}
