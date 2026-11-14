// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.stationBreak
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/** The time 48 dp stands for on a 960 dp screen (780 dp over 120 minutes), as `GuideGeometry` works it out. */
private const val NARROW_BELOW_MS = 443_077L

/** A break narrower than 48 dp is not a focus stop; a wider one is. */
class GuideBreakFocusTest {
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
                    // A 2-minute break (13 dp) and a 25-minute one (162.5 dp).
                    row(
                        CH5,
                        5,
                        program(60, 90, 1),
                        stationBreak(90, 92),
                        program(92, 120, 2),
                        stationBreak(120, 145),
                        program(145, 180, 3),
                    ),
                    // Opens on a 2-minute break and ends on one.
                    row(CH7, 7, stationBreak(60, 62), program(62, 120, 4), stationBreak(120, 122)),
                    row(CH9, 9, program(60, 89, 5), program(89, 180, 6)),
                ),
                complete = true,
            ),
            now,
        )

    private fun at(
        channel: ChannelId,
        startMinute: Long,
        anchorMinute: Long = startMinute,
    ) = GuideFocus(channel, min(startMinute), maxOf(min(anchorMinute), model.originMs))

    private fun GuideFocus.go(direction: GuideDirection) = model.move(this, direction, now, NARROW_BELOW_MS)

    @Test
    fun onlyABreakDrawnNarrowerThan48DpIsNarrow() {
        val cells = model.rows[0].cells
        assertTrue(model.isNarrowBreak(cells[1], NARROW_BELOW_MS))
        assertFalse(model.isNarrowBreak(cells[3], NARROW_BELOW_MS))
        // A programme is never narrow, however short; with no threshold every break is a stop.
        assertFalse(model.isNarrowBreak(cells[0].copy(endMs = cells[0].startMs + MIN), NARROW_BELOW_MS))
        assertFalse(model.isNarrowBreak(cells[1], 0L))
    }

    @Test
    fun leftAndRightPassOverANarrowBreakAndStopOnAWideOne() {
        assertEquals(at(CH5, 92), at(CH5, 60).go(GuideDirection.Right))
        assertEquals(at(CH5, 120), at(CH5, 92).go(GuideDirection.Right))
        assertEquals(at(CH5, 145), at(CH5, 120).go(GuideDirection.Right))
        assertEquals(at(CH5, 120), at(CH5, 145).go(GuideDirection.Left))
        assertEquals(at(CH5, 92), at(CH5, 120).go(GuideDirection.Left))
        assertEquals(at(CH5, 60), at(CH5, 92).go(GuideDirection.Left))
        // Without the threshold the narrow break is a stop, as before.
        assertEquals(at(CH5, 90), model.move(at(CH5, 60), GuideDirection.Right, now))
    }

    @Test
    fun rightBeforeANarrowBreakThatEndsTheRowStays() {
        val last = at(CH7, 62)
        assertEquals(last, last.go(GuideDirection.Right))
    }

    @Test
    fun aRowThatOpensOnANarrowBreakSendsLeftToTheChannel() {
        assertTrue(model.isEarliestCell(at(CH7, 62), NARROW_BELOW_MS))
        assertFalse(model.isEarliestCell(at(CH7, 62)))
        assertFalse(model.isEarliestCell(at(CH5, 92), NARROW_BELOW_MS))
        assertEquals(at(CH7, 62), at(CH7, 62).go(GuideDirection.Left))
    }

    @Test
    fun upOrDownOntoANarrowBreakLandsOnTheProgrammeAfterIt() {
        // From channel 7 at minute 91, Up lands on channel 5's 90-92 break: focus goes on to the programme at 92.
        val up = at(CH7, 62, anchorMinute = 91).go(GuideDirection.Up)
        assertEquals(CH5, up.channel)
        assertEquals(min(92), up.cellStartMs)
        assertEquals(min(91), up.anchorMs)
        // Without the threshold Up lands on the break itself, as before.
        assertEquals(min(90), model.move(at(CH7, 62, anchorMinute = 91), GuideDirection.Up, now).cellStartMs)
    }

    @Test
    fun upOrDownOntoANarrowBreakThatEndsTheRowLandsOnTheProgrammeBeforeIt() {
        assertEquals(min(62), at(CH5, 120, anchorMinute = 121).go(GuideDirection.Down).cellStartMs)
    }

    @Test
    fun upOrDownOntoAWideBreakLandsOnIt() {
        val up = at(CH7, 62, anchorMinute = 130).go(GuideDirection.Up)
        assertEquals(CH5, up.channel)
        assertEquals(min(120), up.cellStartMs)
    }
}
