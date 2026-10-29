// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelRow
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH5
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// The moves these tests make are at the origin's moment unless one says otherwise: the anchor's floor is the origin.
private fun GuideModel.move(
    focus: GuideFocus,
    direction: GuideDirection,
) = move(focus, direction, originMs)

class GuideFocusTest {
    private fun row(
        id: ChannelId,
        number: Int,
        vararg blocks: AiringBlock,
    ) = ChannelRow(
        ChannelAiring(id, number, "Test Channel $number", Instant.fromEpochMilliseconds(T0), null),
        blocks.toList(),
        T0 + 48 * 60 * MIN,
    )

    private fun min(m: Long) = T0 + m * MIN

    // now = minute 65, so the origin is minute 60.
    private val now = min(65)

    private val model =
        buildGuideModel(
            ScheduleWindow(
                listOf(
                    row(CH5, 5, program(30, 90, 1), program(90, 120, 2), program(120, 180, 3)),
                    row(CH7, 7, program(60, 100, 4), slate(100, 130), program(130, 180, 5)),
                    row(CH9, 9, program(60, 75, 6), program(75, 84, 7)),
                ),
                complete = true,
            ),
            now,
        )

    private fun focusAt(
        row: Int,
        cell: Int,
    ) = model.rows[row].let {
        GuideFocus(it.channel, it.cells[cell].startMs, maxOf(it.cells[cell].startMs, model.originMs))
    }

    @Test
    fun initialFocusIsCurrentChannelNowCell() {
        val focus = checkNotNull(model.initialFocus(CH7, now))
        assertEquals(CH7, focus.channel)
        assertEquals(min(60), focus.cellStartMs)
        assertEquals(1 to 0, model.indexOf(focus))
        // Channel 5's cell holding "now" started before the origin, and is still the one focused.
        val first = checkNotNull(model.initialFocus(CH5, now))
        assertEquals(min(30), first.cellStartMs)
        assertEquals(min(60), first.anchorMs)
    }

    @Test
    fun unknownCurrentChannelFocusesFirstRow() {
        assertEquals(CH5, model.initialFocus(null, now)?.channel)
        assertEquals(CH5, model.initialFocus(ChannelId("gone"), now)?.channel)
        assertEquals(0 to 0, model.indexOf(checkNotNull(model.initialFocus(null, now))))
    }

    @Test
    fun indexOfIsNullWhenTheCellIsGone() {
        assertNull(model.indexOf(GuideFocus(CH5, min(7), min(7))))
        assertNull(model.indexOf(GuideFocus(ChannelId("gone"), min(60), min(60))))
    }

    @Test
    fun upDownKeepsAnchorTime() {
        val start = GuideFocus(CH5, min(90), min(110))
        val down = model.move(start, GuideDirection.Down)
        assertEquals(CH7, down.channel)
        assertEquals(min(100), down.cellStartMs) // the slate holds minute 110
        assertEquals(min(110), down.anchorMs)
        val back = model.move(down, GuideDirection.Up)
        assertEquals(CH5, back.channel)
        assertEquals(min(90), back.cellStartMs)
        assertEquals(min(110), back.anchorMs)
    }

    @Test
    fun upDownNeverKeepsAnAnchorEarlierThanNow() {
        // Channel 7's cell on now was focused at 60 and kept that anchor while the clock went on to 80. Channel 9's
        // program from 60 ended at 75 and is still on the grid: Down lands on what is on now, 75 to 84.
        val down = model.move(GuideFocus(CH7, min(60), min(60)), GuideDirection.Down, min(80))
        assertEquals(CH9, down.channel)
        assertEquals(min(75), down.cellStartMs)
        assertEquals(min(80), down.anchorMs)
        // A later anchor is kept as it is.
        assertEquals(min(150), model.move(GuideFocus(CH7, min(130), min(150)), GuideDirection.Down, min(80)).anchorMs)
    }

    @Test
    fun theFocusThatFollowsNowIsAnchoredAtNow() {
        // The tick at 80 (late: the end at 75 was not on the visible range) moves the focus off the program that ended
        // to the one on now, which began at 75: its anchor is now, not that start.
        val focus = model.followingNow(GuideFocus(CH9, min(60), min(60)), previousNowMs = min(65), nowMs = min(80))
        assertEquals(GuideFocus(CH9, min(75), min(80)), focus)
    }

