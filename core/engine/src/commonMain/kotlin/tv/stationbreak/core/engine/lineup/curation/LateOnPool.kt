// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.TitleWeights

/**
 * What a channel switched on after the curation's basis was taken airs until the next boundary: [items] in pool order,
 * the titles it [homed] (those no basis channel homes) and each title's [weights].
 */
class LateOnPool internal constructor(
    val items: List<ScheduleItem>,
    val homed: Set<CanonicalId>,
    val weights: TitleWeights,
)

/**
 * The pool of [channel] as it airs when it is switched on in the middle of a day, from the basis [homes] (null when no
 * channel was curated). The basis is frozen, so the channel takes no home from it; it airs:
 * - every title no basis channel homes, as its own, at the weight of a home;
 * - every title a basis channel homes, as a guest, at the weight of a guest (no entry: weight 1), unless a title made
 *   for kids would guest on a grown-up channel. (A kid channel airs every title of its pool, as a curated one does:
 *   its pool is already held to kid content.)
 * The anime rule holds for every title, as in the curation (an anime-only channel keeps only anime, any other grown-up
 * channel but After Hours drops it).
 *
 * The tone test and the guest cap of a curated guest are not applied: both are measured against a channel's home
 * hours, and this channel has none, so the curated rules would leave it empty. At the boundary the curation runs over
 * the new on-set and the channel gets its real homes.
 */
internal fun lateOnPool(
    snapshot: RuleSnapshot,
    channel: CurationChannel,
    homes: Homes?,
): LateOnPool {
    val pool = channel.pool
    val titles = Titles.of(snapshot, pool)
    val items = ArrayList<ScheduleItem>(pool.size)
    val homed = HashSet<CanonicalId>()
    val weights = HashMap<CanonicalId, Int>()
    for (item in pool) {
        val group = item.groupId
        val title = titles[group]
        // A title the snapshot does not hold cannot be judged: it stays, as it did before the guests.
        if (title != null && !admits(channel, title)) continue
        if (homes?.homeOf(group) == null) {
            items.add(item)
            homed.add(group)
            weights[group] = CurationDefaults.WEIGHT_HOME
        } else if (title != null && mayGuest(channel, title)) {
            // A guest weighs 1, the weight of a title with no entry, so it gets none (as `CurationRun.pools` does).
            items.add(item)
        }
    }
    return LateOnPool(items, homed, TitleWeights.of(weights))
}

// A title made for kids never guests on a grown-up channel (the anime rule is checked for every title).
private fun mayGuest(
    channel: CurationChannel,
    title: Title,
): Boolean = channel.kid || !title.madeForKids
