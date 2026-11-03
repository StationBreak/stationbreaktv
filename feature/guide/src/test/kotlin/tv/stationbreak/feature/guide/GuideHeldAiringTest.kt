// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.Airing
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelRow
import tv.stationbreak.core.domain.lineup.HeldAiring
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.id
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/**
 * #556: after an edit the picture keeps the airing item (spec 22, ruling 16) while the schedule lays the day out
 * anew. The guide's now cell for the playing channel shows what is playing, until it ends; the cells after it are the
 * new schedule's, from the held item's end.
 */
class GuideHeldAiringTest : GuideRig() {
    private fun row(
        channel: ChannelId,
        vararg blocks: AiringBlock,
    ) = ChannelRow(
        ChannelAiring(channel, 5, "Test Channel", Instant.fromEpochMilliseconds(T0), null),
        blocks.toList(),
        T0 + 48 * 60 * MIN,
    )

    private fun GuideViewModel.cellsOf(channel: ChannelId) =
        ready()
            .model.rows
            .first { it.channel == channel }
            .cells

    @Test
    fun theNowCellOfThePlayingChannelIsTheHeldItemAndTheNextStartsAtItsEnd() =
        guideTest { vm ->
            // The new rules say item 102 is on from 60 to 90; the picture plays item 77, from 40 to 100.
            vm.open(CH7, HeldAiring(CH7, program(40, 100, 77)))
            runCurrent()

            val cells = vm.cellsOf(CH7)
            assertEquals(id(77), cells.first().itemId)
            assertEquals(min(40), cells.first().startMs)
            assertEquals(min(100), cells.first().endMs)
            assertEquals(id(103), cells[1].itemId)
            assertEquals(min(100), cells[1].startMs)
            assertEquals(min(120), cells[1].endMs)
            assertEquals(min(120), cells[2].startMs)
        }

    @Test
    fun otherChannelsAreUnchangedByAHeldItem() =
        guideTest { vm ->
            vm.open(CH7, HeldAiring(CH7, program(40, 100, 77)))
            runCurrent()

            val other = vm.cellsOf(CH5)
            assertEquals(id(201), other.first().itemId)
            assertEquals(min(60), other.first().startMs)
        }

    @Test
    fun withNothingHeldTheScheduleIsShownAsItIs() =
        guideTest { vm ->
            vm.open(CH7, null)
            runCurrent()

            assertEquals(id(102), vm.cellsOf(CH7).first().itemId)
        }

    @Test
    fun blocksTheHeldItemCoversAreDroppedAndAnOverlappingOneIsTrimmedOnBothSides() {
        val window =
            ScheduleWindow(
                listOf(row(CH7, program(0, 50, 1), program(50, 70, 2), program(70, 90, 3), program(90, 150, 4))),
                complete = true,
            )

        val blocks =
            window
                .holding(HeldAiring(CH7, program(40, 100, 77)))
                .rows
                .single()
                .blocks

        assertEquals(listOf(T0, T0 + 40 * MIN, T0 + 100 * MIN), blocks.map { it.startMs })
        assertEquals(listOf(T0 + 40 * MIN, T0 + 100 * MIN, T0 + 150 * MIN), blocks.map { it.endMs })
    }

    @Test
    fun aTrimmedProgramJoinsWhereItsOwnTimelineHasReached() {
        val window = ScheduleWindow(listOf(row(CH7, program(90, 150, 4))), complete = true)

        val trimmed =
            window
                .holding(HeldAiring(CH7, program(40, 100, 77)))
                .rows
                .single()
                .blocks
                .last()

        assertEquals(T0 + 100 * MIN, trimmed.startMs)
        assertEquals(10 * MIN, (trimmed.airing as Airing.Program).mediaStartMs)
    }

    @Test
    fun aSlateAfterTheHeldItemIsTrimmedToo() {
        val window = ScheduleWindow(listOf(row(CH7, slate(60, 120))), complete = true)

        val blocks =
            window
                .holding(HeldAiring(CH7, program(40, 100, 77)))
                .rows
                .single()
                .blocks

        assertEquals(listOf(T0 + 40 * MIN, T0 + 100 * MIN), blocks.map { it.startMs })
    }
}
