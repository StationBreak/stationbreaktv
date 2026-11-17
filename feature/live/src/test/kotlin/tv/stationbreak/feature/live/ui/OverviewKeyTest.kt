// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Down and Up scroll the program info's overview, only while it has more than fits (#703). */
class OverviewKeyTest {
    private val info = OverlayUp(info = true)
    private val held = HeldKeys()
    private val steps = mutableListOf<Boolean>()

    private fun key(
        type: KeyEventType,
        key: Key,
        up: OverlayUp = info,
        overflows: Boolean = true,
        repeat: Int = 0,
    ) = handleOverviewKey(type, key, repeat, up, held, overflows) { steps += it }

    @Test
    fun downAndUpScrollALongOverview() {
        assertEquals(true, overviewScrollForward(Key.DirectionDown, info, overflows = true))
        assertEquals(false, overviewScrollForward(Key.DirectionUp, info, overflows = true))
    }

    @Test
    fun aShortOverviewLeavesTheKeysToTheFlip() {
        assertNull(overviewScrollForward(Key.DirectionDown, info, overflows = false))
        assertNull(overviewScrollForward(Key.DirectionUp, info, overflows = false))
        assertFalse(key(KeyEventType.KeyDown, Key.DirectionDown, overflows = false))
        assertFalse(key(KeyEventType.KeyUp, Key.DirectionDown, overflows = false))
        assertEquals(emptyList<Boolean>(), steps)
    }

    @Test
    fun onlyTheInfoOverlayAndOnlyTheDpadScroll() {
        assertNull(overviewScrollForward(Key.DirectionDown, OverlayUp.None, overflows = true))
        assertNull(overviewScrollForward(Key.DirectionDown, OverlayUp(info = true, guide = true), overflows = true))
        assertNull(overviewScrollForward(Key.ChannelDown, info, overflows = true))
        // Back still closes the panel in one press.
        assertNull(overviewScrollForward(Key.Back, info, overflows = true))
    }

    @Test
    fun pressRepeatAndReleaseAreAllConsumedWhileOverflowing() {
        assertTrue(key(KeyEventType.KeyDown, Key.DirectionDown))
        assertTrue(key(KeyEventType.KeyDown, Key.DirectionDown, repeat = 1))
        assertTrue(key(KeyEventType.KeyUp, Key.DirectionDown, up = OverlayUp.None))
        assertEquals(listOf(true, true), steps)
        // The release was the press's own: a later stray release is not ours.
        assertFalse(key(KeyEventType.KeyUp, Key.DirectionDown))
    }

    @Test
    fun upScrollsBack() {
        assertTrue(key(KeyEventType.KeyDown, Key.DirectionUp))
        assertEquals(listOf(false), steps)
    }

    @Test
    fun otherOverlaysAndKeysFallThrough() {
        assertFalse(key(KeyEventType.KeyDown, Key.DirectionDown, up = OverlayUp.None))
        assertFalse(key(KeyEventType.KeyDown, Key.DirectionDown, up = OverlayUp(info = true, guide = true)))
        assertFalse(key(KeyEventType.KeyDown, Key.DirectionDown, up = OverlayUp(info = true, picker = true)))
        assertFalse(key(KeyEventType.KeyDown, Key.DirectionDown, up = OverlayUp(actions = true)))
        assertFalse(key(KeyEventType.KeyDown, Key.Back))
        assertFalse(key(KeyEventType.KeyUp, Key.Back))
        assertFalse(key(KeyEventType.KeyDown, Key.ChannelDown))
        assertEquals(emptyList<Boolean>(), steps)
    }

    @Test
    fun aRepeatOfAFlipIsNotTakenWhenTheOverviewGrowsMidPress() {
        // The first down flipped (short overview, so it fell through to handleLiveKey, which holds it).
        held.hold(Key.DirectionDown, KeyAction.Send(tv.stationbreak.feature.live.LiveCommand.Previous))
        assertFalse(key(KeyEventType.KeyDown, Key.DirectionDown, repeat = 1))
        assertEquals(emptyList<Boolean>(), steps)
    }
}
