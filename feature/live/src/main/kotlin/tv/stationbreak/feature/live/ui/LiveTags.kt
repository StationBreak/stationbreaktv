// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

/** Test tags for the live screen, public so `app`'s instrumented tests can find its parts. */
object LiveTags {
    const val SNOW = "live:snow"
    const val BANNER = "live:banner"
    const val BANNER_TITLE = "live:banner:title"
    const val BANNER_SUBTITLE = "live:banner:subtitle"
    const val BANNER_CHANNEL = "live:banner:channel"
    const val BANNER_SLOT = "live:banner:slot"
    const val BANNER_MARK = "live:banner:mark"
    const val BANNER_JOINED = "live:banner:joined"

    /** The stream line: what plays, or "Couldn't switch" for one linger after a switch that did not take. */
    const val BANNER_STREAM = "live:banner:stream"
    const val BANNER_CHOOSING = "live:banner:choosing"
    const val BANNER_PROGRESS = "live:banner:progress"
    const val BANNER_NEXT = "live:banner:next"
    const val BANNER_ACTIONS = "live:banner:actions"
    const val BANNER_ACTION_INFO = "live:banner:action:info"
    const val BANNER_ACTION_LAST = "live:banner:action:last"
    const val BANNER_ACTION_LIVE = "live:banner:action:live"
    const val BANNER_CHIP_AUDIO = "live:banner:chip:audio"
    const val BANNER_CHIP_SUBTITLES = "live:banner:chip:subtitles"
    const val BANNER_CHIP_VERSION = "live:banner:chip:version"

    /** "Transcoding", right-aligned after the chips, only while the route is a transcode. Not focusable. */
    const val BANNER_TRANSCODING = "live:banner:transcoding"

    /** A picker over the chips; its rows are tagged [PICKER_ROW] plus the row's index. */
    const val PICKER = "live:picker"
    const val PICKER_ROW = "live:picker:row:"

    /** "brief pause", once above the rows when choosing any of them pauses the picture. */
    const val PICKER_HEADING = "live:picker:heading"
    const val TRANSPORT = "live:transport"
    const val TRANSPORT_SPINNER = "live:transport:spinner"
    const val INFO = "live:info"
    const val TEST_CARD = "live:testCard"
    const val DIFFICULTIES = "live:difficulties"
    const val IDENT = "live:ident"
    const val IDENT_NOTE = "live:identNote"
    const val NO_CHANNELS = "live:noChannels"

    /** The line on the card when none of this TV's libraries is available (spec 26). */
    const val NO_LIBRARIES = "live:noLibraries"
}
