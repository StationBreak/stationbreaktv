// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/** The limits a reader applies to a file from outside (docs/rules-format.md, "Limits"). */
object RulesLimits {
    /** The version this reader schedules. */
    const val SUPPORTED_FORMAT_VERSION: Long = 1

    const val MAX_BYTES: Int = 256 * 1024
    const val MAX_DEPTH: Int = 16
    const val MAX_LIST_ENTRIES: Int = 256
    const val MAX_NAME_CODE_POINTS: Int = 100
    const val MAX_TEXT_CODE_POINTS: Int = 256
}
