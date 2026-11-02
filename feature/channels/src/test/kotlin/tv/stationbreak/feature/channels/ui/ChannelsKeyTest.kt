// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelsKeyTest {
    @Test
    fun backIsActedOnAtItsReleaseEvenWithoutItsPress() {
        assertEquals(Pad.Back, padToAct(Key.Back, KeyEventType.KeyUp, 0))
        assertNull(padToAct(Key.Back, KeyEventType.KeyDown, 0))
    }

    @Test
    fun selectActsOncePerPressNotOnItsAutoRepeat() {
        assertEquals(Pad.Select, padToAct(Key.DirectionCenter, KeyEventType.KeyDown, 0))
        assertNull(padToAct(Key.DirectionCenter, KeyEventType.KeyDown, 1))
        assertNull(padToAct(Key.DirectionCenter, KeyEventType.KeyUp, 0))
        assertEquals(Pad.Select, padToAct(Key.Enter, KeyEventType.KeyDown, 0))
    }

    @Test
    fun theDPadActsOnEveryPressSoHoldingScrolls() {
        assertEquals(Pad.Down, padToAct(Key.DirectionDown, KeyEventType.KeyDown, 0))
        assertEquals(Pad.Down, padToAct(Key.DirectionDown, KeyEventType.KeyDown, 5))
        assertNull(padToAct(Key.DirectionDown, KeyEventType.KeyUp, 0))
    }

    @Test
    fun otherKeysAreNotTheScreens() {
        assertNull(padToAct(Key.MediaPlayPause, KeyEventType.KeyDown, 0))
    }
}
