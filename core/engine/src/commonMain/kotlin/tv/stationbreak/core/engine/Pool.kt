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
 * Invariants:
 * - `matchedBySource.size == rules.sources.size`, and every index in [unresolvedSources] and [unresolvedExclusions]
 *   refers to the `ChannelRules` that was passed to [PoolEvaluator.evaluate], not to any other.
 * - The number of distinct items that matched a source equals
 *   `items.size + excluded + heldOut + childrensRatingOut + blockedByCeiling`. Summing
 *   [matchedBySource] over-counts when sources overlap, because an item is counted for each source it matched.
 * - Under a ceiling every unknown-series episode is blocked, so `blockedByCeiling - episodesWithUnknownSeries` is the
 *   number blocked by rating alone. (With no ceiling nothing is blocked, unless the pool is kids-only: a kids-only
 *   pool applies *   the kid rating table even with no ceiling.)
 * - [collectionsSynced] is a pass-through of the snapshot's flag.
 *
 * @property matchedBySource for each entry of `rules.sources`, in order, how many items of the snapshot matched it
 *   (before the exclusions and the ceiling). An item that matched several sources is counted for each.
 * @property excluded items that matched a source and were then removed by an exclusion. When [unresolvedExclusions] is
 *   not empty, that is every item that matched a source.
 * @property blockedByCeiling items that matched a source, survived the exclusions and then failed the rating ceiling.
 * @property episodesWithUnknownSeries episodes that matched a source, survived the exclusions and passed the hold
 *   (before the ceiling) whose series ID is set but has no entry in the snapshot. Under a ceiling these are blocked.
 * @property unresolvedSources 0-based indices into `rules.sources`, ascending, of `collection` sources whose ID is not
 *   in the snapshot's collections. Libraries are not listed: the snapshot's libraries are the ones that were asked for,
 *   so a library missing from them may only not have been requested.
 * @property unresolvedExclusions 0-based indices into `rules.effectiveExclusions`, ascending, of `collection`
 *   exclusions whose ID is not in the snapshot's collections. An exclusion that cannot be checked fails closed: while
 *   this is not empty, the pool is empty (docs/rules-format.md, rule 3).
 * @property collectionsSynced the snapshot's own flag, passed through: false means a collection missing from the
 *   snapshot may only not have been listed yet.
 * @property heldOut items that matched a source (for leftovers, were unclaimed), survived the exclusions and were not
 *   made for kids, on a kids-only channel. Always 0 under any other policy.
 * @property childrensRatingOut items that matched a source (for leftovers, were unclaimed), survived the exclusions and
 *   had a children's rating, on a grown-up channel (ADR-0020). Always 0 under any other policy.
 */
data class PoolCounts(
    val matchedBySource: List<Int>,
    val excluded: Int,
    val blockedByCeiling: Int,
    val episodesWithUnknownSeries: Int,
    val unresolvedSources: List<Int>,
    val unresolvedExclusions: List<Int>,
    val collectionsSynced: Boolean,
    val heldOut: Int = 0,
    val childrensRatingOut: Int = 0,
) {
    init {
        require(unresolvedSources.all { it in matchedBySource.indices }) { "An unresolved source is a source" }
    }
}
