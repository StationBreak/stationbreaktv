// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.schedule.ScheduleItem

/**
 * What [PoolEvaluator] works out: the items a channel's rules select, and counts about how it got there.
 *
 * @property items the pool, in the order of the snapshot it was worked out from (canonical-ID order). Every item is a
 *   movie or an episode, once. An item with a deletion time is kept: which items count for which epoch is the
 *   scheduler's decision (`EpochPool`), not this one.
 * @property counts see [PoolCounts].
 */
data class Pool(
    val items: List<ScheduleItem>,
    val counts: PoolCounts,
)

/**
 * The counts that explain a pool, for the channel list and for why-is-this-channel-empty messages. They are numbers and
 * positions only: no item, series, collection, genre or tag name is in them, so a message built from them cannot leak a
 * title or a name into a log.
 *
 * @property matchedBySource for each entry of `rules.sources`, in order, how many items of the snapshot matched it
 *   (before the exclusions and the ceiling). An item that matched several sources is counted for each.
 * @property excluded items that matched a source and were then removed by an exclusion.
 * @property blockedByCeiling items that matched a source, survived the exclusions and then failed the rating ceiling.
 * @property episodesWithUnknownSeries episodes that matched a source and survived the exclusions (before the ceiling)
 *   whose series ID is set but has no entry in the snapshot. Under a ceiling these are blocked.
 * @property unresolvedSources 0-based indices into `rules.sources`, ascending, of `library` sources whose ID is not in
 *   the snapshot's libraries and of `collection` sources whose ID is not in the snapshot's collections.
 * @property collectionsSynced the snapshot's own flag, passed through: false means a collection missing from the
 *   snapshot may only not have been listed yet.
 */
data class PoolCounts(
    val matchedBySource: List<Int>,
    val excluded: Int,
    val blockedByCeiling: Int,
    val episodesWithUnknownSeries: Int,
    val unresolvedSources: List<Int>,
    val collectionsSynced: Boolean,
)
