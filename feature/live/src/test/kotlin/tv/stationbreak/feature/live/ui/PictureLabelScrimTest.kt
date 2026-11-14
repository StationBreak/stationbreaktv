// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The picture label's scrim: 88% for the top 24 dp of its 32, then a fade over the last 8 dp. */
class PictureLabelScrimTest {
    @Test
    fun theScrimHolds88PercentFor24DpThenFadesOverTheLast8() {
        assertEquals(32f, PictureLabelHeight.value, 0f)
        assertEquals(24f, ScrimSolidHeight.value, 0f)
        val top = Color.Black.copy(alpha = 0.88f)
        val stops = pictureLabelScrimStops(top)
        assertEquals(listOf(0f, 0.75f, 1f), stops.map { it.first })
        // Alpha is stored to about 1/255, so compare within that.
        listOf(0.88f, 0.88f, 0f).zip(stops.map { it.second.alpha }).forEach { (want, got) ->
            assertEquals(want, got, ALPHA_STEP)
        }
        assertEquals(8.dp, PictureLabelHeight - ScrimSolidHeight)
    }
}

private const val ALPHA_STEP = 1f / 255f
