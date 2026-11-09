// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import tv.stationbreak.core.model.ChannelId

/** Test tags for the channel screen, public so `app`'s instrumented tests can find its parts. */
object ChannelsTags {
    /** The whole screen. */
    const val SCREEN = "channels:screen"
    const val TITLE = "channels:title"
    const val LIST = "channels:list"

    /** The line under the title that says why nothing can be changed. */
    const val NOT_EDITABLE = "channels:notEditable"

    /** The line under the title for a refusal or a failure. */
    const val MESSAGE = "channels:message"
    const val RESET = "channels:reset"

    /** The "Use my collections" switch row at the top of the list. */
    const val HOUSEHOLD = "channels:household"

    /** The New channel row above the first group. */
    const val NEW_CHANNEL = "channels:new"
    const val CHOOSING = "channels:choosing"

    /** The note shown while a New, Copy or Edit press waits for the lineup to be known. */
    const val GETTING_READY = "channels:gettingReady"
    const val PANEL = "channels:panel"
    const val PANEL_TEXT = "channels:panel:text"

    /** The Sources step of the channel builder, the first thing it shows for a new channel. */
    const val BUILDER_SOURCES_STEP = "builder:step:Sources"

    /** The line in the actions panel of a channel made by a newer version of the app. */
    const val NEWER_APP = "channels:panel:newerApp"
    const val PICKER = "channels:picker"

    const val ACTION_WATCH = "channels:action:watch"
    const val ACTION_SWITCH_ON = "channels:action:switchOn"
    const val ACTION_SWITCH_OFF = "channels:action:switchOff"
    const val ACTION_COPY = "channels:action:copy"
    const val ACTION_EDIT = "channels:action:edit"
    const val ACTION_CHANGE_NUMBER = "channels:action:changeNumber"
    const val ACTION_DEFAULT_NUMBER = "channels:action:defaultNumber"
    const val ACTION_ABOUT = "channels:action:about"
    const val ACTION_CANCEL = "channels:action:cancel"
    const val ACTION_CONFIRM = "channels:action:confirm"

    /** The properties sheet, and its parts. */
    const val ABOUT = "channels:about"
    const val ABOUT_NAME = "channels:about:name"
    const val ABOUT_TAGS = "channels:about:tags"
    const val ABOUT_PERSONALITY = "channels:about:personality"
    const val ABOUT_LOADING = "channels:about:loading"
    const val ABOUT_PLAYS = "channels:about:plays"
    const val ABOUT_RUNS = "channels:about:runs"
    const val ABOUT_STATUS = "channels:about:status"
    const val ABOUT_CODE = "channels:about:code"

    /** The heading of the On group, of More channels, and of Off. */
    const val HEADING_ON = "channels:heading:on"
    const val HEADING_SHELF = "channels:heading:shelf"
    const val HEADING_OFF = "channels:heading:off"

    fun row(channel: ChannelId) = "channels:row:${channel.value}"

    /** The reason an On channel has nothing to air, when it has one. */
    fun reason(channel: ChannelId) = "channels:reason:${channel.value}"

    /** The reason a channel is shelved, or leaves at the next day start. */
    fun shelfReason(channel: ChannelId) = "channels:shelf-reason:${channel.value}"

    /** One of the picker's three digits, 0 for the hundreds. */
    fun digit(position: Int) = "channels:digit:$position"
}
