// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelRow
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.MIN
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.T0
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.program
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.slate
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.feature.guide.GuideFocus
import tv.stationbreak.feature.guide.GuideSnapshot
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.buildGuideModel
import kotlin.time.Instant

/** The hint row says what OK does on the focused cell, and OK does exactly that (spec 17, ruling R27). */
class HintKindTest {
    private fun min(m: Long) = T0 + m * MIN

    // Channel 9: a program, a slate from 150 to 210, then a program.
    private fun snapshotAt(
        nowMin: Long,
        focusMin: Long,
    ): GuideSnapshot {
        val blocks = listOf(program(0, 150, 1), slate(150, 210), program(210, 600, 2))
        val row = ChannelRow(ChannelAiring(CH9, 9, "Test Channel 9", Instant.fromEpochMilliseconds(T0), null), blocks, min(600))
        val model = buildGuideModel(ScheduleWindow(listOf(row), complete = true), min(nowMin))
        val ready = GuideState.Ready(model, model.originMs, 0, min(nowMin))
        return GuideSnapshot(ready, GuideFocus(CH9, min(focusMin), min(focusMin)))
    }

    @Test
    fun aProgramOnNowIsWatched() {
        assertEquals(HintKind.Watch, hintKindOf(snapshotAt(nowMin = 65, focusMin = 0)))
    }

    @Test
    fun aLaterProgramIsInfo() {
        assertEquals(HintKind.Info, hintKindOf(snapshotAt(nowMin = 65, focusMin = 210)))
    }

    @Test
    fun aSlateOnNowIsWatchedToo() {
        assertEquals(HintKind.Watch, hintKindOf(snapshotAt(nowMin = 160, focusMin = 150)))
    }

    @Test
    fun aLaterSlateHasOnlyBack() {
        assertEquals(HintKind.BackOnly, hintKindOf(snapshotAt(nowMin = 65, focusMin = 150)))
    }

    @Test
    fun noGridHasOnlyBack() {
        assertEquals(HintKind.BackOnly, hintKindOf(GuideSnapshot.Closed))
    }
}
