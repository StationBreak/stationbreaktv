// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.engine.GrownUpHold
import tv.stationbreak.core.engine.Pool
import tv.stationbreak.core.engine.PoolCounts
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.HouseholdSources
import tv.stationbreak.core.engine.lineup.KidStatus.basePolicyOf
import tv.stationbreak.core.engine.lineup.KidStatus.policyOf
import tv.stationbreak.core.engine.lineup.Leftovers
import tv.stationbreak.core.engine.lineup.LeftoversRecipe
import tv.stationbreak.core.engine.lineup.LibraryNames
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.householdFor
import tv.stationbreak.core.engine.lineup.rulesFor
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
 * and then they are two snapshots. [dayStartMs] is when the day starts: the rules of a starter and the "Use my
 * collections" flag are those of that day, so what the leftovers withhold is worked out per day start. The snapshot
 * itself is read by [cutoffMs] and [extras] alone.
 */
internal data class Cut(
    val cutoffMs: Long,
    val extras: Boolean,
    val dayStartMs: Long,
) : Comparable<Cut> {
    override fun compareTo(other: Cut): Int = compareValuesBy(this, other, Cut::cutoffMs, Cut::extras, Cut::dayStartMs)
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

    /** When the day starts, in milliseconds since 1970. */
    val dayStartMs: Long get() = Epochs.epochStart(settings, n)

    /** The snapshot the day is built from. */
    val cut: Cut get() = Cut(cutoffMs, extras, dayStartMs)
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
 * the hold keeps off it is not offered to the grown-up channels either.
 *
 * Any other starter, edited or not, and the grown-up leftovers are grown-up (ADR-0020, grown-up-hold-2): they drop
 * what a kid channel could take ([GrownUpHold.dropsFromGrownUp]), with their own cache key. A custom channel is open,
 * and so is the grown-up leftovers while no kid leftovers channel is on. What the kid hold refuses stays on the
 * grown-up side. [KidStatus.policyOf] decides all of this.
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
     * A channel that leaves the lineup at or before [atMs] ([LineupChannel.leavesAtMs]) is no longer on. A starter's
     * settings are those of the rules for the day that holds [atMs] (`rulesFor`), not today's newest.
     */
    fun scheduled(
        lineup: Lineup,
        atMs: Long,
        extrasFromMs: Long?,
    ): List<Scheduled> =
        lineup.channels
            .filter { it.state == ChannelState.On && it.number != null && it.leavesAtMs.isAfter(atMs) }
            .sortedWith(CHANNEL_ORDER)
            .mapNotNull { channel ->
                settingsOf(channel, lineup, atMs)?.let {
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
     *   passes the account's value): it decides, with the day's start, which days are filtered;
     * - the day-boundary inputs (spec 23): "Use my collections" (`household`), the curation basis (`basis`), each
     *   channel's `leavesAt`, and a starter's previous rules and their boundary (inside its source). Not
     *   `chosenByViewer`, `shelfReason` or `viewerChoicesKnown`: no day depends on them.
     *
     * Numbers and names that change neither are left out: a renumber or a swap on one side of the Kids band keeps every
     * built day. The day's channel and name are read from the current lineup ([rebound]).
     */
    fun inputsOf(
        lineup: Lineup,
        extrasFromMs: Long?,
    ): String {
        val channels = lineup.channels.sortedBy { it.id.value }.map { channelInputs(it, policyOf(it, lineup)) }
        val claims = claimOrder(lineup).map { JsonPrimitive(it.id.value) }
        val inputs =
            JsonObject(
                linkedMapOf(
                    "server" to JsonPrimitive(lineup.serverId.value),
                    "offset" to JsonPrimitive(lineup.utcOffsetMinutes),
                    "channels" to JsonArray(channels),
                    "claims" to JsonArray(claims),
                    "extrasFrom" to JsonPrimitive(extrasFromMs),
                    "household" to JsonPrimitive(lineup.useMyCollections),
                    "basis" to basisInputs(lineup),
                ),
            )
        return sha256Hex(inputs.toString().encodeToByteArray())
    }

    // The curation basis in [inputsOf]: what the days before its boundary were worked out from.
    private fun basisInputs(lineup: Lineup): JsonElement {
        val basis = lineup.curationBasis ?: return JsonNull
        return JsonObject(
            linkedMapOf(
                "fromMs" to JsonPrimitive(basis.fromMs),
                "household" to JsonPrimitive(basis.household),
                "on" to
                    JsonArray(
                        basis.on
                            .map { it.value }
                            .sorted()
                            .map { JsonPrimitive(it) },
                    ),
            ),
        )
    }

    // What one channel contributes to [inputsOf]: the one definition of "its days would change".
    private fun channelInputs(
        channel: LineupChannel,
        policy: PoolPolicy,
    ): JsonObject =
        JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive(channel.id.value),
                "state" to JsonPrimitive(channel.state.name),
                "ceiling" to JsonPrimitive(ceilings.of(channel)),
                "policy" to JsonPrimitive(nameOf(policy)),
                "leavesAt" to JsonPrimitive(channel.leavesAtMs),
                "source" to LineupCodec.sourceOf(unnamed(channel.source)),
            ),
        )

    /**
     * [channel]'s part of [inputsOf] in [lineup], as a digest: it changes whenever this channel's own inputs to the
     * days change (a new play order, a reshuffle, other sources or filters, its pool policy in [lineup]; never a name
     * or a number that changes no pool). A leftovers channel's days also depend on what the other channels claim, so
     * the guide compares the whole list of keys.
     */
    fun playKeyOf(
        channel: LineupChannel,
        lineup: Lineup,
    ): PlayKey {
        val inputs = channelInputs(channel, policyOf(channel, lineup)).toString()
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
     * How many items of the snapshots are eligible for a day of a switched-on channel and yet air nowhere: in no
     * channel's epoch pool (before the trim window), and not held back on purpose (opt-in recipes, hidden channels).
     * Not counted here: titles a grown-up pool drops ([turnedAwayByBothHolds]), which are counted apart. Zero for
     * everything else while the leftovers channels are on, and normally zero for those too.
     */
    fun airingNowhere(
        lineup: Lineup,
        scheduled: List<Scheduled>,
        snapshots: Map<Long, RuleSnapshot>,
        filteredSnapshots: Map<Long, RuleSnapshot> = emptyMap(),
    ): Int = unaired(lineup, scheduled, snapshots, filteredSnapshots).first

    /**
     * How many of the items [airingNowhere] would count that a grown-up pool drops ([GrownUpHold.dropsFromGrownUp]):
     * made for kids, takeable by a kid channel, and aired nowhere. Under `grown-up-hold-2` (ADR-0020, option C) a
     * grown-up pool drops only what a kid channel could take, and the grown-up leftovers keeps everything while no kid
     * leftovers is on, so this is normally zero; it remains as a guard (it is not zero when, say, the grown-up
     * leftovers is off and no kid channel takes the title). A separate count, not part of [airingNowhere].
     */
    fun turnedAwayByBothHolds(
        lineup: Lineup,
        scheduled: List<Scheduled>,
        snapshots: Map<Long, RuleSnapshot>,
        filteredSnapshots: Map<Long, RuleSnapshot> = emptyMap(),
    ): Int = unaired(lineup, scheduled, snapshots, filteredSnapshots).second

    // The items that air nowhere, split: (kept by grown-up pools, dropped by them).
    private fun unaired(
        lineup: Lineup,
        scheduled: List<Scheduled>,
        snapshots: Map<Long, RuleSnapshot>,
        filteredSnapshots: Map<Long, RuleSnapshot>,
    ): Pair<Int, Int> {
        val build = Build(lineup, snapshots, filteredSnapshots)
        // Trimmed items stay claimed (plan Resolution 24): the count reads the epoch pools, not the windowed day pools.
        build.days(scheduled)
        val aired = scheduled.flatMapTo(HashSet()) { s -> build.epochPoolOf(s).map { it.id } }
        val nowhere = HashSet<CanonicalId>()
        val turnedAway = HashSet<CanonicalId>()
        for ((cut, group) in scheduled.groupBy { it.cut }) {
            val snapshot = build.snapshotOf(cut)
            val items = snapshot.items.map { it.item }
            val eligible =
                group.distinctBy { EpochPool.inputsOf(it.settings) }.flatMapTo(HashSet()) { s ->
                    EpochPool.build(s.settings, items, s.n).map { it.id }
                }
            val left = eligible - aired - build.withheld(cut) - build.hiddenLeftovers(cut)
            for (entry in snapshot.items) {
                val id = entry.item.id
                if (id !in left) continue
                val seriesId = entry.item.seriesId
                val series = if (seriesId == null) null else snapshot.series[seriesId]
                if (GrownUpHold.dropsFromGrownUp(entry, series)) turnedAway += id else nowhere += id
            }
        }
        return nowhere.size to turnedAway.size
    }

    // A starter's settings are those of the rules for the day that holds [atMs]. Its rules and previous rules share one
    // timing, so the day is the same under either; the rules for it then decide the rest.
    private fun settingsOf(
        channel: LineupChannel,
        lineup: Lineup,
        atMs: Long,
    ): SchedulerSettings? =
        try {
            when (val source = channel.source) {
                is ChannelSource.Starter -> starterSettings(source, atMs)
                is ChannelSource.Custom -> source.rules.toSchedulerSettings()
                is ChannelSource.Leftovers -> leftoversSettings(source, lineup)
                is ChannelSource.Unusable -> null
            }
        } catch (_: IllegalArgumentException) {
            // Settings the scheduler refuses: the channel is not scheduled rather than the whole lineup failing.
            null
        }

    private fun starterSettings(
        source: ChannelSource.Starter,
        atMs: Long,
    ): SchedulerSettings {
        val current = source.rules.toSchedulerSettings()
        val start = Epochs.epochStart(current, Epochs.epochNumber(current, atMs))
        return source.rulesFor(start).toSchedulerSettings()
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

        // The rules a channel airs on the day that starts at [dayStartMs]: a starter's rules for that day, and for an
        // unedited one, without the household collections when the flag in force that day is off (null: nothing is
        // left, a day with no source). A custom channel's rules are its own.
        private fun rulesAt(
            channel: LineupChannel,
            dayStartMs: Long,
            snapshot: RuleSnapshot,
        ): ChannelRules? =
            when (val source = channel.source) {
                is ChannelSource.Starter -> {
                    val rules = source.rulesFor(dayStartMs)
                    if (source.edited || lineup.householdFor(dayStartMs)) {
                        rules
                    } else {
                        HouseholdSources.without(rules, snapshot)
                    }
                }

                is ChannelSource.Custom -> {
                    source.rules
                }

                is ChannelSource.Leftovers, is ChannelSource.Unusable -> {
                    null
                }
            }

        private fun ruledDay(s: Scheduled): ChannelDay {
            val snapshot = snapshotOf(s.cut)
            val rules = rulesAt(s.channel, s.dayStartMs, snapshot) ?: return householdOffDay(s, snapshot)
            val ruled =
                ruledDayOf(ceilings, rules, s.channel, snapshot, s.settings, s.n) { r, policy ->
                    evaluate(r, s.cut, policy)
                }
            epochPools[s] = ruled.day
            // The window keeps at least one episode of each series, so emptiness (and its reason) is unchanged by it.
            return dayOf(s, ruled.day, ruled.films, hiddenReason(ruled.pool, ruled.day), keepsFor(s))
        }

        // An unedited starter whose sources are all household collections, on a day the flag is off: nothing to air.
        private fun householdOffDay(
            s: Scheduled,
            snapshot: RuleSnapshot,
        ): ChannelDay {
            epochPools[s] = emptyList()
            // Without a full collection pass the names are not known yet, so the emptiness may be only that.
            val synced = snapshot.collectionsSynced
            val reason = if (synced) HiddenReason.HouseholdCollectionsOff else HiddenReason.CollectionsNotSynced
            return dayOf(s, emptyList(), NO_FILMS, reason, TitleKeeps.NONE)
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
                val rules = optInRules(cut) + hiddenRules(cut)
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
        ): Pool = Leftovers.pool(snapshot, claimed, ceilings.of(channel), policyOf(channel, lineup))

        // Every opt-in recipe of the catalog, as resolved against the cut's snapshot (its collection names and library
        // names, as the planner resolves them) and as the lineup holds it. Each is evaluated under its policy: the
        // resolved recipe by its recipe, the lineup's channel by its channel. The ceiling is as today.
        private fun optInRules(cut: Cut): List<PolicyRules> {
            val snapshot = snapshotOf(cut)
            val collections = collectionNamesOf(snapshot)
            val names = LibraryNames(snapshot, collections)
            return catalog.recipes.filter { it.optIn }.flatMap { recipe ->
                val offset = lineup.utcOffsetMinutes
                val resolved = RecipeResolver.resolve(recipe, lineup.serverId, offset, collections, names)
                val inLineup = lineup.channels.firstOrNull { it.id == LineupIds.starter(recipe.key) }
                // The flag in force on the day counts here too: an opt-in channel withholds what it would air.
                val household = lineup.householdFor(cut.dayStartMs)
                val resolvedForDay = if (household) resolved else HouseholdSources.without(resolved, snapshot)
                listOfNotNull(
                    resolvedForDay?.let { PolicyRules(it, policyOf(recipe)) },
                    inLineup?.let { rulesAt(it, cut.dayStartMs, snapshot) }?.let {
                        PolicyRules(it, basePolicyOf(inLineup))
                    },
                )
            }
        }

        // A hidden channel is evaluated as it would air if shown: under its scheduled ceiling and its own policy.
        private fun hiddenRules(cut: Cut): List<PolicyRules> {
            val snapshot = snapshotOf(cut)
            return lineup.channels.filter { it.state == ChannelState.UserHidden }.mapNotNull { channel ->
                rulesAt(channel, cut.dayStartMs, snapshot)?.let { ceilings.policyRules(it, channel) }
            }
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
        is ChannelSource.Starter -> source.mapRules { it.copy(name = null) }
        is ChannelSource.Custom -> source.copy(rules = source.rules.copy(name = null))
        is ChannelSource.Leftovers, is ChannelSource.Unusable -> source
    }

// Whether a channel with [this] leave time is still on at [atMs]: it has none, or it is after [atMs].
private fun Long?.isAfter(atMs: Long): Boolean = this == null || this > atMs

/** Why a day is empty, in [HiddenReason]'s precedence; null when [day] has something. */
internal fun hiddenReason(
    pool: Pool,
    day: List<ScheduleItem>,
): HiddenReason? {
    val counts = pool.counts
    val unresolved = counts.unresolvedSources.isNotEmpty() || counts.unresolvedExclusions.isNotEmpty()
    val matched =
        pool.items.size + counts.excluded + counts.heldOut + counts.madeForKidsOut + counts.filteredOut +
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
        counts.madeForKidsOut > 0 -> HiddenReason.ChildrensRatingsOnly
        counts.filteredOut > 0 -> HiddenReason.AllFilteredOut
        else -> HiddenReason.AllExcluded
    }

/**
 * The names of the collections [snapshot] holds, by ID, as of the snapshot's cut-off: what a themed recipe's collection
 * anchors and a recipe's hints are matched against, so a day build resolves them as the planner does. A collection with
 * no name has none here.
 */
internal fun collectionNamesOf(snapshot: RuleSnapshot): CollectionNames {
    val names = HashMap<CanonicalId, String>()
    for ((id, collection) in snapshot.collections) collection.name?.let { names[id] = it }
    return CollectionNames(names)
}
