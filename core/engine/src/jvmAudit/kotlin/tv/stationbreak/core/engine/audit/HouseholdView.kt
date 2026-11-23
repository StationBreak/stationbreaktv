// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.Pool
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelCeilings
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.HouseholdSources
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.Leftovers
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupDay
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.curatedOn
import tv.stationbreak.core.engine.lineup.curation.Curated
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.lineup.householdFor
import tv.stationbreak.core.engine.lineup.isViewerCustom
import tv.stationbreak.core.engine.lineup.rulesFor
import tv.stationbreak.core.engine.lineup.startsAfter
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.rules.AdultMark

/**
 * The epoch pool the day build makes of a channel's [items] for the day that starts at `dayStartMs`
 * (`EpochPool.build` at `Epochs.epochNumber`): given by the caller, because `core:engine` may not depend on
 * `core:scheduler` outside its tests.
 */
internal fun interface EpochCut {
    fun cut(
        settings: SchedulerSettings,
        items: List<ScheduleItem>,
        dayStartMs: Long,
    ): List<ScheduleItem>
}

/** One channel of the household view: what it airs on the day, under the ceiling and policy a day build gives it. */
@Suppress("LongParameterList") // A plain carrier: one parameter for each fact the invariants and the report read.
internal data class GateChannel(
    val key: String,
    val id: ChannelId,
    val number: Int?,
    val leftovers: Boolean,
    val ceiling: String?,
    val policy: PoolPolicy,
    /** What airs on the day: the pool cut by the epoch ([EpochCut]). Hours and the golden read this. */
    val items: List<ScheduleItem>,
    /** The titles (series or film IDs) the curation gives this channel as their home; empty for leftovers. */
    val homed: Set<CanonicalId>,
    /** The whole pool before the epoch cut (a starter's curated pool, a leftovers pool): the invariants read it too. */
    val pool: List<ScheduleItem> = items,
    /** A viewer's own channel marked adult (a copy of After Hours, #984): it needs a number of 100 or up. */
    val adultMark: Boolean = false,
)

/** The household view of one lineup on one day. [homes] is the curation's, null when no starter is on. */
internal class HouseholdView(
    val snapshot: RuleSnapshot,
    val channels: List<GateChannel>,
    val curated: Curated?,
)

/**
 * The household view (#1035): the lineup the real planner made, then each channel's day the way the day build makes it
 * (`core:domain` `LineupDays`): its rules for the day with the household flag applied, under its scheduled ceiling
 * and its own policy; the curated starters through [Curation.curate]; then the leftovers channels in claim order
 * (ceilinged first, then by number), each over what the channels before it claimed, with the opt-in starters' pools
 * withheld. Each channel airs its pool as the epoch cuts it (`EpochCut`), and the invariants read the whole pool too.
 * Left out, as in #784: the late-on pools and the curation's keeps (the trim window of `EpochPool.dayPool`). The
 * ceiling is `ChannelCeilings.of`, the one function `core:domain` schedules under too.
 */
internal object HouseholdViews {
    /**
     * The view of [planned]. [policyOverride], for the gate's own tests only, weakens a channel's policy (null keeps
     * the real `KidStatus` one), so a test can prove the invariants catch a view built through a laxer path.
     *
     * Not modelled, so refused with a message (a planner lineup with the viewer's switch-ons has none of them): edited
     * starters, retired channels and hidden channels. A viewer's own custom channel is modelled as its pool, cut by the
     * epoch, claimed from the leftovers as the day build does it (#1108). The day build also lets a non-curated starter
     * claim from the leftovers, and withholds a hidden channel's pool; a fixture that holds them needs that first.
     */
    fun of(
        planned: PlannedLineup,
        epoch: EpochCut,
        policyOverride: ((LineupChannel) -> PoolPolicy?)? = null,
    ): HouseholdView {
        val lineup = planned.lineup
        val unmodelled = lineup.channels.filter { isUnmodelled(it) }
        check(unmodelled.isEmpty()) {
            "The gate does not model edited starters, retired channels or hidden channels " +
                "(${unmodelled.size} in this lineup): see docs/design/lineup-seats-and-gaps.md"
        }
        val day = planned.day
        val snapshot = planned.snapshot
        val household = lineup.householdFor(day.startMs)
        val policyOf = { c: LineupChannel -> policyOverride?.invoke(c) ?: KidStatus.basePolicyOf(c) }
        val onSet = lineup.curatedOn(day, planned.planning.recipeKeys)
        val curationChannels =
            lineup.channels.filter { it.id in onSet }.mapNotNull { curationChannelOf(it, planned, household, policyOf) }
        val curated = if (curationChannels.isEmpty()) null else Curation.curate(snapshot, curationChannels)
        val claimed = HashSet<CanonicalId>()
        val channels = ArrayList<GateChannel>()
        for (c in curationChannels.sortedBy { it.number }) {
            val pool = checkNotNull(curated).pools.getValue(c.id)
            for (item in pool.claimed) claimed += item.id
            val source = lineup.channels.first { it.id == c.id }
            val homed = pool.items.map { it.groupId }.filterTo(HashSet()) { curated.homes.homeOf(it) == c.id }
            val items = epoch.cut(c.settings, pool.items, day.startMs)
            val policy = policyOf(source)
            val max = c.rules.maxRating
            channels += GateChannel(c.recipe.key, c.id, c.number, false, max, policy, items, homed, pool.items)
        }
        val customs = lineup.channels.filter { isOnCustom(it, day) }
        for (channel in customs.sortedWith(compareBy({ it.number }, { it.id.value }))) {
            val view = customView(channel, planned, day, epoch, policyOf) ?: continue
            view.items.mapTo(claimed) { it.id }
            channels += view
        }
        claimed += withheld(planned, household)
        val ceilinged = { c: LineupChannel -> ChannelCeilings.of(c, planned.catalog) == null }
        val order = compareBy<LineupChannel>(ceilinged).thenBy { it.number }.thenBy { it.id.value }
        val claiming = lineup.channels.filter { it.source is ChannelSource.Leftovers && it.state != ChannelState.Shelf }
        for (channel in claiming.sortedWith(order)) {
            if (channel.startsAfter(day)) continue
            val source = channel.source as ChannelSource.Leftovers
            val ceiling = ChannelCeilings.of(channel, planned.catalog)
            val policy = policyOverride?.invoke(channel) ?: KidStatus.policyOf(channel, lineup, day)
            val pool = Leftovers.pool(snapshot, claimed, ceiling, policy)
            // As the day build does: what a leftovers channel claims, and airs, is its pool as the epoch cuts it.
            val items = epoch.cut(leftoversSettings(source, planned), pool.items, day.startMs)
            items.mapTo(claimed) { it.id }
            if (channel.state == ChannelState.On) {
                val key = "leftovers:${source.recipeKey}"
                val id = channel.id
                val homed = emptySet<CanonicalId>()
                channels += GateChannel(key, id, channel.number, true, ceiling, policy, items, homed, pool.items)
            }
        }
        return HouseholdView(snapshot, channels, curated)
    }

