// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a track picker's left edge goes: its chip's, held inside the safe area (960 dp layout, 5% overscan). */
class PickerLeftTest {
    private val safeLeft = 48.dp
    private val safeRight = 912.dp

    @Test
    fun `a chip well inside keeps the picker on its left edge`() {
        assertEquals(300.dp, pickerLeft(300.dp, safeLeft, safeRight))
    }

    @Test
    fun `a chip near the right end holds the picker's right edge at the safe area`() {
        // 912 - 340 = 572: a chip at 700 would put the picker's right edge at 1040, past the screen.
        assertEquals(572.dp, pickerLeft(700.dp, safeLeft, safeRight))
    }

    @Test
    fun `a chip left of the safe area is brought in to it`() {
        assertEquals(48.dp, pickerLeft(10.dp, safeLeft, safeRight))
    }

    @Test
    fun `a safe area narrower than the picker still starts at its left edge`() {
        assertEquals(48.dp, pickerLeft(200.dp, safeLeft, 300.dp))
    }
}
