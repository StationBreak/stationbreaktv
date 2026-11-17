// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The teletype style of the brand's flags (DESIGN.md): Space Mono Bold, tracked 0.13em. */
class TeletypeTest {
    @Test
    fun theFlagIsSpaceMonoBoldTrackedThirteenPercentOfAnEm() {
        val flag = teletype(10.sp)
        assertEquals(BroadcastType.Mono, flag.fontFamily)
        assertEquals(FontWeight.Bold, flag.fontWeight)
        assertEquals(10.sp, flag.fontSize)
        assertEquals(TextUnitType.Em, flag.letterSpacing.type)
        assertEquals(0.13f, flag.letterSpacing.value, 0.001f)
    }
}