    @Test
    fun aFocusReplacedByARebuildIsAnchoredNoEarlierThanNow() {
        // The focused cell (from 50) is not in the rebuilt grid; its row's cell holding now is focused instead.
        val focus = model.focusAfterRebuild(GuideFocus(CH9, min(50), min(60)), CH9, min(80))
        assertEquals(GuideFocus(CH9, min(75), min(80)), focus)
    }

    @Test
    fun upDownOntoSlateLandsOnSlate() {
        val focus = model.move(GuideFocus(CH5, min(120), min(125)), GuideDirection.Down)
        assertEquals(min(100), focus.cellStartMs)
        assertEquals(CellKind.Slate, model.rows[1].cells[model.indexOf(focus)!!.second].kind)
    }

    @Test
    fun upDownOntoShorterRowLandsOnItsLastCell() {
        // Row 9 ends at minute 84; anchor 150 is past it.
        val focus = model.move(GuideFocus(CH7, min(130), min(150)), GuideDirection.Down)
        assertEquals(CH9, focus.channel)
        assertEquals(min(75), focus.cellStartMs)
        assertEquals(min(150), focus.anchorMs)
    }

    @Test
    fun upDownClampsAnchorToOrigin() {
        // Row 5's first cell starts at 30, before the origin (60); an anchor of 30 is clamped up to it.
        val down = model.move(GuideFocus(CH5, min(30), min(30)), GuideDirection.Down)
        assertEquals(min(60), down.anchorMs)
        assertEquals(min(60), down.cellStartMs)
    }

    @Test
    fun leftRightVisitsEveryCellIncludingCompactAndSlates() {
        var focus = focusAt(1, 0)
        val visited = mutableListOf(focus.cellStartMs)
        repeat(2) {
            focus = model.move(focus, GuideDirection.Right)
            visited += focus.cellStartMs
        }
        assertEquals(listOf(min(60), min(100), min(130)), visited)
        assertEquals(min(130), focus.anchorMs)
        // Row 9 holds a 15 and a 9 minute cell; both are visited.
        val second = model.move(focusAt(2, 0), GuideDirection.Right)
        assertEquals(min(75), second.cellStartMs)
        assertEquals(min(60), model.move(second, GuideDirection.Left).cellStartMs)
    }

    @Test
    fun leftStopsAtOrigin() {
        // The first cell of row 5 starts before the origin and is the leftmost cell: Left stays.
        val first = focusAt(0, 0)
        assertEquals(min(60), first.anchorMs)
        assertEquals(first, model.move(first, GuideDirection.Left))
        // Right then Left returns to it, the anchor clamped to the origin.
        assertEquals(first, model.move(model.move(first, GuideDirection.Right), GuideDirection.Left))
    }

    @Test
    fun upAndDownWrapRoundTheRowsButLeftAndRightStopAtTheEdges() {
        val top = focusAt(0, 1)
        assertEquals(CH9, model.move(top, GuideDirection.Up).channel)
        val bottom = focusAt(2, 1)
        assertEquals(CH5, model.move(bottom, GuideDirection.Down).channel)
        assertTrue(model.wrapped(bottom, model.move(bottom, GuideDirection.Down), GuideDirection.Down))
        assertTrue(model.wrapped(top, model.move(top, GuideDirection.Up), GuideDirection.Up))
        assertFalse(model.wrapped(top, model.move(top, GuideDirection.Down), GuideDirection.Down))
        val last = focusAt(0, 2)
        assertEquals(last, model.move(last, GuideDirection.Right))
    }

    @Test
    fun viewportShiftsBySlotAndNeverBeforeOrigin() {
        val origin = model.originMs
        val right = GuideDirection.Right
        // Visible already (the viewport covers 60..150): no change.
        assertEquals(origin, viewportStartFor(focusAt(0, 0), model, origin, right, 90.minutes))
        assertEquals(origin, viewportStartFor(focusAt(1, 2), model, origin, right, 90.minutes))
        // Left onto a cell entirely before the viewport: the viewport goes back to the cell's slot.
        val left = GuideDirection.Left
        assertEquals(min(120), viewportStartFor(GuideFocus(CH5, min(120), min(120)), model, min(180), left, 90.minutes))
        assertEquals(min(90), viewportStartFor(GuideFocus(CH5, min(100), min(100)), model, min(180), left, 90.minutes))
        // A cell before the origin never pulls the viewport before it.
        assertEquals(origin, viewportStartFor(focusAt(0, 0), model, min(90), left, 90.minutes))
    }

