// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.PickerKind

/** The remote's Captions and Audio keys, and the picker's hold on the D-pad (design doc B2.1, B2.4). */
class PickerKeyTest {
    private val sent = mutableListOf<LiveCommand>()
    private val held = HeldKeys()

    private fun press(
        key: Key,
        up: OverlayUp,
    ): Boolean = handleLiveKey(KeyEventType.KeyDown, key, up, held) { sent += it }

    @Test
    fun theCaptionsKeyOpensTheSubtitlesPickerFromThePicture() {
        assertTrue(press(Key.Captions, OverlayUp.None))
        assertEquals(listOf<LiveCommand>(LiveCommand.OpenPicker(PickerKind.Subtitles)), sent)
    }

    @Test
    fun theAudioTrackKeyOpensTheAudioPicker() {
        assertTrue(press(Key.MediaAudioTrack, OverlayUp.None))
        assertEquals(listOf<LiveCommand>(LiveCommand.OpenPicker(PickerKind.Audio)), sent)
    }

    @Test
    fun theKeysWorkOverTheBannerToo() {
        press(Key.Captions, OverlayUp(actions = true))
        assertEquals(listOf<LiveCommand>(LiveCommand.OpenPicker(PickerKind.Subtitles)), sent)
    }

    @Test
    fun theGuideHasTheKeysWhileItIsOpen() {
        assertFalse(press(Key.Captions, OverlayUp(guide = true)))
        assertEquals(emptyList<LiveCommand>(), sent)
    }

    @Test
    fun anOpenPickerHasTheDpadAndNothingFlipsTheChannel() {
        val up = OverlayUp(actions = true, picker = true)
        listOf(Key.DirectionUp, Key.DirectionDown, Key.ChannelUp, Key.ChannelDown).forEach {
            assertFalse("$it passes to the picker's focus", press(it, up))
        }
        assertEquals(emptyList<LiveCommand>(), sent)
    }

    @Test
    fun selectAndTheArrowsAreTheRowsOwnWhileAPickerIsOpen() {
        val up = OverlayUp(actions = true, picker = true)
        listOf(Key.DirectionCenter, Key.Enter, Key.DirectionLeft, Key.DirectionRight).forEach {
            assertFalse("$it passes to the picker's focus", press(it, up))
        }
        // In particular Left and Right do not keep the banner's clock running: it is suspended while a picker is open.
        assertEquals(emptyList<LiveCommand>(), sent)
    }

    @Test
    fun backClosesThePickerNotTheBanner() {
        assertTrue(press(Key.Back, OverlayUp(actions = true, picker = true)))
        assertEquals(listOf<LiveCommand>(LiveCommand.DismissOverlay), sent)
    }

    @Test
    fun theOtherPickerKeyOpensThatPickerInstead() {
        press(Key.MediaAudioTrack, OverlayUp(actions = true, picker = true))
        assertEquals(listOf<LiveCommand>(LiveCommand.OpenPicker(PickerKind.Audio)), sent)
    }

    @Test
    fun menuAndInfoDoNothingOverAPicker() {
        val up = OverlayUp(actions = true, picker = true)
        assertFalse(press(Key.Menu, up))
        assertFalse(press(Key.Info, up))
        assertEquals(emptyList<LiveCommand>(), sent)
    }
}
