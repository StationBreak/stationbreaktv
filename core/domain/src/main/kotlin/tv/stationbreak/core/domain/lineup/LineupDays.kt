// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.engine.Pool
import tv.stationbreak.core.engine.PoolCounts
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
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
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.model.schedule.TitleKeeps
import tv.stationbreak.core.model.schedule.TitleWeights
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.scheduler.Day
import tv.stationbreak.core.scheduler.EpochPool
import tv.stationbreak.core.scheduler.Epochs
import tv.stationbreak.core.scheduler.buildDay
import kotlin.time.TimeSource

/**
 * A switched-on channel's broadcast day: its [settings], the epoch [n] and that day's [pool] (`EpochPool.build` and
 * then the trim window, `EpochPool.dayPool`), in canonical-ID order, and its [films] (empty unless the channel's
 * ordering reads collections). With
 * `buildDay(settings, pool, n, films, weights, NONE)` it is the input of the day's schedule; [pool] is what is kept on
 * disk. [hidden] is why
 * the day is empty, or null. [ceilingRank] is the tier of the ceiling the channel is scheduled under, or null for none.
 */
@Suppress("LongParameterList") // The day's fixed facts, one for one.
internal class ChannelDay private constructor(
    val channel: LineupChannel,
    val name: String,
    val settings: SchedulerSettings,
    val n: Long,
    val pool: List<ScheduleItem>,
    val films: FilmCollections,
    val hidden: HiddenReason?,
    val ceilingRank: Int?,
    // The built schedule of the day, worked out on first use and kept (the days are cached, so it is built once).
    private val layout: Lazy<Day>,
) {
    companion object {
        /**
         * A day made now: [pool] is the pool of the epoch ([EpochPool.build]) and must not be windowed already, and the
         * day's pool is that with the trim window of [keeps] applied, worked out here once (no layout is built). A day
         * restored from disk is made with [TitleKeeps.NONE]: what was kept is the windowed pool already, and NONE
         * leaves it as it is.
         */
        @Suppress("LongParameterList") // The day's fixed facts, one for one.
        fun of(
            channel: LineupChannel,
            name: String,
            settings: SchedulerSettings,
            n: Long,
            pool: List<ScheduleItem>,
            films: FilmCollections,
            hidden: HiddenReason?,
            ceilingRank: Int?,
            keeps: TitleKeeps,
            laidOut: (n: Long, ms: Long) -> Unit = { _, _ -> },
        ): ChannelDay {
            val dayPool = EpochPool.dayPool(pool, n, keeps)
            val layout = layoutOf(settings, dayPool, n, films, laidOut)
            return ChannelDay(channel, name, settings, n, dayPool, films, hidden, ceilingRank, layout)
        }
    }

    /** When the day starts, in milliseconds since 1970. */
    val epochStartMs: Long get() = Epochs.epochStart(settings, n)

    val day: Day get() = layout.value

    /** Whether the layout of the day has been worked out (reading [day] before then does that work). */
    val isLaidOut: Boolean get() = layout.isInitialized()

    /**
     * This day as [channel] of a later lineup shows it, at its number and under its [name]: the pool and the layout
     * (worked out or not) are this day's own, so nothing is built again. Only for a lineup whose days have the same
     * inputs ([LineupDays.inputsOf]).
     */
    fun shownAs(
        channel: LineupChannel,
        name: String,
    ): ChannelDay = ChannelDay(channel, name, settings, n, pool, films, hidden, ceilingRank, layout)
}

private fun layoutOf(
    settings: SchedulerSettings,
    pool: List<ScheduleItem>,
    n: Long,
    films: FilmCollections,
    laidOut: (n: Long, ms: Long) -> Unit,
): Lazy<Day> =
    lazy {
        val began = TimeSource.Monotonic.markNow()
        // The pool is already the day's pool (windowed when the day was made), so the layout builds it with no keeps.
        // The weights come with the curation wiring (Task 21).
        buildDay(
            settings,
            pool,
            n,
            films,
            TitleWeights.NONE,
            TitleKeeps.NONE,
        ).also { laidOut(n, began.elapsedNow().inWholeMilliseconds) }
    }

