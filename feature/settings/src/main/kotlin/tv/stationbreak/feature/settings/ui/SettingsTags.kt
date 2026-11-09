// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import tv.stationbreak.core.model.ItemId

/** Test tags for the Settings screens, public so `app`'s instrumented tests can find their parts. */
object SettingsTags {
    /** The whole Libraries screen. */
    const val LIBRARIES = "settings:libraries"
    const val TITLE = "settings:libraries:title"
    const val LIST = "settings:libraries:list"

    /** The line under the title while the server is asked. */
    const val LOADING = "settings:libraries:loading"

    /** The line under the title when the server could not be asked. */
    const val UNAVAILABLE = "settings:libraries:unavailable"

    /** The line under the title after a change, or after an add was given up. */
    const val NOTICE = "settings:libraries:notice"

    fun row(id: ItemId) = "settings:libraries:row:${id.value}"

    /** The "Adding…" line under a row being added. */
    fun adding(id: ItemId) = "settings:libraries:adding:${id.value}"
}
