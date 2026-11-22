// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.PlannerSelection
import tv.stationbreak.core.engine.lineup.curation.CHANNEL_ORDER
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.lineup.curation.Homes
import tv.stationbreak.core.engine.lineup.curation.Homing
import tv.stationbreak.core.engine.lineup.curation.Lane
import tv.stationbreak.core.engine.lineup.curation.LaneBuilder
import tv.stationbreak.core.model.ChannelId

/**
 * The curation's lanes of every starter of the catalog, built the way the planner builds them: each starter's pool
 * under its own policy and the household flag in force, no per-day rating ceiling and no late-on pool. The lanes of
 * every starter are built once; any set of them can be homed.
 */
internal class AuditLanes(
    val planned: PlannedLineup,
) {
    /** The starters of the lineup that are in the catalog and not hidden, as curation channels, in channel order. */
    val channels: List<CurationChannel>
    private val starterOf = HashMap<ChannelId, LineupChannel>()

    init {
        val lineup = planned.lineup
        val starters = lineup.channels.filter { it.source is ChannelSource.Starter }
        val shown = starters.filter { it.state != ChannelState.UserHidden }
        val built = shown.mapNotNull { channel -> curationChannelOf(channel)?.also { starterOf[channel.id] = channel } }
        channels = built.sortedWith(CHANNEL_ORDER)
    }

    // The curation's view of a starter, exactly as the planner makes it (PlannerSelection.curationOf): the rules in
    // force for a starter on the air, those it would resolve for one off the air, under its own policy. Null: not in
    // the catalog.
    private fun curationChannelOf(channel: LineupChannel): CurationChannel? {
        val source = channel.source as ChannelSource.Starter
        if (planned.planning.recipe(source.recipeKey) == null) return null
        return PlannerSelection.curationOf(channel, planned.planning, planned.planning.household)
    }

    private val builder = LaneBuilder(planned.snapshot, channels)

    private val homing: Homing = Curation.homing(planned.snapshot, channels)

    val lanes: Array<Lane> get() = builder.lanes

    fun channelOf(lane: Lane): LineupChannel = starterOf.getValue(lane.channel.id)

    fun homes(on: Set<ChannelId>?): Homes = homing.homes(on)

    fun titleOf(
        lane: Lane,
        position: Int,
    ) = builder.titles[lane.cand[position]].title

    /** The terms of the score of candidate [position] of [lane]; they add up to the lane's score (checked inside). */
    fun termsOf(
        lane: Lane,
        position: Int,
    ): TermValues {
        val t = builder.termsOf(lane, position)
        return TermValues(listOf(t.specificity, t.genre, t.tag, t.studio, t.decade, t.anchor, t.kids, t.format, t.tone))
    }

    /** The policy name of a lane, for a report. */
    fun policyOf(lane: Lane): PoolPolicy = KidStatus.basePolicyOf(channelOf(lane))
}