    private fun isUnmodelled(channel: LineupChannel): Boolean {
        val source = channel.source
        val edited = source is ChannelSource.Starter && source.edited
        val retired = source is ChannelSource.Custom && !channel.isViewerCustom()
        return retired || source is ChannelSource.Unusable || edited || channel.state == ChannelState.UserHidden
    }

    // A viewer's own channel that is on the air on [day].
    private fun isOnCustom(
        channel: LineupChannel,
        day: LineupDay,
    ): Boolean = channel.isViewerCustom() && channel.state == ChannelState.On && !channel.startsAfter(day)

    // A custom channel on the day: its own rules under its own ceiling and the policy the day build gives it. Null when
    // the scheduler refuses its rules (the channel is then not scheduled).
    private fun customView(
        channel: LineupChannel,
        planned: PlannedLineup,
        day: LineupDay,
        epoch: EpochCut,
        policyOf: (LineupChannel) -> PoolPolicy,
    ): GateChannel? {
        val ceiling = ChannelCeilings.of(channel, planned.catalog)
        val rules = (channel.source as ChannelSource.Custom).rules.copy(maxRating = ceiling)
        val settings = runCatching { rules.toSchedulerSettings() }.getOrNull() ?: return null
        val policy = policyOf(channel)
        val pool = PoolEvaluator.evaluate(rules, planned.snapshot, policy).items
        val items = epoch.cut(settings, pool, day.startMs)
        val id = channel.id
        val marked = AdultMark.isMarked((channel.source as ChannelSource.Custom).rules)
        return GateChannel(id.value, id, channel.number, false, ceiling, policy, items, emptySet(), pool, marked)
    }

    // The catalog's recipe while the key is in it, as `LineupDays.leftoversSettings` has it.
    private fun leftoversSettings(
        source: ChannelSource.Leftovers,
        planned: PlannedLineup,
    ): SchedulerSettings {
        val recipe = planned.catalog.leftovers.first { it.key == source.recipeKey }
        return RecipeResolver.leftoversSettings(recipe, planned.lineup.serverId, planned.lineup.utcOffsetMinutes)
    }

    // The curation's view of a curated starter on the day: its rules (as `rulesAt` has them) under its ceiling and
    // policy.
    private fun curationChannelOf(
        channel: LineupChannel,
        planned: PlannedLineup,
        household: Boolean,
        policyOf: (LineupChannel) -> PoolPolicy,
    ): CurationChannel? {
        val source = channel.source as ChannelSource.Starter
        val recipe = planned.planning.recipe(source.recipeKey)
        val own = source.rulesFor(planned.day.startMs)
        val rules = if (source.edited || household) own else HouseholdSources.without(own, planned.snapshot)
        val ruled = rules?.copy(maxRating = ChannelCeilings.of(channel, planned.catalog))
        val settings = ruled?.let { runCatching { it.toSchedulerSettings() }.getOrNull() }
        if (recipe == null || ruled == null || settings == null) return null
        val policy = policyOf(channel)
        val pool = PoolEvaluator.evaluate(ruled, planned.snapshot, policy).items
        return CurationChannel(channel.id, recipe.number, recipe, policy == PoolPolicy.KidsOnly, ruled, settings, pool)
    }

    // What no leftovers channel may air: every opt-in starter's pool, on or off, under its recipe's policy.
    private fun withheld(
        planned: PlannedLineup,
        household: Boolean,
    ): Set<CanonicalId> {
        val out = HashSet<CanonicalId>()
        for (recipe in planned.catalog.recipes.filter { it.optIn }) {
            val resolved = planned.planning.resolve(recipe)
            val rules = (if (household) resolved else HouseholdSources.without(resolved, planned.snapshot)) ?: continue
            val pool: Pool = PoolEvaluator.evaluate(rules, planned.snapshot, KidStatus.policyOf(recipe))
            pool.items.mapTo(out) { it.id }
        }
        return out
    }
}
