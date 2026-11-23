// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.UP_DOWN
import tv.stationbreak.core.ui.padToAct

/** The libraries screen's own keys. The shared remote policy (Back, Select, repeat) is `RemoteKeyTest`'s (#823). */
class LibrariesKeysTest {
    @Test
    fun upAndDownActOnEveryPress() {
        assertEquals(RemoteKey.Down, padToAct(Key.DirectionDown, KeyEventType.KeyDown, 3, UP_DOWN))
        assertEquals(RemoteKey.Up, padToAct(Key.DirectionUp, KeyEventType.KeyDown, 0, UP_DOWN))
        assertNull(padToAct(Key.DirectionUp, KeyEventType.KeyUp, 0, UP_DOWN))
    }

    @Test
    fun selectAndBackReachTheScreenAsItsOwnPads() {
        assertEquals(RemoteKey.Select, padToAct(Key.Enter, KeyEventType.KeyDown, 0, UP_DOWN))
        assertEquals(RemoteKey.Back, padToAct(Key.Back, KeyEventType.KeyUp, 0, UP_DOWN))
        assertNull(padToAct(Key.Back, KeyEventType.KeyDown, 0, UP_DOWN))
    }

    // Left and Right do nothing here, but are kept, so the focus never leaves the screen.
    @Test
    fun leftAndRightAreKept_butDoNothing() {
        assertNull(padToAct(Key.DirectionLeft, KeyEventType.KeyDown, 0, UP_DOWN))
        assertNull(padToAct(Key.DirectionRight, KeyEventType.KeyDown, 2, UP_DOWN))
        assertEquals(true, isScreenKey(Key.DirectionLeft))
        assertEquals(true, isScreenKey(Key.DirectionRight))
    }

    @Test
    fun mediaKeysAreNotTheScreens() {
        assertNull(padToAct(Key.MediaPlayPause, KeyEventType.KeyDown, 0, UP_DOWN))
        assertEquals(false, isScreenKey(Key.MediaPlayPause))
    }
}
