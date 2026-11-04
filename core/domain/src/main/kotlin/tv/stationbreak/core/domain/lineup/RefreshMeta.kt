// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.Plan

/**
 * What the refresher remembers about a stored lineup (spec 12), kept in the same file as the lineup so a restart does
 * not lose it and `forget` takes it away with the lineup. The planner and the engine's `Lineup` know nothing of it.
 * A null field is "unknown", which never equals what the library says now, so it reads as behind.
 *
 * - [plannedGeneration] is the library's `contentGeneration` read just before the plan that produced the lineup.
 * - [collectionPassActedOn] is the start, in epoch milliseconds, of the latest collection pass that plan held (the
 *   planning read had its collections), or null if none yet.
 * - [plannedLibraries] is the sorted set of library IDs the plan used.
 * - [extrasFromMs] is when the extras filter (spec 23) starts to apply, in epoch milliseconds: a day whose start is at
 *   or after it airs no extras. [Long.MIN_VALUE] filters from the start (a new account); null is a file from before
 *   the filter, which the next plan gives the next day start.
 * - [replanFromMs] is the boundary at which a household change (spec 23) takes effect, or at which a change the
 *   planner deferred is due (a themed refresh's switch-on, a re-resolve kept off today's curation), while one is
 *   pending: from then on the lineup is behind, so the refresher plans it again though the library did not change, and
 *   that plan applies the deferred change. Null when no such change is pending.
 */
internal data class RefreshMeta(
    val plannedGeneration: Long? = null,
    val collectionPassActedOn: Long? = null,
    val plannedLibraries: List<String>? = null,
    val extrasFromMs: Long? = null,
    val replanFromMs: Long? = null,
) {
    /** True when the pending household change has reached its boundary at [nowMs]: the lineup is behind. */
    fun replanDue(nowMs: Long): Boolean = replanFromMs.let { it != null && nowMs >= it }
}

/**
 * When the lineup of this plan must be planned again: the pending household change's boundary ([replanFromMs]), or
 * [boundaryMs] when the plan deferred a change to it, whichever comes first. Null when neither.
 */
internal fun Plan.replanFromMs(boundaryMs: Long): Long? =
    listOfNotNull(lineup.replanFromMs(), boundaryMs.takeIf { replanAtBoundary }).minOrNull()

/**
 * The boundary a pending household change takes effect at, when the lineup must be planned again then: a basis still
 * waiting whose flag differs from the one now. Null otherwise.
 */
internal fun Lineup.replanFromMs(): Long? {
    val basis = curationBasis
    return if (basis != null && basis.household != useMyCollections) basis.fromMs else null
}
