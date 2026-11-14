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

    /** The logo that stands for the title when the item has one in time (#723); [PANEL_TITLE] is then its box. */
    const val PANEL_LOGO = "guide:panel:logo"

    /** The row of outlined tags under the meta line, and each tag in it by its index. */
    const val PANEL_TAGS = "guide:panel:tags"
    const val PANEL_TAG = "guide:panel:tag:"
    const val PANEL_EPISODE = "guide:panel:episode"
    const val PANEL_META = "guide:panel:meta"
    const val PANEL_TIME = "guide:panel:time"
    const val PANEL_PROGRESS = "guide:panel:progress"
    const val PANEL_OVERVIEW = "guide:panel:overview"

    /** The details panel's channel variant, while the focus is on the channel column (#631), and its name line. */
    const val CHANNEL_PANEL = "guide:channelPanel"
    const val CHANNEL_PANEL_NAME = "guide:channelPanel:name"
    const val NOW_LINE = "guide:nowLine"
    const val HINT = "guide:hint"
    const val LOADING = "guide:loading"
    const val EMPTY = "guide:empty"

    /** The line where the grid goes when none of this TV's libraries is available (spec 26). */
    const val NO_LIBRARIES = "guide:noLibraries"

    /** The menu sheet (#756): the dimmed screen-wide scrim and the sheet on it; present only while it is open. */
    const val MENU_SCRIM = "guide:menuScrim"
    const val MENU_SHEET = "guide:menuSheet"

    /** The line at the foot of the menu sheet that says what Back does there. */
    const val MENU_HINT = "guide:menuHint"

    /** The Channels row of the menu sheet. */
    const val CHANNELS_BUTTON = "guide:channelsButton"

    /** The Settings row of the menu sheet: the guide's Dark and Light choice. */
    const val SETTINGS_BUTTON = "guide:settingsButton"

    /** The Dark and Light choices of the Settings panel's Appearance row. */
    const val THEME_DARK = "guide:theme:dark"
    const val THEME_LIGHT = "guide:theme:light"

    /** The Safe and Edge choices of the Settings panel's Screen fit row. */
    const val FIT_SAFE = "guide:fit:safe"
    const val FIT_EDGE = "guide:fit:edge"

    /** The Off and On choices of the Settings panel's Even out volume row (#498). */
    const val LEVELLING_OFF = "guide:levelling:off"
    const val LEVELLING_ON = "guide:levelling:on"

    /** The Libraries row of the Settings panel: Select shows Settings, Libraries (spec 26). */
    const val SETTINGS_LIBRARIES = "guide:settings:libraries"

    /** The Sync now row of the Settings panel: Select syncs with the server now (spec 26). */
    const val SETTINGS_SYNC_NOW = "guide:settings:syncNow"

    /** The line beside Sync now: syncing, when it last synced, or why it failed. */
    const val SETTINGS_SYNC_STATUS = "guide:settings:syncStatus"

    /** The Exit row of the menu sheet. */
    const val EXIT_BUTTON = "guide:exitButton"

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

    /** The second line ("S1 E19") of that cell, for an episode whose cell has room for it. */
    fun cellSecondLine(
        channel: ChannelId,
        startMs: Long,
    ) = "guide:cellSecondLine:${channel.value}:$startMs"

    /** The channel mark in the plate of [channel]. */
    fun channelMark(channel: ChannelId) = "guide:channelMark:${channel.value}"
}
