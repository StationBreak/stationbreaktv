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

    /** "Converting for this TV", right-aligned after the chips, only while the route is a transcode. Not focusable. */
    const val BANNER_TRANSCODING = "live:banner:transcoding"

    /** The label along the top of the picture while the guide is open (#722). */
    const val PICTURE_LABEL = "live:picture:label"

    /** A picker over the chips; its rows are tagged [PICKER_ROW] plus the row's index. */
    const val PICKER = "live:picker"
    const val PICKER_ROW = "live:picker:row:"

    /** "brief pause", once above the rows when choosing any of them pauses the picture. */
    const val PICKER_HEADING = "live:picker:heading"
    const val TRANSPORT = "live:transport"
    const val TRANSPORT_SPINNER = "live:transport:spinner"
    const val INFO = "live:info"

    /** Stan's station screens: standby while a channel tunes (the difficulties and empty-screen roots are below). */
    const val STAN_STANDBY = "live:stan:standby"

    /** Stan standing by while live starts, before anything is chosen: no channel to name. */
    const val STAN_STARTING = "live:stan:starting"
    const val STAN_OFF_AIR = "live:stan:offAir"
    const val STAN_STAGE = "live:stan:stage"
    const val STAN_HEADLINE = "live:stan:headline"
    const val STAN_STATUS = "live:stan:status"
    const val STAN_DETAIL = "live:stan:detail"
    const val STAN_SIGN_OFF = "live:stan:signOff"
    const val STAN_CLOCK = "live:stan:clock"
    const val DIFFICULTIES = "live:difficulties"

    /** Stan's screen when no channel is on the air. */
    const val NO_CHANNELS = "live:noChannels"

    /** Stan's screen when none of this TV's libraries is available (spec 26). */
    const val NO_LIBRARIES = "live:noLibraries"
}
