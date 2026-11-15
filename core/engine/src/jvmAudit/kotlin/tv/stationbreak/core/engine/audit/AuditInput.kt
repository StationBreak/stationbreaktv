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
 */
internal class Viewer(
    val switchedOn: List<String>,
    val customChannels: List<CustomChannel>,
)

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
