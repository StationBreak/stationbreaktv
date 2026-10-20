// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.Pool
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.Leftovers
import tv.stationbreak.core.engine.lineup.LeftoversRecipe
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.scheduler.EpochPool
import tv.stationbreak.core.scheduler.Epochs
import tv.stationbreak.core.engine.lineup.ChannelState as EngineState

/**
 * A switched-on channel's broadcast day: its [settings], the epoch [n] and that day's [pool] (`EpochPool.build`), in
 * canonical-ID order. With `buildDay(settings, pool, n)` it is the input of the day's schedule. [hidden] is why the day
 * is empty, or null.
 */
internal class ChannelDay(
    val channel: LineupChannel,
    val name: String,
    val settings: SchedulerSettings,
    val n: Long,
    val pool: List<ScheduleItem>,
    val hidden: HiddenReason?,
) {
    /** When the day starts, in milliseconds since 1970. */
    val epochStartMs: Long get() = Epochs.epochStart(settings, n)
}

/** A switched-on channel that can be scheduled, with the day that holds the moment asked about. */
internal class Scheduled(
    val channel: LineupChannel,
    val settings: SchedulerSettings,
    val n: Long,
) {
    /** The day's cut-off (`S - G`): the library snapshot the day is built from is read at it. */
    val cutoffMs: Long get() = Epochs.cutoff(settings, n)
}

/**
 * Works out a lineup's broadcast days at one moment (spec 12) from one library snapshot per distinct cut-off, read by
 * the caller (#275: never `scheduleItems`).
 *
 * A channel is scheduled when it is on, has a number and has usable rules or is a leftovers channel. A rules channel's
 * day pool is its pool (`PoolEvaluator`) for the day (`EpochPool.build`). A leftovers channel airs what is left after
 * claiming, against its own snapshot:
 * - every item in the day pool of every switched-on channel that is not a leftovers channel;
 * - every item of the pool of every opt-in catalog recipe, on or not, both as the catalog resolves it (without
 *   collection hints) and as the lineup's channel holds it;
 * - every item of the pool of every channel the user hid;
 * - the pools of the leftovers channels before it, ceilinged ones first (so the grown-up leftovers leaves out what the
 *   kids' leftovers airs, or would air if the user hid it).
 *
 * Known edge, accepted: an unusable channel has no pool, so if the user hid one its content can reach the leftovers.
 */
