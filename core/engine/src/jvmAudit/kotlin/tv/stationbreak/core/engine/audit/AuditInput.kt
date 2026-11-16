// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.ChannelRules

/** What one audit run reads: the library [snapshot], and optionally what the [viewer] added to the lineup. */
internal class AuditInput(
    val snapshot: RuleSnapshot,
    val viewer: Viewer?,
)

/**
 * The viewer's own choices, which the planner treats as the viewer's: the recipes the viewer switched on
 * ([switchedOn], recipe keys) and the custom channels the viewer built ([customChannels], with the pool their rules
 * select).
 *
 * Two more are made in code, never read from a snapshot, and need a second day: [hiddenThenOn] are planner picks the
 * viewer hides on the first day and switches on again after the boundary, and [keptThenOn] is how many of the picks
 * that start at the boundary in their places the viewer switches off before they air (they are kept starters) and on
 * again after it. After the boundary a switch-on of either adds a channel.
 */
internal class Viewer(
    val switchedOn: List<String>,
    val customChannels: List<CustomChannel>,
    val hiddenThenOn: List<String> = emptyList(),
    val keptThenOn: Int = 0,
) {
    /** Whether the audit needs the day after the boundary for this viewer. */
    val needsSecondDay: Boolean get() = hiddenThenOn.isNotEmpty() || keptThenOn > 0
}

/** A custom channel: a [name] for the report and its [rules] (the rules format). */
internal class CustomChannel(
    val name: String,
    val rules: ChannelRules,
)

/** What [AuditSnapshotCodec.read] found. */
internal sealed interface SnapshotResult {
    class Ok(
        val input: AuditInput,
    ) : SnapshotResult

    /** [reason] is plain text with no file content in it: where the problem is, and what. */
    class Invalid(
        val reason: String,
    ) : SnapshotResult
}