/** No film is in a collection: a leftovers channel's side map, and a channel whose ordering does not read one. */
internal val NO_FILMS: FilmCollections = FilmCollections.of(emptyMap())

/**
 * A snapshot a day is built from: the cut-off it is read at, and whether the extras filter was applied to it. Two days
 * of one cut-off can differ in the second (the filter starts at a day start, and days of other timing start elsewhere),
 * and then they are two snapshots.
 */
internal data class Cut(
    val cutoffMs: Long,
    val extras: Boolean,
) : Comparable<Cut> {
    override fun compareTo(other: Cut): Int = compareValuesBy(this, other, { it.cutoffMs }, { it.extras })
}

/**
 * A switched-on channel that can be scheduled, with the day that holds the moment asked about. [extras] is whether the
 * extras filter applies to the day (its start is at or after the lineup's boundary, [LineupDays.scheduled]).
 */
internal class Scheduled(
    val channel: LineupChannel,
    val settings: SchedulerSettings,
    val n: Long,
    val extras: Boolean = false,
) {
    /** The day's cut-off (`S - G`): the library snapshot the day is built from is read at it. */
    val cutoffMs: Long get() = Epochs.cutoff(settings, n)

    /** The snapshot the day is built from. */
    val cut: Cut get() = Cut(cutoffMs, extras)
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
 * The rating ceiling a channel is scheduled under never comes from the stored lineup alone ([Ceilings.of]): a leftovers
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
@Suppress("TooManyFunctions") // A lineup's days: worked out, restored, keyed and rebound together.
internal class LineupDays(
    private val catalog: StarterCatalogView,
    // The trim keeps of a channel's day: none until the curation wiring (Task 21) gives each channel its own. When it
    // does, they must also join [inputsOf] and the key of the persisted days, or a changed keep would reuse old days.
    private val keepsFor: (Scheduled) -> TitleKeeps = { TitleKeeps.NONE },
    private val laidOut: (n: Long, ms: Long) -> Unit = { _, _ -> },
) {
    private val ceilings = Ceilings(catalog)

    /**
     * The channels of [lineup] that are scheduled at [atMs], in number order. Reads nothing. A day is filtered of
     * extras when its start is at or after [extrasFromMs] (the account's `RefreshMeta.extrasFromMs`; null is never).
     */
    fun scheduled(
        lineup: Lineup,
        atMs: Long,
        extrasFromMs: Long?,
    ): List<Scheduled> =
        lineup.channels
            .filter { it.state == ChannelState.On && it.number != null }
            .sortedWith(CHANNEL_ORDER)
            .mapNotNull { channel ->
                settingsOf(channel, lineup)?.let {
                    val n = Epochs.epochNumber(it, atMs)
                    Scheduled(channel, it, n, extrasFromMs != null && Epochs.epochStart(it, n) >= extrasFromMs)
                }
            }

    /**
     * Everything of [lineup] that a day's pool and empty reason depend on, as a SHA-256 (hex), so that two lineups
     * whose days are the same give the same digest whatever their numbers and names. With the library snapshots, the
     * catalog and the moment (which day of each channel) it is the whole input of [days]:
     * - the server and the UTC offset, which every starter and leftovers channel's settings derive from;
     * - every channel, by ID: its state (on, hidden or on the shelf: which pools are aired, withheld or ignored), its
     *   source as stored but for the name in its rules (the rules, the edited flag, the leftovers key and ceiling, an
     *   unusable channel's JSON), the ceiling it is scheduled under ([Ceilings.of]) and its pool policy
     *   ([KidStatus.policyOf]). The last two are where a number counts: the Kids band holds a channel to PG and to
     *   made-for-kids content, so a move into or out of 1-99 that changes either changes the digest;
     * - the order the leftovers channels claim in ([claimOrder]: ceilinged first, then by number), the one other place
     *   numbers count;
     * - [extrasFromMs], when the extras filter starts to apply (the account's `RefreshMeta.extrasFromMs`; every caller
     *   passes the account's value): it decides, with the day's start, which days are filtered.
     *
     * Numbers and names that change neither are left out: a renumber or a swap on one side of the Kids band keeps every
     * built day. The day's channel and name are read from the current lineup ([rebound]).
     */
    fun inputsOf(
        lineup: Lineup,
        extrasFromMs: Long?,
    ): String {
        val channels = lineup.channels.sortedBy { it.id.value }.map { channelInputs(it) }
        val claims = claimOrder(lineup).map { JsonPrimitive(it.id.value) }
        val inputs =
            JsonObject(
                linkedMapOf(
                    "server" to JsonPrimitive(lineup.serverId.value),
                    "offset" to JsonPrimitive(lineup.utcOffsetMinutes),
                    "channels" to JsonArray(channels),
                    "claims" to JsonArray(claims),
                    "extrasFrom" to JsonPrimitive(extrasFromMs),
                ),
            )
        return sha256Hex(inputs.toString().encodeToByteArray())
    }

    // What one channel contributes to [inputsOf]: the one definition of "its days would change".
    private fun channelInputs(channel: LineupChannel): JsonObject =
        JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive(channel.id.value),
                "state" to JsonPrimitive(channel.state.name),
                "ceiling" to JsonPrimitive(ceilings.of(channel)),
                "policy" to JsonPrimitive(nameOf(policyOf(channel))),
                "source" to LineupCodec.sourceOf(unnamed(channel.source)),
            ),
        )

    /**
     * [channel]'s part of [inputsOf], as a digest: it changes whenever this channel's own inputs to the days change (a
     * new play order, a reshuffle, other sources or filters; never a name or a number that changes no pool). A
     * leftovers channel's days also depend on other channels, so the guide compares the whole list of keys.
     */
    fun playKeyOf(channel: LineupChannel): PlayKey {
        val inputs = channelInputs(channel).toString()
        return PlayKey(sha256Hex(inputs.encodeToByteArray()))
    }

    /** Whether [built] are already [scheduled]'s days as its lineup shows them: its very channels, in its order. */
    fun isShownAs(
        built: List<ChannelDay>,
        scheduled: List<Scheduled>,
    ): Boolean = built.size == scheduled.size && built.indices.all { built[it].channel === scheduled[it].channel }

    /**
     * [built], the days of a lineup with the same [inputsOf] as [scheduled]'s, as [scheduled] shows them: in its
     * order, each with its channel (so its number) and its name. [built] itself when it already is.
     */
    fun rebound(
        built: List<ChannelDay>,
        scheduled: List<Scheduled>,
    ): List<ChannelDay> {
        if (isShownAs(built, scheduled)) return built
        val byId = built.associateBy { it.channel.id }
        return scheduled.map { s ->
            val day = checkNotNull(byId[s.channel.id]) { "Days of the same inputs are of the same channels" }
            if (day.channel === s.channel) day else day.shownAs(s.channel, s.channel.displayName(catalog))
        }
    }

    /**
     * The days of [scheduled] (from [scheduled] for [lineup]), each from `snapshots[cutoffMs]`, or from
     * `filteredSnapshots[cutoffMs]` (the same snapshot with the extras taken out) for a day with [Scheduled.extras].
     * [beforeChannel] is called before each channel's day is worked out; a caller that has stopped needing the days
     * throws from it. [evaluated] is called once per `PoolEvaluator.evaluate` run, to count them.
     */
    @Suppress("LongParameterList") // The lineup, its days, both kinds of snapshot and the two hooks.
    fun days(
        lineup: Lineup,
        scheduled: List<Scheduled>,
        snapshots: Map<Long, RuleSnapshot>,
        filteredSnapshots: Map<Long, RuleSnapshot> = emptyMap(),
        beforeChannel: () -> Unit = {},
        evaluated: () -> Unit = {},
    ): List<ChannelDay> = Build(lineup, snapshots, filteredSnapshots, evaluated).days(scheduled, beforeChannel)

    /**
     * The day of [s] as a day kept on disk ([PersistedDays]) is made back into: [pool] is the day pool as it was kept
     * (already windowed, so it is made with [TitleKeeps.NONE]), [films] is its film-to-collection map and [hidden] its
     * empty reason. Everything else of the day comes from [s] and the catalog.
     */
    fun restore(
        s: Scheduled,
        pool: List<ScheduleItem>,
        films: FilmCollections,
        hidden: HiddenReason?,
    ): ChannelDay = dayOf(s, pool, films, hidden, TitleKeeps.NONE)

    // The day of [s] from the pool of its epoch: the day pool is that with the window of [keeps] applied, once.
    private fun dayOf(
        s: Scheduled,
        pool: List<ScheduleItem>,
        films: FilmCollections,
        hidden: HiddenReason?,
        keeps: TitleKeeps,
    ): ChannelDay {
        val ceilingRank = RatingLadder.rankOf(ceilings.of(s.channel))
        val name = s.channel.displayName(catalog)
        return ChannelDay.of(s.channel, name, s.settings, s.n, pool, films, hidden, ceilingRank, keeps, laidOut)
    }

    /**
     * How many items of the snapshots are eligible for a day of a switched-on channel and yet air nowhere: in no day
     * pool, and not held back on purpose (opt-in recipes, hidden channels). Zero while the leftovers channels are on.
     */
    fun airingNowhere(
        lineup: Lineup,
        scheduled: List<Scheduled>,
        snapshots: Map<Long, RuleSnapshot>,
        filteredSnapshots: Map<Long, RuleSnapshot> = emptyMap(),
    ): Int {
        val build = Build(lineup, snapshots, filteredSnapshots)
        // Trimmed items stay claimed (plan Resolution 24): the count reads the epoch pools, not the windowed day pools.
        build.days(scheduled)
        val aired = scheduled.flatMapTo(HashSet()) { s -> build.epochPoolOf(s).map { it.id } }
        val nowhere = HashSet<CanonicalId>()
        for ((cut, group) in scheduled.groupBy { it.cut }) {
            val items = build.snapshotOf(cut).items.map { it.item }
            val eligible =
                group.distinctBy { EpochPool.inputsOf(it.settings) }.flatMapTo(HashSet()) { s ->
                    EpochPool.build(s.settings, items, s.n).map { it.id }
                }
            nowhere += eligible - aired - build.withheld(cut) - build.hiddenLeftovers(cut)
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

    /**
     * One computation over one lineup and its snapshots. Pools are worked out once each.
     *
     * Any new read of the lineup here (a field of a channel or of the lineup, or a new place a number or a name counts)
     * must be added to [inputsOf] too, or two lineups that build different days would share the kept ones.
     * `DayInputsDifferentialTest` checks the two agree over every edit.
     */
    private inner class Build(
        private val lineup: Lineup,
        private val snapshots: Map<Long, RuleSnapshot>,
        private val filteredSnapshots: Map<Long, RuleSnapshot>,
        private val evaluated: () -> Unit = {},
    ) {
        private val withheldBy = HashMap<Cut, Set<CanonicalId>>()

        // The pool of each scheduled channel's epoch, before the trim window: what a channel claims.
        private val epochPools = HashMap<Scheduled, List<ScheduleItem>>()

        fun epochPoolOf(s: Scheduled): List<ScheduleItem> = epochPools.getValue(s)

        /** The snapshot of [cut]: with the extras taken out for a filtered day. */
        fun snapshotOf(cut: Cut): RuleSnapshot =
            if (cut.extras) filteredSnapshots.getValue(cut.cutoffMs) else snapshots.getValue(cut.cutoffMs)

        // A pool depends on the snapshot (its cut-off, and whether the extras are out of it), the sources, the
        // exclusions, the filters, the ceiling and the policy
        // (`PoolEvaluator`, "What a pool depends on"), so rules that agree on all of those (a recipe as resolved and as
        // the lineup has it, a hidden channel that is an unedited starter) are evaluated once.
        private val evaluations = HashMap<EvaluationKey, Pool>()

        private fun evaluate(
            rules: ChannelRules,
            cut: Cut,
            policy: PoolPolicy,
        ): Pool {
            val key =
                EvaluationKey(cut, rules.sources, rules.effectiveExclusions, rules.filters, rules.maxRating, policy)
            evaluations[key]?.let { return it }
            evaluated()
            return PoolEvaluator.evaluate(rules, snapshotOf(cut), policy).also { evaluations[key] = it }
        }

        private val leftovers = claimOrder(lineup)

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
            // Trimmed items stay claimed (plan Resolution 24): a channel that is on claims its whole epoch pool, not
            // its windowed day pool, so the leftovers never air what the window set aside, whatever the day. The claim
            // chain below does the same. Days restored from disk claim nothing (the leftovers' stored pools already
            // left these items out); only a build from the snapshots, here, makes claims.
            val claimedByOn = ruled.keys.flatMapTo(HashSet()) { s -> epochPoolOf(s).map { it.id } }
            val byId = scheduled.associateBy { it.channel.id }
            // The leftovers channels claim in turn, so each one's day is worked out in claim order: the chain of
            // claims is walked once per cut-off, not again from the start for every channel. The days go back in the
            // order of [scheduled].
            val claimOrder = leftovers.withIndex().associate { (index, channel) -> channel.id to index }
            val chains = HashMap<Cut, ClaimChain>()
            val leftoversDays = HashMap<Scheduled, ChannelDay>()
            val inClaimOrder =
                scheduled
                    .filter { it.channel.source is ChannelSource.Leftovers }
                    .sortedBy { claimOrder[it.channel.id] ?: leftovers.size }
            for (s in inClaimOrder) {
                beforeChannel()
                val chain = chains.getOrPut(s.cut) { ClaimChain(s.cut, claimedByOn) }
                leftoversDays[s] = leftoversDay(s, chain, claimOrder[s.channel.id] ?: leftovers.size, byId)
            }
            return scheduled.map { checkNotNull(ruled[it] ?: leftoversDays[it]) }
        }

        /** What the leftovers channels walked so far have claimed from one cut-off's snapshot, and how far that is. */
        private inner class ClaimChain(
            val cut: Cut,
            claimedByOn: Set<CanonicalId>,
        ) {
            val claimed: MutableSet<CanonicalId> = HashSet(claimedByOn).apply { addAll(withheld(cut)) }
            var next = 0
        }

        private fun ruledDay(s: Scheduled): ChannelDay {
            val rules = checkNotNull(rulesOf(s.channel))
            val snapshot = snapshotOf(s.cut)
            val ruled =
                ruledDayOf(ceilings, rules, s.channel, snapshot, s.settings, s.n) { r, policy ->
                    evaluate(r, s.cut, policy)
                }
            epochPools[s] = ruled.day
            // The window keeps at least one episode of each series, so emptiness (and its reason) is unchanged by it.
            return dayOf(s, ruled.day, ruled.films, hiddenReason(ruled.pool, ruled.day), keepsFor(s))
        }

        private fun leftoversDay(
            s: Scheduled,
            chain: ClaimChain,
            position: Int,
            byId: Map<ChannelId, Scheduled>,
        ): ChannelDay {
            val snapshot = snapshotOf(chain.cut)
            // The channels before this one claim in turn, each against what the ones before it took.
            while (chain.next < position) {
                val before = leftovers[chain.next++]
                val pool = leftoversPool(before, snapshot, chain.claimed)
                val on = byId[before.id]
                val items =
                    if (on != null) {
                        EpochPool.build(on.settings, pool.items, on.n)
                    } else {
                        pool.items
                    }
                items.mapTo(chain.claimed) { it.id }
            }
            val pool = leftoversPool(s.channel, snapshot, chain.claimed)
            val day = EpochPool.build(s.settings, pool.items, s.n)
            epochPools[s] = day
            return dayOf(s, day, NO_FILMS, hiddenReason(pool, day), keepsFor(s))
        }

        /** What no leftovers channel may air from the [cut]'s snapshot: opt-in and hidden channels' pools. */
        fun withheld(cut: Cut): Set<CanonicalId> =
            withheldBy.getOrPut(cut) {
                val rules = optInRules() + hiddenRules()
                rules.flatMapTo(HashSet()) { (r, policy) -> evaluate(r, cut, policy).items.map { it.id } }
            }

        /** What the hidden leftovers channels would air from the [cut]'s snapshot: held back on purpose too. */
        fun hiddenLeftovers(cut: Cut): Set<CanonicalId> {
            val snapshot = snapshotOf(cut)
            val claimed = withheld(cut)
            return leftovers.filter { it.state == ChannelState.UserHidden }.flatMapTo(HashSet()) { channel ->
                leftoversPool(channel, snapshot, claimed).items.map { it.id }
            }
        }

        private fun leftoversPool(
            channel: LineupChannel,
            snapshot: RuleSnapshot,
            claimed: Set<CanonicalId>,
        ): Pool = Leftovers.pool(snapshot, claimed, ceilings.of(channel), policyOf(channel))

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
                rulesOf(channel)?.let { ceilings.policyRules(it, channel) }
            }
    }

    /**
     * What a pool depends on, besides the snapshot of [cut] (its cut-off, and whether the extras are out of it, so a
     * filtered and an unfiltered day of one cut-off never share a pool): the rules' sources, exclusions, filters and
     * ceiling, and its policy, so channels under different policies with the same rules never share a pool.
     */
    private data class EvaluationKey(
        val cut: Cut,
        val sources: List<Source>,
        val exclusions: List<Source>,
        val filters: Filters?,
        val maxRating: String?,
        val policy: PoolPolicy,
    )

    // Leftovers channels that claim in turn: ceilinged first, then by number and ID. Shelved ones claim nothing.
    private fun claimOrder(lineup: Lineup): List<LineupChannel> =
        lineup.channels
            .filter { it.source is ChannelSource.Leftovers && it.state != ChannelState.Shelf }
            .sortedWith(compareBy<LineupChannel> { ceilings.of(it) == null }.then(CHANNEL_ORDER))

    private companion object {
        val NO_NAMES = CollectionNames(emptyMap())

        /** A grown-up leftovers number, for the stand-in recipe of a retired leftovers key. */
        const val RETIRED_NUMBER = 199
    }
}

