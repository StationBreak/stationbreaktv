// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.rules.ChannelRules

/**
 * What a draft's rules are written for (`DraftRules.toRules`): a new channel or an edit of a custom channel. It is
 * not a second version policy. The version is always `ChannelRules.writeVersion` (ADR-0026), and a new channel adds
 * one floor: it is written at 5, the spread day end (ADR-0027, ADR-0022's addendum), while an edit keeps the write
 * version, so a saved channel keeps its version (a v5 file stays 5, a v4 studio file stays 4, a v2 file that needs
 * only 1 goes to 1).
 */
internal sealed interface RulesTarget {
    /** The rules the draft was opened from, which kept settings are read from; null for a new channel from scratch. */
    val previous: ChannelRules?

    /** A new channel; [carry] is the rules of the built-in channel a copy was opened from, or null. */
    data class NewChannel(
        val carry: ChannelRules?,
    ) : RulesTarget {
        override val previous: ChannelRules? get() = carry
    }

    /** An edit of a custom channel whose stored rules are [previous]. */
    data class Edit(
        override val previous: ChannelRules,
    ) : RulesTarget
}
