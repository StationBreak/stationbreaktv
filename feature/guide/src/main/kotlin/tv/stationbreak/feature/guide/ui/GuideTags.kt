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

    /** The first visit's note, in the panel's place until the first key (#94), and its body line. */
    const val FIRST_VISIT = "guide:firstVisit"
    const val FIRST_VISIT_BODY = "guide:firstVisit:body"
    const val PANEL_CHANNEL = "guide:panel:channel"

    /** The station bug (Stan's head and "Station Break" in Ranchers) at the start of the panel's top line. */
    const val STATION_BUG = "guide:stationBug"
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

    /** The channel box's tag line, its tags (the row, and each tag by its index) and its On now / Next line (#777). */
    const val CHANNEL_PANEL_TAG_LINE = "guide:channelPanel:tagLine"
    const val CHANNEL_PANEL_TAGS = "guide:channelPanel:tags"
    const val CHANNEL_PANEL_TAG = "guide:channelPanel:tag:"
    const val CHANNEL_PANEL_ON_NOW = "guide:channelPanel:onNow"
    const val CHANNEL_PANEL_ON_NOW_TITLE = "guide:channelPanel:onNow:title"
    const val CHANNEL_PANEL_ON_NOW_LEFT = "guide:channelPanel:onNow:left"
    const val CHANNEL_PANEL_ON_NOW_NEXT = "guide:channelPanel:onNow:next"

    /** The channel box's holdings (#757): the hours line, the list's label ("Home to:", "Mostly:") and the list. */
    const val CHANNEL_PANEL_HOURS = "guide:channelPanel:hours"
    const val CHANNEL_PANEL_LIST_LABEL = "guide:channelPanel:listLabel"
    const val CHANNEL_PANEL_LIST = "guide:channelPanel:list"
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

    /** The About row of the menu sheet (#803): the focused channel's properties; absent with no grid. */
    const val ABOUT_BUTTON = "guide:aboutButton"

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
    const val CHANNEL_CHANGE_STAN = "guide:channelChange:stan"
    const val CHANNEL_CHANGE_SNOW = "guide:channelChange:snow"

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

    /** The one label over the run of short programmes of [channel] that starts at [startMs]. */
    fun runLabel(
        channel: ChannelId,
        startMs: Long,
    ) = "guide:runLabel:${channel.value}:$startMs"

    /** That label's title. */
    fun runLabelTitle(
        channel: ChannelId,
        startMs: Long,
    ) = "guide:runLabelTitle:${channel.value}:$startMs"

    /** That label's count (" · 12"). */
    fun runLabelCount(
        channel: ChannelId,
        startMs: Long,
    ) = "guide:runLabelCount:${channel.value}:$startMs"

    /** The channel mark in the plate of [channel]. */
    fun channelMark(channel: ChannelId) = "guide:channelMark:${channel.value}"
}