// A policy's name in the days' key; a new policy fails the build here rather than sharing a name.
private fun nameOf(policy: PoolPolicy): String =
    when (policy) {
        PoolPolicy.Open -> "open"
        PoolPolicy.KidsOnly -> "kidsOnly"
        PoolPolicy.GrownUp -> "grownUp"
    }

// [source] without the name its rules may carry: a name never reaches a pool.
private fun unnamed(source: ChannelSource): ChannelSource =
    when (source) {
        is ChannelSource.Starter -> source.copy(rules = source.rules.copy(name = null))
        is ChannelSource.Custom -> source.copy(rules = source.rules.copy(name = null))
        is ChannelSource.Leftovers, is ChannelSource.Unusable -> source
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
        pool.items.size + counts.excluded + counts.heldOut + counts.childrensRatingOut + counts.filteredOut +
            counts.blockedByCeiling
    return when {
        day.isNotEmpty() -> null
        !counts.collectionsSynced && unresolved -> HiddenReason.CollectionsNotSynced
        matched == 0 -> HiddenReason.NoMatches
        pool.items.isEmpty() -> emptyPoolReason(counts)
        else -> HiddenReason.EmptyToday
    }
}

// Why a pool that matched something has nothing airable: the ceiling wins, then the holds, then the filters, then the
// exclusions.
private fun emptyPoolReason(counts: PoolCounts): HiddenReason =
    when {
        counts.blockedByCeiling > 0 -> HiddenReason.AllBlockedByCeiling
        counts.heldOut > 0 -> HiddenReason.NothingMadeForKids
        counts.childrensRatingOut > 0 -> HiddenReason.ChildrensRatingsOnly
        counts.filteredOut > 0 -> HiddenReason.AllFilteredOut
        else -> HiddenReason.AllExcluded
    }
