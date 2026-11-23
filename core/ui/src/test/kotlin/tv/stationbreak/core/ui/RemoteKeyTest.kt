// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The remote policy every TV screen shares (#823). Each screen's own test covers only what is its own. */
class RemoteKeyTest {
    @Test
    fun backIsActedOnAtItsReleaseEvenWithoutItsPress() {
        assertEquals(RemoteKey.Back, remoteKeyToAct(Key.Back, KeyEventType.KeyUp, 0))
        assertNull(remoteKeyToAct(Key.Back, KeyEventType.KeyDown, 0))
        assertNull(remoteKeyToAct(Key.Back, KeyEventType.KeyDown, 1))
    }

    @Test
    fun selectActsOncePerPressNotOnItsAutoRepeatOrRelease() {
        for (key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
            assertEquals(RemoteKey.Select, remoteKeyToAct(key, KeyEventType.KeyDown, 0))
            assertNull(remoteKeyToAct(key, KeyEventType.KeyDown, 1))
            assertNull(remoteKeyToAct(key, KeyEventType.KeyUp, 0))
        }
    }

    @Test
    fun aDirectionActsOnEveryPressSoHoldingScrolls() {
        for ((key, pad) in DIRECTIONS) {
            assertEquals(pad, remoteKeyToAct(key, KeyEventType.KeyDown, 0))
            assertEquals(pad, remoteKeyToAct(key, KeyEventType.KeyDown, 5))
            assertNull(remoteKeyToAct(key, KeyEventType.KeyUp, 0))
        }
    }

    @Test
    fun aDirectionThatIsNotOneOfTheScreensPadsIsKeptButDoesNothing() {
        val upDown = setOf(RemoteKey.Up, RemoteKey.Down)
        assertNull(remoteKeyToAct(Key.DirectionLeft, KeyEventType.KeyDown, 0, upDown))
        assertNull(remoteKeyToAct(Key.DirectionRight, KeyEventType.KeyDown, 3, upDown))
        assertEquals(RemoteKey.Up, remoteKeyToAct(Key.DirectionUp, KeyEventType.KeyDown, 0, upDown))
        // Select and Back are never limited by the pads.
        assertEquals(RemoteKey.Select, remoteKeyToAct(Key.Enter, KeyEventType.KeyDown, 0, upDown))
        assertEquals(RemoteKey.Back, remoteKeyToAct(Key.Back, KeyEventType.KeyUp, 0, emptySet()))
    }

    @Test
    fun theMediaKeysAndMenuAreNotAScreensKeys() {
        for (key in listOf(Key.MediaPlayPause, Key.MediaFastForward, Key.Menu, Key.ChannelUp)) {
            assertNull(remoteKeyOf(key))
            assertNull(remoteKeyToAct(key, KeyEventType.KeyDown, 0))
        }
    }

    private companion object {
        val DIRECTIONS =
            listOf(
                Key.DirectionUp to RemoteKey.Up,
                Key.DirectionDown to RemoteKey.Down,
                Key.DirectionLeft to RemoteKey.Left,
                Key.DirectionRight to RemoteKey.Right,
            )
    }

    @Test
    fun padToActTakesEveryDirectionByDefault() {
        assertEquals(RemoteKey.Left, padToAct(Key.DirectionLeft, KeyEventType.KeyDown, 0))
        assertEquals(RemoteKey.Right, padToAct(Key.DirectionRight, KeyEventType.KeyDown, 4))
        assertEquals(RemoteKey.Up, padToAct(Key.DirectionUp, KeyEventType.KeyDown, 2))
        assertNull(padToAct(Key.DirectionRight, KeyEventType.KeyUp, 0))
    }

    @Test
    fun padToActWithUpDownKeepsLeftAndRightButDoesNothingForThem() {
        assertEquals(RemoteKey.Up, padToAct(Key.DirectionUp, KeyEventType.KeyDown, 3, UP_DOWN))
        assertEquals(RemoteKey.Down, padToAct(Key.DirectionDown, KeyEventType.KeyDown, 0, UP_DOWN))
        assertNull(padToAct(Key.DirectionLeft, KeyEventType.KeyDown, 0, UP_DOWN))
        assertNull(padToAct(Key.DirectionRight, KeyEventType.KeyDown, 2, UP_DOWN))
        assertEquals(RemoteKey.Back, padToAct(Key.Back, KeyEventType.KeyUp, 0, UP_DOWN))
        assertEquals(RemoteKey.Select, padToAct(Key.Enter, KeyEventType.KeyDown, 0, UP_DOWN))
    }
}