internal class LineupDays(
    private val catalog: StarterCatalogView,
) {
    /** The channels of [lineup] that are scheduled at [atMs], in number order. Reads nothing. */
    fun scheduled(
        lineup: Lineup,
        atMs: Long,
    ): List<Scheduled> =
        lineup.channels
            .filter { it.state == EngineState.On && it.number != null }
            .sortedWith(CHANNEL_ORDER)
            .mapNotNull { channel ->
                settingsOf(channel, lineup)?.let { Scheduled(channel, it, Epochs.epochNumber(it, atMs)) }
            }

    /** The days of [scheduled] (from [scheduled] for [lineup]), each from `snapshots[cutoffMs]`. */
    fun days(
        lineup: Lineup,
        scheduled: List<Scheduled>,
        snapshots: Map<Long, RuleSnapshot>,
    ): List<ChannelDay> = Build(lineup, snapshots).days(scheduled)

    /**
     * How many items of the snapshots are eligible for a day of a switched-on channel and yet air nowhere: in no day
     * pool, and not held back on purpose (opt-in recipes, hidden channels). Zero while the leftovers channels are on.
     */
    fun airingNowhere(
        lineup: Lineup,
        scheduled: List<Scheduled>,
        snapshots: Map<Long, RuleSnapshot>,
    ): Int {
        val build = Build(lineup, snapshots)
        val aired = build.days(scheduled).flatMapTo(HashSet()) { day -> day.pool.map { it.id } }
        val nowhere = HashSet<CanonicalId>()
        for ((cutoff, group) in scheduled.groupBy { it.cutoffMs }) {
            val items = snapshots.getValue(cutoff).items.map { it.item }
            val eligible =
                group.distinctBy { it.settings.minRuntimeMs to it.settings.epochMs }.flatMapTo(HashSet()) { s ->
                    EpochPool.build(s.settings, items, s.n).map { it.id }
                }
            nowhere += eligible - aired - build.withheld(cutoff) - build.hiddenLeftovers(cutoff)
        }
        return nowhere.size
    }

    private fun settingsOf(
        channel: LineupChannel,
        lineup: Lineup,
    ): SchedulerSettings? =
        try {
            when (val source = channel.source) {
                is ChannelSource.Starter -> source.rules.toSchedulerSettings()
                is ChannelSource.Custom -> source.rules.toSchedulerSettings()
                is ChannelSource.Leftovers -> leftoversSettings(source, lineup)
                is ChannelSource.Unusable -> null
            }
        } catch (_: IllegalArgumentException) {
            // Settings the scheduler refuses: the channel is not scheduled rather than the whole lineup failing.
            null
        }

    // The catalog's recipe while the key is in it. A retired key keeps the same settings: they depend only on the
    // key, the server and the lineup's offset, so a stand-in recipe with that key gives them.
    private fun leftoversSettings(
        source: ChannelSource.Leftovers,
        lineup: Lineup,
    ): SchedulerSettings {
        val recipe =
            catalog.leftovers.firstOrNull { it.key == source.recipeKey }
                ?: LeftoversRecipe(source.recipeKey, source.recipeKey, "", RETIRED_NUMBER, null)
        return RecipeResolver.leftoversSettings(recipe, lineup.serverId, lineup.utcOffsetMinutes)
    }

    /** One computation over one lineup and its snapshots. Pools are worked out once each. */
    private inner class Build(
        private val lineup: Lineup,
        private val snapshots: Map<Long, RuleSnapshot>,
    ) {
        private val withheldBy = HashMap<Long, Set<CanonicalId>>()

        // Leftovers channels that claim in turn: ceilinged first, then by number and ID. Shelved ones claim nothing.
        private val leftovers =
            lineup.channels
                .filter { it.source is ChannelSource.Leftovers && it.state != EngineState.Shelf }
                .sortedWith(compareBy<LineupChannel> { it.ceiling == null }.then(CHANNEL_ORDER))

        fun days(scheduled: List<Scheduled>): List<ChannelDay> {
            val ruled = scheduled.filter { it.channel.source !is ChannelSource.Leftovers }.associateWith(::ruledDay)
            val claimedByOn = ruled.values.flatMapTo(HashSet()) { day -> day.pool.map { it.id } }
            val byId = scheduled.associateBy { it.channel.id }
            return scheduled.map { ruled[it] ?: leftoversDay(it, claimedByOn, byId) }
        }

        private fun ruledDay(s: Scheduled): ChannelDay {
            val pool = PoolEvaluator.evaluate(checkNotNull(rulesOf(s.channel)), snapshots.getValue(s.cutoffMs))
            return dayOf(s, pool)
        }

        private fun leftoversDay(
            s: Scheduled,
            claimedByOn: Set<CanonicalId>,
            byId: Map<ChannelId, Scheduled>,
        ): ChannelDay {
            val snapshot = snapshots.getValue(s.cutoffMs)
            val claimed = HashSet(claimedByOn).apply { addAll(withheld(s.cutoffMs)) }
            for (before in leftovers.takeWhile { it.id != s.channel.id }) {
                val pool = leftoversPool(before, snapshot, claimed)
                val on = byId[before.id]
                val items = if (on != null) EpochPool.build(on.settings, pool.items, on.n) else pool.items
                items.mapTo(claimed) { it.id }
            }
            return dayOf(s, leftoversPool(s.channel, snapshot, claimed))
        }

        private fun dayOf(
            s: Scheduled,
            pool: Pool,
        ): ChannelDay {
            val day = EpochPool.build(s.settings, pool.items, s.n)
            return ChannelDay(s.channel, s.channel.displayName(catalog), s.settings, s.n, day, hiddenReason(pool, day))
        }

        /** What no leftovers channel may air from the [cutoff]'s snapshot: opt-in and hidden channels' pools. */
        fun withheld(cutoff: Long): Set<CanonicalId> =
            withheldBy.getOrPut(cutoff) {
                val snapshot = snapshots.getValue(cutoff)
                val rules = optInRules() + hiddenRules()
                rules.flatMapTo(HashSet()) { r -> PoolEvaluator.evaluate(r, snapshot).items.map { it.id } }
            }

        /** What the hidden leftovers channels would air from the [cutoff]'s snapshot: held back on purpose too. */
        fun hiddenLeftovers(cutoff: Long): Set<CanonicalId> {
            val snapshot = snapshots.getValue(cutoff)
            val claimed = withheld(cutoff)
            return leftovers.filter { it.state == EngineState.UserHidden }.flatMapTo(HashSet()) { channel ->
                leftoversPool(channel, snapshot, claimed).items.map { it.id }
            }
        }

        private fun leftoversPool(
            channel: LineupChannel,
            snapshot: RuleSnapshot,
            claimed: Set<CanonicalId>,
        ): Pool = Leftovers.pool(snapshot, claimed, channel.ceiling)

        // Every opt-in recipe of the catalog, as resolved without collection hints and as the lineup holds it.
        private fun optInRules(): List<ChannelRules> =
            catalog.recipes.filter { it.optIn }.flatMap { recipe ->
                val resolved = RecipeResolver.resolve(recipe, lineup.serverId, lineup.utcOffsetMinutes, NO_NAMES)
                val held = lineup.channels.firstOrNull { it.id == LineupIds.starter(recipe.key) }?.let(::rulesOf)
                listOfNotNull(resolved, held)
            }

        private fun hiddenRules(): List<ChannelRules> {
            val hidden = lineup.channels.filter { it.state == EngineState.UserHidden }
            return hidden.mapNotNull(::rulesOf)
        }
    }

    private companion object {
        val NO_NAMES = CollectionNames(emptyMap())

        /** A grown-up leftovers number, for the stand-in recipe of a retired leftovers key. */
        const val RETIRED_NUMBER = 199
    }
}

// A leftovers channel's rating ceiling.
private val LineupChannel.ceiling: String? get() = (source as? ChannelSource.Leftovers)?.maxRating

private fun rulesOf(channel: LineupChannel): ChannelRules? =
    when (val source = channel.source) {
        is ChannelSource.Starter -> source.rules
        is ChannelSource.Custom -> source.rules
        is ChannelSource.Leftovers, is ChannelSource.Unusable -> null
    }

/** Why a day is empty, in [HiddenReason]'s precedence; null when [day] has something. */
internal fun hiddenReason(
    pool: Pool,
    day: List<ScheduleItem>,
): HiddenReason? {
    val counts = pool.counts
    val unresolved = counts.unresolvedSources.isNotEmpty() || counts.unresolvedExclusions.isNotEmpty()
    return when {
        day.isNotEmpty() -> null
        !counts.collectionsSynced && unresolved -> HiddenReason.CollectionsNotSynced
        pool.items.size + counts.excluded + counts.blockedByCeiling == 0 -> HiddenReason.NoMatches
        pool.items.isEmpty() && counts.blockedByCeiling == 0 -> HiddenReason.AllExcluded
        pool.items.isEmpty() -> HiddenReason.AllBlockedByCeiling
        else -> HiddenReason.EmptyToday
    }
}
