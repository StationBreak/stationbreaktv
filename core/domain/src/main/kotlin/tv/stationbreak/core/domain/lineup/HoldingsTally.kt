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
 * A day's pool counted by title, in the order a line lists them. [homed] are the titles whose home is the channel, as
 * the day's build kept them ([ChannelDay.homed]); null when the day does not say, and then [homes] is null too.
 */
internal class HoldingsTally(
    val totalMs: Long,
    private val titles: List<TalliedTitle>,
    homed: Boolean,
) {
    /** The titles that live on the channel, anchors first, then most hours, then the lower title ID; null: unknown. */
    val homes: List<TalliedTitle>? =
        if (homed) titles.filter { it.home }.sortedWith(compareByDescending<TalliedTitle> { it.anchor }.then(BY_HOURS)) else null

    /** Every title, most hours first, then the lower title ID. */
    val byHours: List<TalliedTitle> = titles.sortedWith(BY_HOURS)

    val titleCount: Int get() = titles.size

    companion object {
        private val BY_HOURS = compareByDescending<TalliedTitle> { it.ms }.thenBy { it.id.value }

        /** The tally of [pool]; null when it airs nothing. A title is an anchor when it is a home of weight 3. */
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
                    val home = homed?.contains(id) == true
                    TalliedTitle(id, held, anchor = home && weights[id] == TitleWeights.MAX_WEIGHT, home = home)
                }
            return HoldingsTally(total, titles, homed != null)
        }
    }
}

/**
 * The tally as a [ChannelHoldings] valid until [untilMs], its titles named by [names] (asked once, for the titles the
 * lists could show). A lookup that fails costs the names, not the counts; a title with no name is passed over for the
 * next.
 */
internal suspend fun HoldingsTally.holdings(
    untilMs: Long,
    names: TitleNames,
): ChannelHoldings {
    val asked = ChannelHoldings.LISTED * 2
    val homed = homes
    val wanted = ((homed?.take(asked) ?: emptyList()) + byHours.take(asked)).mapTo(LinkedHashSet()) { it.id }
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
    val shown = if (homed == null) HomeShows.Unknown else HomeShows.Known(homed.size, listed(homed))
    return ChannelHoldings(totalMs, titleCount, shown, listed(byHours), untilMs)
}
