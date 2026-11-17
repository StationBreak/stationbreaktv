// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The teletype style of the guide's flags and clock (DESIGN.md): Space Mono Bold, tracked 0.13em. */
class GuideTeletypeTest {
    @Test
    fun theFlagIsSpaceMonoBoldTrackedThirteenPercent() {
        val flag = guideTeletype(10.sp)
        assertEquals(BroadcastType.Mono, flag.fontFamily)
        assertEquals(FontWeight.Bold, flag.fontWeight)
        assertEquals(10.sp, flag.fontSize)
        assertEquals(1.3f, flag.letterSpacing.value, 0.001f)
    }

    @Test
    fun theClockIsTheSameStyleAtElevenSp() {
        val clock = guideTeletype(11.sp)
        assertEquals(11.sp, clock.fontSize)
        assertEquals(1.43f, clock.letterSpacing.value, 0.001f)
    }
}
