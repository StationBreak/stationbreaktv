// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.Leftovers
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.curatedOn
import tv.stationbreak.core.engine.lineup.curation.Curated
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.lineup.isOnAirOn
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.schedule.ScheduleItem

/** One channel that is on, with the items its pool holds on the day. [key] is its short name in a report. */
internal class OnChannel(
    val key: String,
    val id: ChannelId,
    val items: Set<CanonicalId>,
    val titles: Set<CanonicalId>,
    /** True for a leftovers channel, which airs what no other channel does. */
    val leftovers: Boolean = false,
)

/**
 * What airs on the day the report describes, as the planner's view has it: the curated starters' pools from
 * [Curation.curate], a custom channel's own pool, and what the leftovers channels take of the rest. A starter's pool
 * holds its homes and guests (every candidate on a kid channel).
 */
internal class OnAir(
    private val lanes: AuditLanes,
) {
    private val planned = lanes.planned
    private val groupOf: Map<CanonicalId, CanonicalId> =
        planned.snapshot.items.associate { it.item.id to it.item.groupId }

    /** The curated on-set, in channel order. */
    val curatedChannels: List<CurationChannel>

    /** The curation of [curatedChannels]: pools and homes. Null when no starter is on. */
    val curated: Curated?

    /** Every channel on the air that day, starters first (channel order), then custom, then leftovers. */
    val channels: List<OnChannel>

    init {
        val lineup = planned.lineup
        val onSet = lineup.curatedOn(planned.day, planned.planning.recipeKeys)
        curatedChannels = lanes.channels.filter { it.id in onSet }
        curated = if (curatedChannels.isEmpty()) null else Curation.curate(planned.snapshot, curatedChannels)
        val starters =
            curatedChannels.map { c ->
                val items =
                    curated
                        ?.pools
                        ?.getValue(c.id)
                        ?.items
                        .orEmpty()
                channel(c.recipe.key, c.id, items)
            }
        val customs = customChannels(lineup.channels.filter { it.isOnAirOn(planned.day) })
        val claimed = HashSet<CanonicalId>()
        for (c in starters + customs) claimed += c.items
        // What the day build withholds from the leftovers too: an opt-in starter that is off keeps its pool.
        for (lane in lanes.lanes) {
            val off = lane.channel.id !in onSet && lane.channel.recipe.optIn
            if (off) for (item in lane.channel.pool) claimed += item.id
        }
        channels = starters + customs + leftovers(lineup.channels, claimed)
    }

    private fun channel(
        key: String,
        id: ChannelId,
        items: Collection<ScheduleItem>,
        leftovers: Boolean = false,
    ): OnChannel {
        val ids = items.mapTo(LinkedHashSet()) { it.id }
        return OnChannel(key, id, ids, ids.mapTo(LinkedHashSet()) { groupOf.getValue(it) }, leftovers)
    }

    private fun customChannels(on: List<LineupChannel>): List<OnChannel> =
        on.filter { it.source is ChannelSource.Custom }.map { c ->
            val rules = (c.source as ChannelSource.Custom).rules
            val pool = PoolEvaluator.evaluate(rules, planned.snapshot, PoolPolicy.Open).items
            channel(c.id.value, c.id, pool)
        }

    private fun leftovers(
        all: List<LineupChannel>,
        claimed: Set<CanonicalId>,
    ): List<OnChannel> {
        val on = all.filter { it.isOnAirOn(planned.day) && it.source is ChannelSource.Leftovers }
        return on.sortedBy { it.number }.map { c ->
            val source = c.source as ChannelSource.Leftovers
            val policy = KidStatus.policyOf(c, planned.lineup, planned.day)
            val recipe = planned.catalog.leftovers.firstOrNull { it.key == source.recipeKey }
            val pool = Leftovers.pool(planned.snapshot, claimed, recipe?.maxRating ?: source.maxRating, policy)
            channel("leftovers:${source.recipeKey}", c.id, pool.items, leftovers = true)
        }
    }

    /** The runtime of every item of the snapshot, by item ID. */
    val runtimeOfItems: Map<CanonicalId, Long> by lazy {
        planned.snapshot.items.associate { it.item.id to it.item.runtimeMs }
    }

    /** The key of the channel that is the home of [title] in the curation, or null. */
    fun homeOf(title: CanonicalId): ChannelId? = curated?.homes?.homeOf(title)
}
