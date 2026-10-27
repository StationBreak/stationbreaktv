// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import tv.stationbreak.core.model.ChannelId

/** What the viewer asks of the live channel (spec 15, F1.4). The screen sends these; [LiveChannel.run] follows them. */
sealed interface LiveCommand {
    /**
     * Move the channel number one step; the stop is what tunes. One per key-down, auto-repeat included: a [repeat] (a
     * key held down) steps only at the held-key rate (`LiveTimings.flipRepeatDelay` and `flipRepeatEvery`, #430),
     * paced by [atMs], the key event's time (`KeyEvent.getEventTime`, uptime), or the clock when there is none. While
     * the key is down nothing is tuned: the settle counts from [FlipReleased] (#430).
     */
    data class Flip(
        val direction: FlipDirection,
        val repeat: Boolean = false,
        val atMs: Long? = null,
    ) : LiveCommand

    /**
     * The Up or Down key of the last [Flip] came up: the settle starts now. A release that never arrives is covered by
     * `LiveTimings.flipReleaseFallback` after the last down or repeat.
     */
    data object FlipReleased : LiveCommand

    /** The Menu key: back to the channel before this one, at once. Pressed again, it toggles back. */
    data object Previous : LiveCommand

    /**
     * Select on a program on now in the guide (spec 17): tune [channel] at once, through the same commit as a flip, so
     * Menu, the look-ahead and reporting follow as they do for one. A newer press than any still waiting.
     */
    data class Tune(
        val channel: ChannelId,
    ) : LiveCommand

    /**
     * Select with no banner, or the Info key with none: shows the actions banner (Info, Last) for the program on, and
     * keeps it up while it is pressed again. Does nothing unless the picture plays.
     */
    data object ShowBanner : LiveCommand

    /** Opens the program info overlay when a banner shows; with none, shows the actions banner like [ShowBanner]. */
    data object ShowInfo : LiveCommand

    /** Back: closes the info overlay (the actions banner comes back), else the actions banner; else nothing. */
    data object DismissOverlay : LiveCommand
}
