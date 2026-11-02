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
 * Back over the overlays: the press is consumed whole, even though its own Down closed the overlay. Back is not the
 * screen's only while the guide is open ([inGuide]): then it leaves live TV.
 */
class BackKeyTest {
    private val sent = mutableListOf<LiveCommand>()
    private val held = HeldKeys()
    private val inGuide = OverlayUp(guide = true)

    private fun back(
        type: KeyEventType,
        up: OverlayUp,
    ) = handleLiveKey(type, Key.Back, up, held) { sent += it }

    @Test
    fun theUpOfAConsumedBackIsConsumedAfterTheOverlayClosed() {
        assertTrue(back(KeyEventType.KeyDown, OverlayUp(info = true)))
        // The overlay is gone by the time the release arrives.
        assertTrue(back(KeyEventType.KeyUp, OverlayUp.None))
        assertEquals(listOf<LiveCommand>(LiveCommand.DismissOverlay), sent)
    }

    @Test
    fun liveOwnsOnlyTheFirstStepOfTheBackLadder() {
        // Show -> guide: the full picture's Back opens the guide, press and release consumed.
        val opened = mutableListOf<Boolean>()
        val key = { type: KeyEventType, up: OverlayUp ->
            handleLiveKey(type, Key.Back, up, held, guide = { opened += it }) { sent += it }
        }
        assertTrue(key(KeyEventType.KeyDown, OverlayUp.None))
        assertTrue(key(KeyEventType.KeyUp, OverlayUp.None))
        assertEquals(listOf(true), opened)
        // Guide -> playing cell -> menu row -> leave: each Back is the guide's, then the activity's; live passes all.
        repeat(3) {
            assertFalse(key(KeyEventType.KeyDown, inGuide))
            assertFalse(key(KeyEventType.KeyUp, inGuide))
        }
        assertEquals(listOf(true), opened)
        assertEquals(emptyList<LiveCommand>(), sent)
    }

    @Test
    fun aBackThatWasNotConsumedPassesBothWays() {
        assertFalse(back(KeyEventType.KeyDown, inGuide))
        assertFalse(back(KeyEventType.KeyUp, inGuide))
        assertEquals(emptyList<LiveCommand>(), sent)
    }

    @Test
    fun theReleaseIsOnlyHeldBackOnce() {
        back(KeyEventType.KeyDown, OverlayUp(actions = true))
        assertTrue(back(KeyEventType.KeyUp, OverlayUp.None))
        assertFalse("the next, separate release is not ours", back(KeyEventType.KeyUp, inGuide))
    }

    @Test
    fun aLostReleaseDoesNotSwallowTheNextRealOne() {
        // The first press was consumed, but its release never arrived.
        assertTrue(back(KeyEventType.KeyDown, OverlayUp(info = true)))
        // The next press finds the guide open: not consumed, and it clears the stale hold.
        assertFalse(back(KeyEventType.KeyDown, inGuide))
        assertFalse(back(KeyEventType.KeyUp, inGuide))
    }
}
