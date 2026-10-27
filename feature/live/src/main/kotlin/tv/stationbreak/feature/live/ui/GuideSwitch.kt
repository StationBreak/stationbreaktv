// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.stationbreak.core.model.ChannelId

/**
 * Whether the program guide is over live TV (spec 17), and how it was opened. Changed on the main thread, by the key
 * that opens or closes it, so the next key (read at key time, not from the last frame) already sees the change. Live TV
 * owns this, not the guide: the guide's own state may say "closed" for a moment while an open is under way.
 */
internal class GuideSwitch {
    private val open = MutableStateFlow(false)

    /** True while the guide is open. */
    val shown: StateFlow<Boolean> = open.asStateFlow()

    private val opened = MutableStateFlow(0L)

    /**
     * When the guide last opened, in `System.nanoTime` units: a new value for each opening, so the guide can tell a
     * close and reopen that [shown] (true, false, true before a frame reads it) does not show.
     */
    val openedAt: StateFlow<Long> = opened.asStateFlow()

    /** [openedAt] as it stands. */
    val openedAtNanos: Long get() = opened.value

    /** The channel that was playing when the guide last opened; null when none was yet. */
    @Volatile
    var channel: ChannelId? = null
        private set

    /** Opens the guide over [channel], at [atNanos]. */
    fun open(
        atNanos: Long,
        channel: ChannelId?,
    ) {
        this.channel = channel
        opened.value = atNanos
        open.value = true
    }

    fun close() {
        open.value = false
    }

    /**
     * Runs one sitting of live TV and closes the guide when it ends, however it ends: Home or standby stops the
     * sitting, and live TV comes back on the picture, not on a guide left open from before.
     */
    suspend fun whileSitting(sitting: suspend () -> Unit) {
        try {
            sitting()
        } finally {
            close()
        }
    }
}
