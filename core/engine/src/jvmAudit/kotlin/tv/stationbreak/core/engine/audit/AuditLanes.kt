// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.curation.CHANNEL_ORDER
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.lineup.curation.FitInputs
import tv.stationbreak.core.engine.lineup.curation.Homes
import tv.stationbreak.core.engine.lineup.curation.Homing
import tv.stationbreak.core.engine.lineup.curation.Lane
import tv.stationbreak.core.engine.lineup.curation.LaneBuilder
import tv.stationbreak.core.engine.lineup.curation.fitTermsQ16
import tv.stationbreak.core.engine.lineup.curation.foldedDistinct
import tv.stationbreak.core.engine.lineup.curation.formatFits
import tv.stationbreak.core.engine.lineup.curation.hits
import tv.stationbreak.core.engine.lineup.curation.matchesAny
import tv.stationbreak.core.engine.lineup.curationChannel
import tv.stationbreak.core.engine.lineup.reResolved
import tv.stationbreak.core.engine.lineup.rulesFor
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

    // The curation's view of a starter, as the planner makes it: the rules in force for an unedited starter on the
    // air, those it would resolve for one off the air (PlannerSelection.reasonInputOf). Null: not in the catalog.
    private fun curationChannelOf(channel: LineupChannel): CurationChannel? {
        val planning = planned.planning
        val source = channel.source as ChannelSource.Starter
        val recipe = planning.recipe(source.recipeKey) ?: return null
        val own = channel.state == ChannelState.On || source.edited
        val rules = if (own) source.rulesFor(planned.day.startMs) else planning.reResolved(recipe, source)
        val policy = KidStatus.policyOf(channel, planned.lineup, planned.day)
        val household = planning.household || source.edited
        return planning.curationChannel(channel.id, recipe.number, recipe, policy, rules, household)
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

    /** The terms of the score of candidate [position] of [lane], from the same inputs [AuditLanes] scored it with. */
    fun termsOf(
        lane: Lane,
        position: Int,
    ): TermValues {
        val inputs = inputsOf(lane)
        val title = builder.titles[lane.cand[position]]
        val f =
            FitInputs(
                hits(inputs.genres, title.genres),
                hits(inputs.tags, title.tags),
                matchesAny(inputs.studios, title.studios),
                lane.guestOnly,
                lane.anchor[position],
                title.title.madeForKids && lane.kid,
                formatFits(inputs.promise, title.title),
                lane.candTone[title.tone],
                lane.candMs,
            )
        val t = fitTermsQ16(lane.cand.size, f)
        return TermValues(listOf(t.specificity, t.genre, t.tag, t.studio, t.decade, t.anchor, t.kids, t.format, t.tone))
    }

    private val inputsByChannel = HashMap<ChannelId, LaneInputs>()

    private fun inputsOf(lane: Lane): LaneInputs =
        inputsByChannel.getOrPut(lane.channel.id) {
            val recipe = lane.channel.recipe
            LaneInputs(
                foldedDistinct(recipe.themeGenres),
                foldedDistinct(recipe.themeTags),
                recipe.theme?.themeStudios.orEmpty(),
                recipe.theme?.format,
            )
        }

    /** The policy name of a lane, for a report. */
    fun policyOf(lane: Lane): PoolPolicy = if (lane.kid) PoolPolicy.KidsOnly else PoolPolicy.GrownUp
}

private class LaneInputs(
    val genres: List<String>,
    val tags: List<String>,
    val studios: List<tv.stationbreak.core.engine.lineup.NameMatcher>,
    val promise: tv.stationbreak.core.engine.lineup.FormatPromise?,
)
