// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.TitleWeights

/** The words for titles: the name of each of the IDs asked about that the library has one for. */
internal typealias TitleNames = suspend (Set<CanonicalId>) -> Map<CanonicalId, String>

/** One title of a day's pool, before it has a name: its hours, whether it is an anchor, and whether it is at home. */
internal data class TalliedTitle(
    val id: CanonicalId,
    val ms: Long,
    val anchor: Boolean,
    val home: Boolean,
)

/**
 * A day's pool counted by title, in the order a line lists them. [homed] are the titles whose home is the channel when
 * the day's own curation said so; without it (a day read back from disk) a title weighs 2 or 3 only when it is at home
 * or an anchor, so those are taken as homes.
 */
internal class HoldingsTally(
    val totalMs: Long,
    private val titles: List<TalliedTitle>,
) {
    /** The titles that live on the channel, anchors first, then most hours, then the lower title ID. */
    val homes: List<TalliedTitle> =
        titles.filter { it.home }.sortedWith(compareByDescending<TalliedTitle> { it.anchor }.then(BY_HOURS))

    /** Every title, most hours first, then the lower title ID. */
    val byHours: List<TalliedTitle> = titles.sortedWith(BY_HOURS)

    val titleCount: Int get() = titles.size

    companion object {
        private val BY_HOURS = compareByDescending<TalliedTitle> { it.ms }.thenBy { it.id.value }

        /** The tally of [pool]; null when it airs nothing. */
        fun of(
            pool: List<ScheduleItem>,
            weights: TitleWeights,
            homed: Set<CanonicalId>?,
        ): HoldingsTally? {
            val ms = LinkedHashMap<CanonicalId, Long>()
            var total = 0L
            for (item in pool) {
                ms.merge(item.groupId, item.runtimeMs, Long::plus)
                total += item.runtimeMs
            }
            if (ms.isEmpty()) return null
            val titles =
                ms.map { (id, held) ->
                    val weight = weights[id]
                    val home = homed?.contains(id) ?: (weight >= TitleWeights.MAX_WEIGHT - 1)
                    TalliedTitle(id, held, anchor = home && weight == TitleWeights.MAX_WEIGHT, home = home)
                }
            return HoldingsTally(total, titles)
        }
    }
}

/**
 * The tally as a [ChannelHoldings], its titles named by [names] (asked once, for the titles the lists could show). A
 * lookup that fails costs the names, not the counts; a title with no name is passed over for the next.
 */
internal suspend fun HoldingsTally.holdings(names: TitleNames): ChannelHoldings {
    val asked = ChannelHoldings.LISTED * 2
    val wanted = (homes.take(asked) + byHours.take(asked)).mapTo(LinkedHashSet()) { it.id }
    val named =
        try {
            names(wanted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }

    fun listed(titles: List<TalliedTitle>) =
        titles
            .mapNotNull { t -> named[t.id]?.let { HeldTitle(t.id, it, t.ms, t.anchor) } }
            .take(ChannelHoldings.LISTED)
    return ChannelHoldings(
        totalMs = totalMs,
        titleCount = if (homes.isEmpty()) titleCount else homes.size,
        homeCount = homes.size,
        homes = listed(homes),
        top = listed(byHours),
    )
}
