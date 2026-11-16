// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.ui.GuideColors

/** The channel tile (#803): its number and outline by the row's state, in both themes, and its measures. */
class ChannelTileLookTest {
    private val themes = listOf(GuideColors.Dark, GuideColors.Light)

    @Test
    fun aRestingRowIsASecondaryNumberOverTheQuietLine() {
        for (c in themes) {
            val look = tileLook(rowFocused = false, playing = false, colors = c)
            assertEquals(c.textSecondary, look.number)
            assertEquals(c.line, look.line)
        }
    }

    @Test
    fun theRowThatHoldsTheProgrammeFocusIsAPrimaryNumberOverTheKeyCapStroke() {
        for (c in themes) {
            val look = tileLook(rowFocused = true, playing = false, colors = c)
            assertEquals(c.textPrimary, look.number)
            assertEquals(c.keyCapStroke, look.line)
        }
    }

    @Test
    fun theStrokeIsWhiteAtTwentyEightPercentInDark() {
        assertEquals(0.28f, GuideColors.Dark.keyCapStroke.alpha, 0.005f) // 8-bit alpha: 71/255
        assertEquals(1f, GuideColors.Dark.keyCapStroke.red, 0.001f)
        assertEquals(1f, GuideColors.Dark.keyCapStroke.green, 0.001f)
        assertEquals(1f, GuideColors.Dark.keyCapStroke.blue, 0.001f)
    }

    @Test
    fun thePlayingChannelKeepsAPrimaryNumberAndTheQuietLineUntilItsRowIsFocused() {
        for (c in themes) {
            val look = tileLook(rowFocused = false, playing = true, colors = c)
            assertEquals(c.textPrimary, look.number)
            assertEquals(c.line, look.line)
        }
    }

    @Test
    fun theTileTakesItsGapFromTheColumnSoTheTimeColumnsStayPut() {
        assertEquals(84f, ChannelColumnWidth.value, 0.001f)
        assertEquals(4.dp, ChannelTileGap)
        assertEquals(80f, (ChannelColumnWidth - ChannelTileGap).value, 0.001f)
    }
}
