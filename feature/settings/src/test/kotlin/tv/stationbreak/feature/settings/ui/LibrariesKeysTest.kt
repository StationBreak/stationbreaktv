// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrariesKeysTest {
    @Test
    fun backIsActedOnAtItsRelease() {
        assertEquals(Pad.Back, padToAct(Key.Back, KeyEventType.KeyUp, 0))
        assertNull(padToAct(Key.Back, KeyEventType.KeyDown, 0))
    }

    @Test
    fun selectActsOncePerPress_notOnItsAutoRepeat() {
        assertEquals(Pad.Select, padToAct(Key.DirectionCenter, KeyEventType.KeyDown, 0))
        assertNull(padToAct(Key.DirectionCenter, KeyEventType.KeyDown, 1))
        assertNull(padToAct(Key.DirectionCenter, KeyEventType.KeyUp, 0))
        assertEquals(Pad.Select, padToAct(Key.Enter, KeyEventType.KeyDown, 0))
    }

    @Test
    fun upAndDownActOnEveryPress() {
        assertEquals(Pad.Down, padToAct(Key.DirectionDown, KeyEventType.KeyDown, 3))
        assertEquals(Pad.Up, padToAct(Key.DirectionUp, KeyEventType.KeyDown, 0))
        assertNull(padToAct(Key.DirectionUp, KeyEventType.KeyUp, 0))
    }

    // Left and Right do nothing here, but are kept, so the focus never leaves the screen.
    @Test
    fun leftAndRightAreKept_butDoNothing() {
        assertNull(padToAct(Key.DirectionLeft, KeyEventType.KeyDown, 0))
        assertEquals(true, isScreenKey(Key.DirectionLeft))
        assertEquals(true, isScreenKey(Key.DirectionRight))
    }

    @Test
    fun mediaKeysAreNotTheScreens() {
        assertNull(padToAct(Key.MediaPlayPause, KeyEventType.KeyDown, 0))
        assertEquals(false, isScreenKey(Key.MediaPlayPause))
    }
}
