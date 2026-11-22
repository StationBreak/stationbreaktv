// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.engine.Pool
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.HouseholdSources
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.Leftovers
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.lineup.curatedOn
import tv.stationbreak.core.engine.lineup.curation.Curated
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.lineup.householdFor
import tv.stationbreak.core.engine.lineup.rulesFor
import tv.stationbreak.core.engine.lineup.startsAfter
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.rules.RatingLadder

/** One channel of the household view: what it airs on the day, under the ceiling and policy a day build gives it. */
@Suppress("LongParameterList") // A plain carrier: one parameter for each fact the invariants and the report read.
internal data class GateChannel(
    val key: String,
    val id: ChannelId,
    val number: Int?,
    val leftovers: Boolean,
    val ceiling: String?,
    val policy: PoolPolicy,
    /** What airs: a starter's curated pool (homes and guests), a leftovers channel's pool. No late-on pool. */
    val items: List<ScheduleItem>,
    /** The titles (series or film IDs) the curation gives this channel as their home; empty for leftovers. */
    val homed: Set<CanonicalId>,
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
 * withheld. Left out, as in #784: the late-on pools and the epoch trim window. `core:engine` cannot depend on
 * `core:domain`, so the ceiling rule is mirrored here ([ceilingOf]) and held by the gate's own band invariants.
 */
internal object HouseholdViews {
    fun of(planned: PlannedLineup): HouseholdView {
        val lineup = planned.lineup
        val day = planned.day
        val snapshot = planned.snapshot
        val household = lineup.householdFor(day.startMs)
        val onSet = lineup.curatedOn(day, planned.planning.recipeKeys)
        val curationChannels =
            lineup.channels.filter { it.id in onSet }.mapNotNull { curationChannelOf(it, planned, household) }
        val curated = if (curationChannels.isEmpty()) null else Curation.curate(snapshot, curationChannels)
        val claimed = HashSet<CanonicalId>()
        val channels = ArrayList<GateChannel>()
        for (c in curationChannels.sortedBy { it.number }) {
            val pool = checkNotNull(curated).pools.getValue(c.id)
            for (item in pool.claimed) claimed += item.id
            val source = lineup.channels.first { it.id == c.id }
            val homed = pool.items.map { it.groupId }.filterTo(HashSet()) { curated.homes.homeOf(it) == c.id }
            val policy = policyOf(source)
            channels += GateChannel(c.recipe.key, c.id, c.number, false, c.rules.maxRating, policy, pool.items, homed)
        }
        claimed += withheld(planned, household)
        val order = compareBy<LineupChannel> { ceilingOf(it) == null }.thenBy { it.number }.thenBy { it.id.value }
        val claiming = lineup.channels.filter { it.source is ChannelSource.Leftovers && it.state != ChannelState.Shelf }
        for (channel in claiming.sortedWith(order)) {
            if (channel.startsAfter(day)) continue
            val source = channel.source as ChannelSource.Leftovers
            val ceiling = ceilingOf(channel)
            val policy = KidStatus.policyOf(channel, lineup, day)
            val pool = Leftovers.pool(snapshot, claimed, ceiling, policy)
            pool.items.mapTo(claimed) { it.id }
            if (channel.state == ChannelState.On) {
                val key = "leftovers:${source.recipeKey}"
                channels += GateChannel(key, channel.id, channel.number, true, ceiling, policy, pool.items, emptySet())
            }
        }
        return HouseholdView(snapshot, channels, curated)
    }

    private fun policyOf(channel: LineupChannel): PoolPolicy = KidStatus.basePolicyOf(channel)

    // The curation's view of a curated starter on the day: its rules (household applied) under its ceiling and policy.
    private fun curationChannelOf(
        channel: LineupChannel,
        planned: PlannedLineup,
        household: Boolean,
    ): CurationChannel? {
        val recipe = planned.planning.recipe((channel.source as ChannelSource.Starter).recipeKey)
        val own = (channel.source as ChannelSource.Starter).rulesFor(planned.day.startMs)
        val rules = if (household) own else HouseholdSources.without(own, planned.snapshot)
        val ruled = rules?.copy(maxRating = ceilingOf(channel))
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

    /**
     * The ceiling [channel] is scheduled under, as `core:domain`'s `Ceilings.of` has it: the catalog's for a leftovers
     * channel and for an unedited starter (else the stored one), but a kid channel and a key that was ever kid-safe
     * are never above PG, and a built-in channel numbered in the Family band never above PG, in the Teen band never
     * above TV-14.
     */
    fun ceilingOf(channel: LineupChannel): String? {
        val stored = storedCeiling(channel.source)
        val cap = capOf(channel)
        return if (cap != null && !atMost(stored, cap)) cap else stored
    }

    private fun storedCeiling(source: ChannelSource): String? =
        when (source) {
            is ChannelSource.Leftovers -> {
                StarterCatalog.leftovers.firstOrNull { it.key == source.recipeKey }?.maxRating ?: source.maxRating
            }

            is ChannelSource.Starter -> {
                val recipe = StarterCatalog.recipe(source.recipeKey)?.maxRating
                if (recipe != null && !source.edited) recipe else source.rules.maxRating
            }

            is ChannelSource.Custom -> {
                source.rules.maxRating
            }

            is ChannelSource.Unusable -> {
                null
            }
        }

    private fun capOf(channel: LineupChannel): String? {
        val everKidSafe = (channel.source as? ChannelSource.Starter)?.recipeKey in StarterCatalog.EVER_KID_SAFE_KEYS
        return when {
            KidStatus.isKidChannel(channel) || everKidSafe -> "PG"
            channel.source is ChannelSource.Custom -> null
            channel.number in Band.Family.numbers -> "PG"
            channel.number in Band.Teen.numbers -> "TV-14"
            else -> null
        }
    }

    // True for a ceiling on the ladder at [limit] or lower; null (no ceiling) and unknown names are not.
    private fun atMost(
        rating: String?,
        limit: String,
    ): Boolean {
        val rank = RatingLadder.rankOf(rating)
        return rank != null && rank <= checkNotNull(RatingLadder.rankOf(limit))
    }
}
