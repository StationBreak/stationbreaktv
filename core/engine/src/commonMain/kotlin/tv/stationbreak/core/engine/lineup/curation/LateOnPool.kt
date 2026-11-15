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
 * - every title a basis channel homes, as a guest, at the weight of a guest, unless the rules of a guest keep it out:
 *   the anime rule (an anime-only channel keeps only anime, any other grown-up channel drops it), and a title made for
 *   kids never guests on a grown-up channel.
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
    val homedElsewhere = ArrayList<ScheduleItem>()
    for (item in pool) if (homes?.homeOf(item.groupId) != null) homedElsewhere.add(item)
    val titles = if (homedElsewhere.isEmpty()) emptyMap() else Titles.of(snapshot, homedElsewhere)
    val items = ArrayList<ScheduleItem>(pool.size)
    val homed = HashSet<CanonicalId>()
    val weights = HashMap<CanonicalId, Int>()
    for (item in pool) {
        val group = item.groupId
        if (homes?.homeOf(group) == null) {
            items.add(item)
            homed.add(group)
            weights[group] = CurationDefaults.WEIGHT_HOME
        } else {
            val title = titles[group]
            if (title != null && mayGuest(channel, title)) {
                items.add(item)
                if (group !in weights) weights[group] = CurationDefaults.WEIGHT_GUEST
            }
        }
    }
    return LateOnPool(items, homed, TitleWeights.of(weights))
}

// A title may guest where the anime rule admits it, and a title made for kids never guests on a grown-up channel.
private fun mayGuest(
    channel: CurationChannel,
    title: Title,
): Boolean = admits(channel, title) && (channel.kid || !title.madeForKids)
