// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

/** Test tags of the Hours of operation screen (#789), public so `app`'s instrumented tests can find its parts. */
object HoursTags {
    /** The hours screen over the Settings sheet (#789): its sheet, title, hint row, notice and its two notes. */
    const val SCREEN = "guide:hours"
    const val TITLE = "guide:hours:title"
    const val HINT = "guide:hours:hint"
    const val NOTICE = "guide:hours:notice"
    const val PIN_NOTE = "guide:hours:pinNote"
    const val OVERLAP_NOTE = "guide:hours:overlapNote"

    /** What stands in the screen's place without Pro, and with Pro and no PIN; and the line for no periods. */
    const val PRO_REQUIRED = "guide:hours:proRequired"
    const val NEEDS_PIN = "guide:hours:needsPin"
    const val EMPTY = "guide:hours:empty"

    /** The Add a period row, and the period at [index] in the list's order. */
    const val ADD = "guide:hours:add"

    fun period(index: Int): String = "guide:hours:period:$index"

    /** The editor's row for [field]. */
    internal fun field(field: HoursField): String = "guide:hours:field:${field.name.lowercase()}"
}
