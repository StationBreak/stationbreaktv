// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun theStrokeIsTheBrandGreyInDark() {
        assertEquals(
            androidx.compose.ui.graphics
                .Color(0xFFCFCBC2),
            GuideColors.Dark.keyCapStroke,
        )
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
    fun onlyThePlayingChannelHasAMarker_andItIsNotTheAccent() {
        for (c in themes) {
            assertEquals(null, tileLook(rowFocused = false, playing = false, colors = c).marker)
            assertEquals(null, tileLook(rowFocused = true, playing = false, colors = c).marker)
            val playing = tileLook(rowFocused = false, playing = true, colors = c)
            assertEquals(c.textPrimary, playing.marker)
            assertNotEquals(c.accent, playing.marker)
            // Told from a focused row that is not playing: same number, but only the playing one has the bar.
            assertEquals(tileLook(rowFocused = true, playing = false, colors = c).number, playing.number)
            assertNotEquals(tileLook(rowFocused = true, playing = false, colors = c).marker, playing.marker)
        }
    }

    @Test
    fun theTileTakesItsGapFromTheColumnSoTheTimeColumnsStayPut() {
        assertEquals(84f, ChannelColumnWidth.value, 0.001f)
        assertEquals(4.dp, ChannelTileGap)
        assertEquals(80f, (ChannelColumnWidth - ChannelTileGap).value, 0.001f)
    }
}
