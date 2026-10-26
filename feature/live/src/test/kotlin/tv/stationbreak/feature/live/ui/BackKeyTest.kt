// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.live.LiveCommand

/** Back over the overlays: the press is consumed whole, even though its own Down closed the overlay. */
class BackKeyTest {
    private val sent = mutableListOf<LiveCommand>()
    private val held = HeldKeys()

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
    fun aBackThatWasNotConsumedPassesBothWays() {
        assertFalse(back(KeyEventType.KeyDown, OverlayUp.None))
        assertFalse(back(KeyEventType.KeyUp, OverlayUp.None))
        assertEquals(emptyList<LiveCommand>(), sent)
    }

    @Test
    fun theReleaseIsOnlyHeldBackOnce() {
        back(KeyEventType.KeyDown, OverlayUp(actions = true))
        assertTrue(back(KeyEventType.KeyUp, OverlayUp.None))
        assertFalse("the next, separate release is not ours", back(KeyEventType.KeyUp, OverlayUp.None))
    }
}
