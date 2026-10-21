// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

/**
 * What the refresher remembers about a stored lineup (spec 12), kept in the same file as the lineup so a restart does
 * not lose it and `forget` takes it away with the lineup. The planner and the engine's `Lineup` know nothing of it.
 * A null field is "unknown", which never equals what the library says now, so it reads as behind.
 *
 * - [plannedGeneration] is the library's `contentGeneration` read just before the plan that produced the lineup.
 * - [collectionPassActedOn] is the start, in epoch milliseconds, of the latest collection pass that plan held (the
 *   planning read had its collections), or null if none yet.
 * - [plannedLibraries] is the sorted set of library IDs the plan used.
 */
internal data class RefreshMeta(
    val plannedGeneration: Long? = null,
    val collectionPassActedOn: Long? = null,
    val plannedLibraries: List<String>? = null,
)
