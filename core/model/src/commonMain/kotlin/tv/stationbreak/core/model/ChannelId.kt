// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.jvm.JvmInline

/** Identifies a channel. What the value looks like is decided with the channel model (F0.7); it's never blank. */
@JvmInline
value class ChannelId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A channel ID can't be blank" }
    }
}
