// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.live.LiveCommand

/**
 * Select brings the actions banner on its Down, and the banner's Info button takes the focus before the Up arrives. The
 * Up belongs to the press the screen consumed, so it must not reach the button (it clicks on Up) and open the info.
 */
class SelectKeyTest {
    private val sent = mutableListOf<LiveCommand>()
    private val held = HeldKeys()

    private fun select(
        type: KeyEventType,
        up: OverlayUp,
    ) = handleLiveKey(type, Key.DirectionCenter, up, held) { sent += it }

    @Test
    fun theReleaseOfTheSelectThatOpenedTheActionsBannerIsConsumed() {
        assertTrue(select(KeyEventType.KeyDown, OverlayUp.None))
        // The actions banner is up by the time the release arrives.
        assertTrue(select(KeyEventType.KeyUp, OverlayUp(actions = true)))
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowBanner), sent)
    }

    @Test
    fun aSelectPressOnAButtonPassesBothWays() {
        assertFalse(select(KeyEventType.KeyDown, OverlayUp(actions = true)))
        assertFalse(select(KeyEventType.KeyUp, OverlayUp(actions = true)))
        assertEquals(emptyList<LiveCommand>(), sent)
    }

    @Test
    fun theHoldIsForThatPressOnly() {
        select(KeyEventType.KeyDown, OverlayUp.None)
        select(KeyEventType.KeyUp, OverlayUp(actions = true))
        // The next press is the button's, down and up.
        assertFalse(select(KeyEventType.KeyDown, OverlayUp(actions = true)))
        assertFalse(select(KeyEventType.KeyUp, OverlayUp(actions = true)))
    }

    @Test
    fun enterIsHeldTheSameWay() {
        assertTrue(handleLiveKey(KeyEventType.KeyDown, Key.Enter, OverlayUp.None, held) { sent += it })
        assertTrue(handleLiveKey(KeyEventType.KeyUp, Key.Enter, OverlayUp(actions = true), held) { sent += it })
    }
}
