// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/**
 * Format version 5, the spread day end. A file that says 5 spreads the time left at the end of a broadcast day through
 * the day as station breaks; a file that says 1 to 4 gathers it at the end, as before.
 *
 * Version 5 is a behaviour version, not a feature version: it knows the fields of version 4 (and matches studios under
 * studio fold 2, as 4 does), and `ChannelRules.requiredVersion` never reports it. `ChannelRules.writeVersion` keeps a
 * file that says 5 at 5.
 */
object SpreadDayEnd {
    /** The first format version that spreads the day's leftover time as station breaks. */
    const val FORMAT_VERSION: Int = RulesVocabulary.VERSION_5
}
