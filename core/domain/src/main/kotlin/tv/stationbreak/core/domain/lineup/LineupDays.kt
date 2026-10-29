// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.Pool
import tv.stationbreak.core.engine.PoolCounts
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.KidStatus.isKidChannel
import tv.stationbreak.core.engine.lineup.KidStatus.policyOf
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
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.scheduler.Day
import tv.stationbreak.core.scheduler.EpochPool
import tv.stationbreak.core.scheduler.Epochs
import tv.stationbreak.core.scheduler.buildDay
import kotlin.time.TimeSource

/**
 * A switched-on channel's broadcast day: its [settings], the epoch [n] and that day's [pool] (`EpochPool.build`), in
 * canonical-ID order. With `buildDay(settings, pool, n)` it is the input of the day's schedule. [hidden] is why the day
 * is empty, or null. [ceilingRank] is the tier of the ceiling the channel is scheduled under, or null for none.
 */
@Suppress("LongParameterList") // The day's fixed facts, one for one.
internal class ChannelDay(
    val channel: LineupChannel,
    val name: String,
    val settings: SchedulerSettings,
    val n: Long,
    val pool: List<ScheduleItem>,
    val hidden: HiddenReason?,
    val ceilingRank: Int?,
    laidOut: (n: Long, ms: Long) -> Unit = { _, _ -> },
) {
    /** When the day starts, in milliseconds since 1970. */
    val epochStartMs: Long get() = Epochs.epochStart(settings, n)

    /** The built schedule of the day, worked out on first use and kept (the days are cached, so it is built once). */
    private val layout =
        lazy {
            val began = TimeSource.Monotonic.markNow()
            buildDay(settings, pool, n).also { laidOut(n, began.elapsedNow().inWholeMilliseconds) }
        }

    val day: Day get() = layout.value

    /** Whether the layout of the day has been worked out (reading [day] before then does that work). */
    val isLaidOut: Boolean get() = layout.isInitialized()
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
 * The rating ceiling a channel is scheduled under never comes from the stored lineup alone ([ceilingOf]): a leftovers
 * channel whose key is in the catalog takes the catalog's, an unedited starter of a kid-safe recipe takes the recipe's,
 * an edited one keeps its own but never above PG, and any channel numbered in the Kids band is held to PG at most.
 *
 * A kid channel ([KidStatus.isKidChannel]: numbered in the Kids band, or a key that was ever kid-safe) is also held to
 * made-for-kids content (spec 18): every pool it gets, ruled, leftovers and withheld, is evaluated with the hold, and
 * its cache key carries the policy. A hidden or opt-in kid channel withholds only what it would air if shown, so what
 * the hold keeps off it stays with the grown-up leftovers.
 *
 * Any other starter, edited or not, is grown-up (ADR-0020): it drops children's-rated items, with its own cache key.
 * A custom channel and the grown-up leftovers are open. [KidStatus.policyOf] decides all of this.
 *
 * Known edge, accepted: an unusable channel has no pool, so if the user hid one its content can reach the leftovers.
 */
internal class LineupDays(
    private val catalog: StarterCatalogView,
    private val laidOut: (n: Long, ms: Long) -> Unit = { _, _ -> },
) {
    /** The channels of [lineup] that are scheduled at [atMs], in number order. Reads nothing. */
    fun scheduled(
        lineup: Lineup,
        atMs: Long,
    ): List<Scheduled> =
        lineup.channels
            .filter { it.state == ChannelState.On && it.number != null }
            .sortedWith(CHANNEL_ORDER)
            .mapNotNull { channel ->
                settingsOf(channel, lineup)?.let { Scheduled(channel, it, Epochs.epochNumber(it, atMs)) }
            }

    /**
     * The days of [scheduled] (from [scheduled] for [lineup]), each from `snapshots[cutoffMs]`. [beforeChannel] is
     * called before each channel's day is worked out; a caller that has stopped needing the days throws from it.
     * [evaluated] is called once per `PoolEvaluator.evaluate` run, to count them.
     */
    fun days(
        lineup: Lineup,
        scheduled: List<Scheduled>,
        snapshots: Map<Long, RuleSnapshot>,
        beforeChannel: () -> Unit = {},
        evaluated: () -> Unit = {},
    ): List<ChannelDay> = Build(lineup, snapshots, evaluated).days(scheduled, beforeChannel)

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
        private val evaluated: () -> Unit = {},
    ) {
        private val withheldBy = HashMap<Long, Set<CanonicalId>>()

        // A pool depends only on the snapshot, the sources, the exclusions and the ceiling, so rules that agree on
        // those (a recipe as resolved and as the lineup has it, a hidden channel that is an unedited starter) are
        // evaluated once.
        private val evaluations = HashMap<EvaluationKey, Pool>()

        private fun evaluate(
            rules: ChannelRules,
            cutoff: Long,
            policy: PoolPolicy,
        ): Pool {
            val key = EvaluationKey(cutoff, rules.sources, rules.effectiveExclusions, rules.maxRating, policy)
            evaluations[key]?.let { return it }
            evaluated()
            return PoolEvaluator.evaluate(rules, snapshots.getValue(cutoff), policy).also { evaluations[key] = it }
        }

        // Leftovers channels that claim in turn: ceilinged first, then by number and ID. Shelved ones claim nothing.
        private val leftovers =
            lineup.channels
                .filter { it.source is ChannelSource.Leftovers && it.state != ChannelState.Shelf }
                .sortedWith(compareBy<LineupChannel> { ceilingOf(it) == null }.then(CHANNEL_ORDER))

        fun days(
            scheduled: List<Scheduled>,
            beforeChannel: () -> Unit = {},
        ): List<ChannelDay> {
            val ruled = HashMap<Scheduled, ChannelDay>()
            for (s in scheduled) {
                if (s.channel.source is ChannelSource.Leftovers) continue
                beforeChannel()
                ruled[s] = ruledDay(s)
            }
            val claimedByOn = ruled.values.flatMapTo(HashSet()) { day -> day.pool.map { it.id } }
            val byId = scheduled.associateBy { it.channel.id }
            // The leftovers channels claim in turn, so each one's day is worked out in claim order: the chain of
            // claims is walked once per cut-off, not again from the start for every channel. The days go back in the
            // order of [scheduled].
            val claimOrder = leftovers.withIndex().associate { (index, channel) -> channel.id to index }
            val chains = HashMap<Long, ClaimChain>()
            val leftoversDays = HashMap<Scheduled, ChannelDay>()
            val inClaimOrder =
                scheduled
                    .filter { it.channel.source is ChannelSource.Leftovers }
                    .sortedBy { claimOrder[it.channel.id] ?: leftovers.size }
            for (s in inClaimOrder) {
                beforeChannel()
                val chain = chains.getOrPut(s.cutoffMs) { ClaimChain(s.cutoffMs, claimedByOn) }
                leftoversDays[s] = leftoversDay(s, chain, claimOrder[s.channel.id] ?: leftovers.size, byId)
            }
            return scheduled.map { checkNotNull(ruled[it] ?: leftoversDays[it]) }
        }

        /** What the leftovers channels walked so far have claimed from one cut-off's snapshot, and how far that is. */
        private inner class ClaimChain(
            val cutoffMs: Long,
            claimedByOn: Set<CanonicalId>,
        ) {
            val claimed: MutableSet<CanonicalId> = HashSet(claimedByOn).apply { addAll(withheld(cutoffMs)) }
            var next = 0
        }

        private fun ruledDay(s: Scheduled): ChannelDay {
            val rules = checkNotNull(rulesOf(s.channel)).copy(maxRating = ceilingOf(s.channel))
            return dayOf(s, evaluate(rules, s.cutoffMs, policyOf(s.channel)))
        }

        private fun leftoversDay(
            s: Scheduled,
            chain: ClaimChain,
            position: Int,
            byId: Map<ChannelId, Scheduled>,
        ): ChannelDay {
            val snapshot = snapshots.getValue(chain.cutoffMs)
            // The channels before this one claim in turn, each against what the ones before it took.
            while (chain.next < position) {
                val before = leftovers[chain.next++]
                val pool = leftoversPool(before, snapshot, chain.claimed)
                val on = byId[before.id]
                val items = if (on != null) EpochPool.build(on.settings, pool.items, on.n) else pool.items
                items.mapTo(chain.claimed) { it.id }
            }
            return dayOf(s, leftoversPool(s.channel, snapshot, chain.claimed))
        }

        private fun dayOf(
            s: Scheduled,
            pool: Pool,
        ): ChannelDay {
            val day = EpochPool.build(s.settings, pool.items, s.n)
            val ceilingRank = RatingLadder.rankOf(ceilingOf(s.channel))
            val name = s.channel.displayName(catalog)
            return ChannelDay(s.channel, name, s.settings, s.n, day, hiddenReason(pool, day), ceilingRank, laidOut)
        }

        /** What no leftovers channel may air from the [cutoff]'s snapshot: opt-in and hidden channels' pools. */
        fun withheld(cutoff: Long): Set<CanonicalId> =
            withheldBy.getOrPut(cutoff) {
                val rules = optInRules() + hiddenRules()
                rules.flatMapTo(HashSet()) { (r, policy) -> evaluate(r, cutoff, policy).items.map { it.id } }
            }

        /** What the hidden leftovers channels would air from the [cutoff]'s snapshot: held back on purpose too. */
        fun hiddenLeftovers(cutoff: Long): Set<CanonicalId> {
            val snapshot = snapshots.getValue(cutoff)
            val claimed = withheld(cutoff)
            return leftovers.filter { it.state == ChannelState.UserHidden }.flatMapTo(HashSet()) { channel ->
                leftoversPool(channel, snapshot, claimed).items.map { it.id }
            }
        }

        private fun leftoversPool(
            channel: LineupChannel,
            snapshot: RuleSnapshot,
            claimed: Set<CanonicalId>,
        ): Pool = Leftovers.pool(snapshot, claimed, ceilingOf(channel), policyOf(channel))

        // Every opt-in recipe of the catalog, as resolved without collection hints and as the lineup holds it. Each is
        // evaluated under its policy: the resolved recipe by its recipe, the lineup's channel by its channel. The
        // ceiling is as today.
        private fun optInRules(): List<PolicyRules> =
            catalog.recipes.filter { it.optIn }.flatMap { recipe ->
                val resolved = RecipeResolver.resolve(recipe, lineup.serverId, lineup.utcOffsetMinutes, NO_NAMES)
                val inLineup = lineup.channels.firstOrNull { it.id == LineupIds.starter(recipe.key) }
                listOfNotNull(
                    PolicyRules(resolved, policyOf(recipe)),
                    inLineup?.let(::rulesOf)?.let { PolicyRules(it, policyOf(inLineup)) },
                )
            }

        // A hidden channel is evaluated as it would air if shown: under its scheduled ceiling and its own policy.
        private fun hiddenRules(): List<PolicyRules> =
            lineup.channels.filter { it.state == ChannelState.UserHidden }.mapNotNull { channel ->
                rulesOf(channel)?.let { PolicyRules(it.copy(maxRating = ceilingOf(channel)), policyOf(channel)) }
            }
    }

    /** Rules to evaluate, and the policy the pool is narrowed by (ADR-0019, ADR-0020). */
    private data class PolicyRules(
        val rules: ChannelRules,
        val policy: PoolPolicy,
    )

    /**
     * What a pool depends on, besides the snapshot of [cutoffMs]: the rules' sources, exclusions and ceiling, and
     * its policy, so channels under different policies with the same rules never share a pool.
     */
    private data class EvaluationKey(
        val cutoffMs: Long,
        val sources: List<Source>,
        val exclusions: List<Source>,
        val maxRating: String?,
        val policy: PoolPolicy,
    )

    /**
     * The rating ceiling [channel] is scheduled under. A leftovers channel takes the catalog's ceiling while its key is
     * in the catalog (the stored one only counts for a retired key). An unedited starter of a kid-safe recipe takes the
     * recipe's; an edited one keeps its stored ceiling. A channel whose key was ever kid-safe
     * (`StarterCatalog.EVER_KID_SAFE_KEYS`, whatever its number or edit), and any channel numbered in the Kids band, is
     * never above PG: a ceiling that is null or higher becomes PG.
     */
    private fun ceilingOf(channel: LineupChannel): String? {
        val stored = storedCeiling(channel.source)
        return if (isKidChannel(channel) && !atMostPg(stored)) KIDS_MAX_RATING else stored
    }

    // The ceiling before the kids' cap: the catalog's wherever it governs, else the stored one.
    private fun storedCeiling(source: ChannelSource): String? =
        when (source) {
            is ChannelSource.Leftovers -> leftoversCeiling(source)
            is ChannelSource.Starter -> starterCeiling(source)
            is ChannelSource.Custom -> source.rules.maxRating
            is ChannelSource.Unusable -> null
        }

    private fun starterCeiling(source: ChannelSource.Starter): String? {
        val recipeCeiling = catalog.recipes.firstOrNull { it.key == source.recipeKey }?.maxRating
        return if (recipeCeiling != null && !source.edited) recipeCeiling else source.rules.maxRating
    }

    // The catalog's ceiling, null included, while the key is in it; the stored one only for a retired key.
    private fun leftoversCeiling(source: ChannelSource.Leftovers): String? {
        val recipe = catalog.leftovers.firstOrNull { it.key == source.recipeKey }
        return if (recipe != null) recipe.maxRating else source.maxRating
    }

    private companion object {
        /** The most a kids' channel may air: the Kids band's maximum. */
        const val KIDS_MAX_RATING = "PG"

        val NO_NAMES = CollectionNames(emptyMap())

        /** A grown-up leftovers number, for the stand-in recipe of a retired leftovers key. */
        const val RETIRED_NUMBER = 199
    }
}

// True for a ceiling on the ladder at PG or lower; null (no ceiling) and unknown names are not.
private fun atMostPg(rating: String?): Boolean {
    val rank = RatingLadder.rankOf(rating)
    return rank != null && rank <= checkNotNull(RatingLadder.rankOf("PG"))
}

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
    val matched =
        pool.items.size + counts.excluded + counts.heldOut + counts.childrensRatingOut + counts.blockedByCeiling
    return when {
        day.isNotEmpty() -> null
        !counts.collectionsSynced && unresolved -> HiddenReason.CollectionsNotSynced
        matched == 0 -> HiddenReason.NoMatches
        pool.items.isEmpty() -> emptyPoolReason(counts)
        else -> HiddenReason.EmptyToday
    }
}

// Why a pool that matched something has nothing airable: the ceiling wins, then the holds, then the exclusions.
private fun emptyPoolReason(counts: PoolCounts): HiddenReason =
    when {
        counts.blockedByCeiling > 0 -> HiddenReason.AllBlockedByCeiling
        counts.heldOut > 0 -> HiddenReason.NothingMadeForKids
        counts.childrensRatingOut > 0 -> HiddenReason.ChildrensRatingsOnly
        else -> HiddenReason.AllExcluded
    }
