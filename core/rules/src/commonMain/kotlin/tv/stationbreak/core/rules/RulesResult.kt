// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/**
 * What [RulesReader.read] found in a rules file. Only [Ok] may be scheduled, and a caller that does not schedule
 * version 2 features yet checks [ChannelRules.requiredVersion] too.
 */
sealed interface RulesResult {
    /** A file of version 1 or 2 that this reader read in full. */
    data class Ok(
        val rules: ChannelRules,
    ) : RulesResult

    /** The file is from a newer format. It is not read any further and the channel is not scheduled. */
    data class UpdateRequired(
        val version: Long,
    ) : RulesResult

    /**
     * The file is well formed but uses a kind this app does not schedule: an unknown source type, ordering mode or
     * filler mode, a filler mode other than `none`, or, in version 2, a `filters.kind`. The channel is not scheduled and the user is told an update is
     * required. [rules] is set when the whole file could still be read (so it can be kept and written back), and is
     * null when part of it could not be understood.
     */
    data class Unsupported(
        val reason: String,
        val rules: ChannelRules? = null,
    ) : RulesResult

    /** The file is not a valid rules file. [reason] is plain text with no file content in it. */
    data class Invalid(
        val reason: String,
    ) : RulesResult
}
