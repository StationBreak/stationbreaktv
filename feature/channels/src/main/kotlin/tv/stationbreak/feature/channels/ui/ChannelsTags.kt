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

    /** The New channel row above the first group. */
    const val NEW_CHANNEL = "channels:new"
    const val CHOOSING = "channels:choosing"
    const val PANEL = "channels:panel"
    const val PANEL_TEXT = "channels:panel:text"

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
    const val ACTION_CANCEL = "channels:action:cancel"
    const val ACTION_CONFIRM = "channels:action:confirm"

    /** The heading of the On group, of More channels, and of Off. */
    const val HEADING_ON = "channels:heading:on"
    const val HEADING_SHELF = "channels:heading:shelf"
    const val HEADING_OFF = "channels:heading:off"

    fun row(channel: ChannelId) = "channels:row:${channel.value}"

    /** The reason an On channel has nothing to air, when it has one. */
    fun reason(channel: ChannelId) = "channels:reason:${channel.value}"

    /** One of the picker's three digits, 0 for the hundreds. */
    fun digit(position: Int) = "channels:digit:$position"
}
