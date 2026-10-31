// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.ui.input.key.Key
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.SeekDirection

/**
 * The transport commands for [key] (ADR-0021, 2), or null when it is none. The media keys always are; Left and Right
 * are only while [scrubbing] (paused, with no overlay up).
 */
internal fun transportCommand(
    key: Key,
    scrubbing: Boolean,
): LiveCommand? =
    when (key) {
        Key.MediaPlayPause -> LiveCommand.PlayPause

        Key.MediaPlay -> LiveCommand.Play

        Key.MediaPause -> LiveCommand.Pause

        Key.MediaRewind, Key.MediaPrevious, Key.MediaSkipBackward -> LiveCommand.Seek(SeekDirection.Back)

        Key.MediaFastForward, Key.MediaNext, Key.MediaSkipForward -> LiveCommand.Seek(SeekDirection.Ahead)

        // DESIGN(#383): D-pad scrubbing while paused is provisional; detailed UX comes with the design partner.
        Key.DirectionLeft -> LiveCommand.Seek(SeekDirection.Back).takeIf { scrubbing }

        Key.DirectionRight -> LiveCommand.Seek(SeekDirection.Ahead).takeIf { scrubbing }

        else -> null
    }

/** A flip or a seek carries the key event's time and whether it repeats; other commands are as they are. */
internal fun LiveCommand.timed(
    repeat: Boolean,
    atMs: Long?,
): LiveCommand =
    when (this) {
        is LiveCommand.Flip -> copy(repeat = repeat, atMs = atMs)
        is LiveCommand.Seek -> copy(repeat = repeat, atMs = atMs)
        else -> this
    }
