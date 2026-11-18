// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The channel screen's own keys. The shared remote policy (Back, Select, repeat) is `RemoteKeyTest`'s (#823). */
class ChannelsKeyTest {
    // Left and Right move between the sheet's columns and the builder's panels, so holding one keeps moving.
    @Test
    fun leftAndRightActHereOnEveryPress() {
        assertEquals(Pad.Left, padToAct(Key.DirectionLeft, KeyEventType.KeyDown, 0))
        assertEquals(Pad.Right, padToAct(Key.DirectionRight, KeyEventType.KeyDown, 4))
        assertNull(padToAct(Key.DirectionRight, KeyEventType.KeyUp, 0))
    }

    @Test
    fun theScreenFollowsTheSharedPolicy() {
        assertEquals(Pad.Back, padToAct(Key.Back, KeyEventType.KeyUp, 0))
        assertEquals(Pad.Select, padToAct(Key.DirectionCenter, KeyEventType.KeyDown, 0))
        assertNull(padToAct(Key.DirectionCenter, KeyEventType.KeyDown, 1))
        assertEquals(Pad.Down, padToAct(Key.DirectionDown, KeyEventType.KeyDown, 5))
        assertNull(padToAct(Key.MediaPlayPause, KeyEventType.KeyDown, 0))
    }
}
