// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The faces of [BroadcastType]: the brand face (Ranchers, one regular weight) is its own family. */
class BroadcastTypeTest {
    @Test
    fun theVoiceIsRanchersRegularAndNoOtherFace() {
        val fonts = (BroadcastType.Voice as FontListFontFamily).fonts
        assertEquals(1, fonts.size)
        assertEquals(FontWeight.Normal, fonts.single().weight)
        assertNotEquals(BroadcastType.Display, BroadcastType.Voice)
        assertNotEquals(BroadcastType.Mono, BroadcastType.Voice)
    }
}