    @Test
    fun rightOntoACellBeyondTheEdgeSnapsToItsSlot() {
        val wide =
            buildGuideModel(
                ScheduleWindow(listOf(row(CH5, 5, program(60, 170, 1), program(170, 200, 2))), complete = true),
                now,
            )
        val onto = wide.move(checkNotNull(wide.initialFocus(CH5, now)), GuideDirection.Right)
        assertEquals(min(170), onto.cellStartMs)
        // The viewport covers 60..150: the new cell is entirely outside, so it snaps to the 150 slot.
        assertEquals(min(150), viewportStartFor(onto, wide, min(60), GuideDirection.Right))
        // Partly visible: unchanged.
        assertEquals(min(120), viewportStartFor(onto, wide, min(120), GuideDirection.Right))
    }

    @Test
    fun downAcrossALongCellKeepsTheViewport() {
        val long =
            buildGuideModel(
                ScheduleWindow(
                    listOf(
                        row(CH5, 5, program(60, 180, 1), program(180, 270, 2)),
                        row(CH7, 7, program(60, 300, 4)),
                    ),
                    complete = true,
                ),
                now,
            )
        val from = GuideFocus(CH5, min(180), min(180))
        val down = long.move(from, GuideDirection.Down)
        assertEquals(min(60), down.cellStartMs) // the 4 hour cell began at the origin
        assertEquals(min(180), viewportStartFor(down, long, min(180), GuideDirection.Down))
    }

    @Test
    fun upDownKeepsTimeColumnsSteady() {
        val rows =
            buildGuideModel(
                ScheduleWindow(
                    listOf(
                        row(CH5, 5, program(60, 130, 1), program(130, 250, 2)),
                        row(CH7, 7, program(60, 240, 4)),
                        row(CH9, 9, program(60, 100, 6), program(100, 150, 7), program(150, 260, 8)),
                    ),
                    complete = true,
                ),
                now,
            )
        var focus = GuideFocus(CH5, min(130), min(135))
        repeat(2) {
            focus = rows.move(focus, GuideDirection.Down)
            assertEquals(min(120), viewportStartFor(focus, rows, min(120), GuideDirection.Down))
        }
        repeat(2) {
            focus = rows.move(focus, GuideDirection.Up)
            assertEquals(min(120), viewportStartFor(focus, rows, min(120), GuideDirection.Up))
        }
    }

    @Test
    fun upDownOntoACellOutsideTheViewportRevealsTheAnchorWhenTheCellHoldsIt() {
        val long =
            buildGuideModel(
                ScheduleWindow(
                    listOf(
                        row(CH5, 5, program(60, 100, 1), program(100, 500, 2)),
                        row(CH7, 7, program(60, 350, 4), program(350, 500, 5)),
                    ),
                    complete = true,
                ),
                now,
            )
        val down = long.move(GuideFocus(CH5, min(100), min(400)), GuideDirection.Down)
        assertEquals(min(350), down.cellStartMs)
        // The viewport is far from the anchor (400); the cell holds it, so it is shown in whole slots: 330..420.
        assertEquals(min(330), viewportStartFor(down, long, min(60), GuideDirection.Down))
    }

    @Test
    fun upDownOntoAFallbackCellScrollsToTheCellNotTheAnchor() {
        val short =
            buildGuideModel(
                ScheduleWindow(
                    listOf(row(CH5, 5, program(60, 500, 1)), row(CH7, 7, program(60, 100, 4))),
                    complete = true,
                ),
                now,
            )
        val down = short.move(GuideFocus(CH5, min(60), min(400)), GuideDirection.Down)
        assertEquals(min(60), down.cellStartMs)
        assertEquals(min(400), down.anchorMs)
        val viewport = viewportStartFor(down, short, min(330), GuideDirection.Down)
        // The short cell (60..100) ends before the viewport: the fewest slots back to show its last moment.
        assertEquals(min(90), viewport)
        assertOverlaps(short, down, viewport)
    }

