// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The libraries screen's own keys. The shared remote policy (Back, Select, repeat) is `RemoteKeyTest`'s (#823). */
class LibrariesKeysTest {
    @Test
    fun upAndDownActOnEveryPress() {
        assertEquals(Pad.Down, padToAct(Key.DirectionDown, KeyEventType.KeyDown, 3))
        assertEquals(Pad.Up, padToAct(Key.DirectionUp, KeyEventType.KeyDown, 0))
        assertNull(padToAct(Key.DirectionUp, KeyEventType.KeyUp, 0))
    }

    @Test
    fun selectAndBackReachTheScreenAsItsOwnPads() {
        assertEquals(Pad.Select, padToAct(Key.Enter, KeyEventType.KeyDown, 0))
        assertEquals(Pad.Back, padToAct(Key.Back, KeyEventType.KeyUp, 0))
        assertNull(padToAct(Key.Back, KeyEventType.KeyDown, 0))
    }

    // Left and Right do nothing here, but are kept, so the focus never leaves the screen.
    @Test
    fun leftAndRightAreKept_butDoNothing() {
        assertNull(padToAct(Key.DirectionLeft, KeyEventType.KeyDown, 0))
        assertNull(padToAct(Key.DirectionRight, KeyEventType.KeyDown, 2))
        assertEquals(true, isScreenKey(Key.DirectionLeft))
        assertEquals(true, isScreenKey(Key.DirectionRight))
    }

    @Test
    fun mediaKeysAreNotTheScreens() {
        assertNull(padToAct(Key.MediaPlayPause, KeyEventType.KeyDown, 0))
        assertEquals(false, isScreenKey(Key.MediaPlayPause))
    }
}
