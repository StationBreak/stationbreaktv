// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.input.key.Key
import tv.stationbreak.feature.live.FlipDirection
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.PickerKind

/**
 * The command [key] asks for over [up], or null when it asks for none here. The guide has the D-pad and Select, so
 * nothing is sent while it is open (flipping, Menu and Info would act on a picture the viewer cannot see); an open
 * picker has them too ([pickerCommand]); otherwise the overlays and the transport take their keys ([overlayCommand]).
 */
internal fun mappedCommand(
    key: Key,
    up: OverlayUp = OverlayUp.None,
): LiveCommand? =
    when {
        up.guide -> null
        up.picker -> pickerCommand(key, open = true)
        else -> pickerCommand(key, open = false) ?: overlayCommand(key, up)
    }

/**
 * The remote's own picker keys, straight from the picture (design doc B2.1): Captions opens the Subtitles picker and
 * Audio track the Audio picker. With a picker [open] Back closes it to its chip, and every other key is the focus's
 * (Up and Down move in it and never flip the channel, Select chooses), so none is sent.
 */
private fun pickerCommand(
    key: Key,
    open: Boolean,
): LiveCommand? =
    when {
        key == Key.Captions -> LiveCommand.OpenPicker(PickerKind.Subtitles)
        key == Key.MediaAudioTrack -> LiveCommand.OpenPicker(PickerKind.Audio)
        open && key == Key.Back -> LiveCommand.DismissOverlay
        else -> null
    }

private fun overlayCommand(
    key: Key,
    up: OverlayUp,
): LiveCommand? {
    // Left, Right and Select belong to the overlays when one is up; paused with none, they are the transport's.
    val scrubbing = up.paused && !up.actions && !up.info
    // Over the actions banner Select clicks its focused button; over the info overlay it does nothing; paused it plays
    // (ADR-0021, 2).
    val select = if (scrubbing) LiveCommand.Play else LiveCommand.ShowBanner.takeUnless { up.actions || up.info }
    return when (key) {
        Key.DirectionUp, Key.ChannelUp -> LiveCommand.Flip(FlipDirection.Up)

        Key.DirectionDown, Key.ChannelDown -> LiveCommand.Flip(FlipDirection.Down)

        Key.Menu -> LiveCommand.Previous

        Key.Info -> LiveCommand.ShowInfo

        Key.DirectionCenter, Key.Enter -> select

        // Back closes an overlay; with none up it opens the guide (keyAction).
        Key.Back -> LiveCommand.DismissOverlay.takeIf { up.actions || up.info }

        else -> transportCommand(key, scrubbing)
    }
}