    private fun assertOverlaps(
        m: GuideModel,
        focus: GuideFocus,
        viewport: Long,
        span: Long = 90 * MIN,
    ) {
        val (r, c) = checkNotNull(m.indexOf(focus))
        val cell = m.rows[r].cells[c]
        assertTrue("$cell not in $viewport", cell.endMs > viewport && cell.startMs < viewport + span)
        assertTrue(viewport >= m.originMs)
    }

    @Test
    fun focusedCellAlwaysOverlapsTheViewportAfterAnyMove() {
        val shapes =
            buildGuideModel(
                ScheduleWindow(
                    listOf(
                        row(CH5, 5, program(30, 100, 1), program(100, 110, 2), program(110, 400, 3)),
                        row(CH7, 7),
                        row(CH9, 9, program(60, 75, 6), program(75, 150, 7)),
                        row(ChannelId("ch-4"), 4, program(60, 360, 8)),
                        row(ChannelId("ch-3"), 3, slate(60, 90), program(90, 95, 9), program(95, 400, 10)),
                    ),
                    complete = true,
                ),
                now,
            )
        val anchors = (60L..390L step 15L).map(::min)
        val viewports = (60L..360L step 30L).map(::min)
        val moves =
            shapes.rows.flatMap { row ->
                row.cells.flatMap { cell ->
                    anchors.flatMap { anchor ->
                        GuideDirection.entries.map { direction ->
                            direction to shapes.move(GuideFocus(row.channel, cell.startMs, anchor), direction)
                        }
                    }
                }
            }
        for ((direction, moved) in moves) {
            for (viewport in viewports) {
                assertOverlaps(shapes, moved, viewportStartFor(moved, shapes, viewport, direction))
            }
        }
        assertTrue(moves.size * viewports.size > 1000)
    }

    @Test
    fun emptyModelHasNoFocus() {
        val empty = buildGuideModel(ScheduleWindow(emptyList(), complete = true), now)
        assertNull(empty.initialFocus(CH5, now))
        val noCells = buildGuideModel(ScheduleWindow(listOf(row(CH5, 5)), complete = true), now)
        assertNull(noCells.initialFocus(CH5, now))
    }

    @Test
    fun currentChannelWithNoCellsFallsBackToFirstRowWithCells() {
        val m =
            buildGuideModel(
                ScheduleWindow(
                    listOf(row(CH5, 5), row(CH7, 7, program(60, 100, 4)), row(CH9, 9, program(60, 90, 6))),
                    complete = true,
                ),
                now,
            )
        assertEquals(CH7, m.initialFocus(CH5, now)?.channel)
        assertEquals(CH9, m.initialFocus(CH9, now)?.channel)
    }

    @Test
    fun upDownSkipsRowsWithNoCells() {
        val m =
            buildGuideModel(
                ScheduleWindow(
                    listOf(row(CH5, 5, program(60, 100, 1)), row(CH7, 7), row(CH9, 9, program(60, 90, 6))),
                    complete = true,
                ),
                now,
            )
        val top = GuideFocus(CH5, min(60), min(60))
        val down = m.move(top, GuideDirection.Down)
        assertEquals(CH9, down.channel)
        assertEquals(top, m.move(down, GuideDirection.Up))
        // Nothing with cells beyond: Down wraps to the first row that has any, skipping the empty one.
        assertEquals(top, m.move(down, GuideDirection.Down))
    }

    @Test
    fun firstVisibleRowFollowsFocusAtEdges() {
        assertEquals(0, firstVisibleRowFor(2, 0, 5, 20))
        assertEquals(1, firstVisibleRowFor(5, 0, 5, 20))
        assertEquals(3, firstVisibleRowFor(3, 4, 5, 20))
        assertEquals(4, firstVisibleRowFor(6, 4, 5, 20))
        assertEquals(0, firstVisibleRowFor(1, 0, 5, 3))
        assertEquals(15, firstVisibleRowFor(19, 15, 5, 20))
    }

    @Test
    fun firstVisibleRowNeedsAtLeastOneVisibleRow() {
        assertThrows(IllegalArgumentException::class.java) { firstVisibleRowFor(0, 0, 0, 5) }
    }
}
