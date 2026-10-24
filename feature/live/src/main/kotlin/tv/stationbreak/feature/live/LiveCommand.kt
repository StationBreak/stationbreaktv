// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

/** What the viewer asks of the live channel (spec 15, F1.4). The screen sends these; [LiveChannel.run] follows them. */
sealed interface LiveCommand {
    /** Move the channel number one step. One per key-down, auto-repeat included; the stop is what tunes. */
    data class Flip(
        val direction: FlipDirection,
    ) : LiveCommand

    /** The Menu key: back to the channel before this one, at once. Pressed again, it toggles back. */
    data object Previous : LiveCommand
}
