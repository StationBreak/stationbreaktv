// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import tv.stationbreak.core.model.ChannelId

/** Test tags for the guide screen, public so `app`'s instrumented tests can find its parts. */
object GuideTags {
    /** The whole guide, while it is open. */
    const val SCREEN = "guide:screen"
    const val GRID = "guide:grid"
    const val CLOCK = "guide:clock"
    const val PANEL = "guide:panel"
    const val PANEL_CHANNEL = "guide:panel:channel"
    const val PANEL_TITLE = "guide:panel:title"
    const val PANEL_EPISODE = "guide:panel:episode"
    const val PANEL_TIME = "guide:panel:time"
    const val PANEL_PROGRESS = "guide:panel:progress"
    const val PANEL_OVERVIEW = "guide:panel:overview"
    const val COMPACT_LABEL = "guide:compactLabel"
    const val NOW_LINE = "guide:nowLine"
    const val HINT = "guide:hint"
    const val LOADING = "guide:loading"
    const val EMPTY = "guide:empty"
    const val INFO = "guide:info"

    /** The cell of [channel] that starts at [startMs]. */
    fun cell(
        channel: ChannelId,
        startMs: Long,
    ) = "guide:cell:${channel.value}:$startMs"

    /** The text written in that cell, when it has room for any. */
    fun cellText(
        channel: ChannelId,
        startMs: Long,
    ) = "guide:cellText:${channel.value}:$startMs"
}
