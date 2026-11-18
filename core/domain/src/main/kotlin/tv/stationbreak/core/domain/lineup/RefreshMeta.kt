// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.Lineup

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
 * - [replanFromMs] is the boundary at which a household change (spec 23) takes effect, while one is pending: from
 *   then on the lineup is behind, so the refresher plans it again though the library did not change (a starter whose
 *   switch-on was deferred to the boundary is then switched on). Null when no such change is pending.
 * - [plannedWithoutCollections] is true once a plan of the lineup gave up waiting for the collection pass
 *   (`GIVE_UP_AFTER` passes failed): its first choice was made without them. Stored, so the lineup is still known to
 *   have its first choice ([firstChoiceMade]) after a restart, but only while it is the only thing that says so
 *   ([givenUpMatters]): once a pass is taken in or the lineup is finalised it is cleared, and the file goes back to the
 *   version it needs without it.
 */
internal data class RefreshMeta(
    val plannedGeneration: Long? = null,
    val collectionPassActedOn: Long? = null,
    val plannedLibraries: List<String>? = null,
    val extrasFromMs: Long? = null,
    val replanFromMs: Long? = null,
    val plannedWithoutCollections: Boolean = false,
) {
    /** True when the pending household change has reached its boundary at [nowMs]: the lineup is behind. */
    fun replanDue(nowMs: Long): Boolean = replanFromMs.let { it != null && nowMs >= it }
}

/**
 * The boundary a pending household change takes effect at, when the lineup must be planned again then: a basis still
 * waiting whose flag differs from the one now. Null otherwise.
 */
internal fun Lineup.replanFromMs(): Long? {
    val basis = curationBasis
    return if (basis != null && basis.household != useMyCollections) basis.fromMs else null
}

/**
 * Whether [RefreshMeta.plannedWithoutCollections] still says something about [lineup]: no pass taken in and not
 * finalised. Once either is so, it never goes back while the lineup is planned on (`collectionPassActedOn` keeps its
 * value until a newer pass, and the planner never unfinalises a lineup; only a reset plans from nothing), so
 * [firstChoiceMade] stays true without it.
 */
internal fun RefreshMeta.givenUpMatters(lineup: Lineup): Boolean =
    plannedWithoutCollections && collectionPassActedOn == null && !lineup.finalised

/**
 * Whether the planner has made [lineup]'s first choice ([FirstLineup]): it is finalised, or a plan took in a collection
 * pass (a thin library stays unfinalised after it), or a plan gave up waiting for one ([meta]).
 */
internal fun firstChoiceMade(
    lineup: Lineup,
    meta: RefreshMeta,
): Boolean = lineup.finalised || meta.collectionPassActedOn != null || meta.plannedWithoutCollections
