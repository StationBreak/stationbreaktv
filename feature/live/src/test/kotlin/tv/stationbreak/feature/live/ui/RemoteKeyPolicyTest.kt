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
 * Live TV takes Select and Back from the shared policy in core/ui (#1080): Select is DirectionCenter, Enter and
 * NumPadEnter alike, and Back acts on its release, its down being only kept.
 */
class RemoteKeyPolicyTest {
    private val selects = listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

    @Test
    fun allThreeSelectKeysBringTheBanner() {
        for (select in selects) {
            assertEquals("$select", LiveCommand.ShowBanner, mappedCommand(select))
        }
    }

    @Test
    fun allThreeSelectKeysPlayWhilePaused() {
        val paused = OverlayUp(paused = true)
        for (select in selects) {
            assertEquals("$select", LiveCommand.Play, mappedCommand(select, paused))
        }
    }

    @Test
    fun allThreeSelectKeysLeaveTheActionsAndInfoOverlaysAlone() {
        for (select in selects) {
            assertEquals("$select actions", null, mappedCommand(select, OverlayUp(actions = true)))
            assertEquals("$select info", null, mappedCommand(select, OverlayUp(info = true)))
        }
    }

    @Test
    fun aNumPadEnterDownIsSelect() {
        val sent = mutableListOf<LiveCommand>()
        val held = HeldKeys()
        assertTrue(handleLiveKey(KeyEventType.KeyDown, Key.NumPadEnter, OverlayUp.None, held) { sent += it })
        assertTrue(handleLiveKey(KeyEventType.KeyUp, Key.NumPadEnter, OverlayUp(actions = true), held) { sent += it })
        assertEquals(listOf<LiveCommand>(LiveCommand.ShowBanner), sent)
    }

    @Test
    fun aBackDownIsNotYetBackAndItsUpIs() {
        val sent = mutableListOf<LiveCommand>()
        val opened = mutableListOf<Boolean>()
        val held = HeldKeys()
        val info = OverlayUp(info = true)
        val key = { type: KeyEventType, up: OverlayUp ->
            handleLiveKey(type, Key.Back, up, held, guide = { opened += it }) { sent += it }
        }
        // The down is swallowed, so nothing behind sees it, but nothing acts.
        assertTrue(key(KeyEventType.KeyDown, info))
        assertEquals(emptyList<LiveCommand>(), sent)
        assertTrue(key(KeyEventType.KeyUp, info))
        assertEquals(listOf<LiveCommand>(LiveCommand.DismissOverlay), sent)
        // From the full picture the same: the guide opens on the release.
        assertTrue(key(KeyEventType.KeyDown, OverlayUp.None))
        assertEquals(emptyList<Boolean>(), opened)
        assertTrue(key(KeyEventType.KeyUp, OverlayUp.None))
        assertEquals(listOf(true), opened)
    }

    @Test
    fun aBackReleaseAloneStillActs() {
        // A fast double Back can arrive as a release alone (its down went to the screen behind).
        val opened = mutableListOf<Boolean>()
        assertTrue(handleLiveKey(KeyEventType.KeyUp, Key.Back, OverlayUp.None, HeldKeys(), guide = { opened += it }) {})
        assertEquals(listOf(true), opened)
    }

    @Test
    fun aBackOverTheOpenGuidePassesBothWaysAndNeverActs() {
        val sent = mutableListOf<LiveCommand>()
        val held = HeldKeys()
        val inGuide = OverlayUp(guide = true)
        assertFalse(handleLiveKey(KeyEventType.KeyDown, Key.Back, inGuide, held) { sent += it })
        // Even if the guide has closed by the release, the down was the guide's: not ours to act on.
        assertFalse(handleLiveKey(KeyEventType.KeyUp, Key.Back, OverlayUp.None, held) { sent += it })
        assertEquals(emptyList<LiveCommand>(), sent)
    }
}
