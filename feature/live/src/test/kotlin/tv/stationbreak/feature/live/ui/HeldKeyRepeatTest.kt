// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.live.FlipDirection
import tv.stationbreak.feature.live.LiveCommand

/**
 * A key held down auto-repeats: more downs with a rising repeat count, then one up. The repeats belong to the press the
 * screen took, so they are consumed, and only a flip runs again (holding Up keeps flipping). Anything else would redo a
 * step the press already took: open the info a second time, close the banner under the overlay Back just closed, or
 * toggle back and forth between two channels.
 */
class HeldKeyRepeatTest {
    private val sent = mutableListOf<LiveCommand>()
    private val held = HeldKeys()
    private val actions = OverlayUp(actions = true)

    private fun down(
        key: Key,
        repeat: Int,
        up: OverlayUp,
    ) = handleLiveKey(KeyEventType.KeyDown, key, up, held, repeat) { sent += it }

    private fun release(
        key: Key,
        up: OverlayUp,
    ) = handleLiveKey(KeyEventType.KeyUp, key, up, held) { sent += it }

    @Test
    fun aHeldSelectOpensTheActionsBannerOnceAndNeverReachesInfo() {
        assertTrue(down(Key.DirectionCenter, 0, OverlayUp.None))
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowBanner), sent)
        // Focus is on Info now; the repeats and the release are still the press that brought the banner.
        assertTrue(down(Key.DirectionCenter, 1, actions))
        assertTrue(down(Key.DirectionCenter, 2, actions))
        assertTrue(release(Key.DirectionCenter, actions))
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowBanner), sent)
    }

    @Test
    fun aHeldInfoKeyAsksOnce() {
        assertTrue(down(Key.Info, 0, OverlayUp.None))
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowInfo), sent)
        assertTrue(down(Key.Info, 1, actions))
        assertTrue(down(Key.Info, 2, actions))
        assertTrue(release(Key.Info, actions))
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowInfo), sent)
    }

    @Test
    fun aHeldBackOverInfoClosesOnlyTheInfo() {
        val both = OverlayUp(actions = true, info = true)
        assertTrue(down(Key.Back, 0, both))
        // The info is closed; the actions banner below it must stay.
        assertTrue(down(Key.Back, 1, actions))
        assertTrue(down(Key.Back, 2, actions))
        assertTrue(release(Key.Back, actions))
        assertEquals(listOf<LiveCommand>(LiveCommand.DismissOverlay), sent)
    }

    @Test
    fun aHeldMenuGoesBackOnce() {
        assertTrue(down(Key.Menu, 0, OverlayUp.None))
        assertTrue(down(Key.Menu, 1, OverlayUp.None))
        assertTrue(down(Key.Menu, 2, OverlayUp.None))
        assertTrue(release(Key.Menu, OverlayUp.None))
        assertEquals(listOf<LiveCommand>(LiveCommand.Previous), sent)
    }

    @Test
    fun aHeldUpOrDownKeepsFlipping() {
        assertTrue(down(Key.DirectionUp, 0, OverlayUp.None))
        assertTrue(down(Key.DirectionUp, 1, OverlayUp.None))
        assertTrue(release(Key.DirectionUp, OverlayUp.None))
        assertTrue(down(Key.DirectionDown, 0, OverlayUp.None))
        assertTrue(down(Key.DirectionDown, 1, OverlayUp.None))
        assertTrue(release(Key.DirectionDown, OverlayUp.None))
        val up = LiveCommand.Flip(FlipDirection.Up)
        val down = LiveCommand.Flip(FlipDirection.Down)
        assertEquals(listOf<LiveCommand>(up, up, down, down), sent)
    }

    @Test
    fun aHeldChannelKeyKeepsFlipping() {
        down(Key.ChannelUp, 0, OverlayUp.None)
        down(Key.ChannelUp, 1, OverlayUp.None)
        down(Key.ChannelUp, 2, OverlayUp.None)
        assertEquals(List(3) { LiveCommand.Flip(FlipDirection.Up) }, sent)
    }

    @Test
    fun aRepeatOfAPressTheScreenDidNotTakeStillPasses() {
        // Select pressed on a focused button is the button's, repeats included.
        assertFalse(down(Key.DirectionCenter, 0, actions))
        assertFalse(down(Key.DirectionCenter, 1, actions))
        assertFalse(release(Key.DirectionCenter, actions))
        assertEquals(emptyList<LiveCommand>(), sent)
    }

    @Test
    fun aNewPressAfterALostReleaseIsJudgedAfresh() {
        assertTrue(down(Key.DirectionCenter, 0, OverlayUp.None))
        // The release never came; the next press lands on the actions banner's button.
        assertFalse(down(Key.DirectionCenter, 0, actions))
        assertFalse(release(Key.DirectionCenter, actions))
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowBanner), sent)
    }
}
