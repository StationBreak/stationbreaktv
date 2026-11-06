// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.engine.GroupCollections
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
import tv.stationbreak.core.engine.lineup.LineupDay
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.engine.lineup.curation.CuratedPool
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.lineup.curation.Homes
import tv.stationbreak.core.engine.lineup.householdFor
import tv.stationbreak.core.engine.lineup.isCuratedOn
import tv.stationbreak.core.engine.lineup.leavesBy
import tv.stationbreak.core.engine.lineup.rulesFor
import tv.stationbreak.core.engine.lineup.startsAfter
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
 * then the trim window, `EpochPool.dayPool`), in canonical-ID order, its [films] (empty unless the channel's ordering
 * reads collections) and its [weights] (a curated day's, spec 23; else none). With
 * `buildDay(settings, pool, n, films, weights)` it is the input of the day's schedule; [pool], [films] and
 * [weights] are what is kept on disk (the keeps never are: [pool] is already windowed). [hidden] is why the day is
 * empty, or null. [ceilingRank] is the tier of the ceiling the channel is scheduled under, or null for none.
 */
@Suppress("LongParameterList") // The day's fixed facts, one for one.
internal class ChannelDay private constructor(
    val channel: LineupChannel,
    val name: String,
    val settings: SchedulerSettings,
    val n: Long,
    val pool: List<ScheduleItem>,
    val films: FilmCollections,
    val weights: TitleWeights,
    val hidden: HiddenReason?,
    val ceilingRank: Int?,
    // The built schedule of the day, worked out on first use and kept (the days are cached, so it is built once).
    private val layout: Lazy<Day>,
) {
    companion object {
        /**
         * A day made now: [pool] is the pool of the epoch ([EpochPool.build]) and must not be windowed already, and the
         * day's pool is that with the trim window of [keeps] applied, worked out here once (no layout is built): the
         * one place the window is applied to a day of a lineup. A day restored from disk is made with
         * [TitleKeeps.NONE]: what was kept is the windowed pool already, and NONE leaves it as it is.
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
            weights: TitleWeights = TitleWeights.NONE,
            laidOut: (n: Long, ms: Long) -> Unit = { _, _ -> },
        ): ChannelDay {
            val dayPool = EpochPool.dayPool(pool, n, keeps)
            // The weights of the titles the windowed day holds only: what the days file is checked against on read.
            val dayWeights = weightsIn(weights, dayPool)
            val layout = layoutOf(settings, dayPool, n, films, dayWeights, laidOut)
            return ChannelDay(channel, name, settings, n, dayPool, films, dayWeights, hidden, ceilingRank, layout)
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
    ): ChannelDay = ChannelDay(channel, name, settings, n, pool, films, weights, hidden, ceilingRank, layout)
}

@Suppress("LongParameterList") // buildDay's inputs and the timing hook.
private fun layoutOf(
    settings: SchedulerSettings,
    pool: List<ScheduleItem>,
    n: Long,
    films: FilmCollections,
    weights: TitleWeights,
    laidOut: (n: Long, ms: Long) -> Unit,
): Lazy<Day> =
    lazy {
        val began = TimeSource.Monotonic.markNow()
        // The pool is already the day's pool (windowed when the day was made), so the layout builds it with no keeps.
        buildDay(settings, pool, n, films, weights).also {
            laidOut(n, began.elapsedNow().inWholeMilliseconds)
        }
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
 * The curation a day reads (spec 23): the curated on-set [on] (channel IDs, ascending) and the "Use my collections"
 * flag in force that day, [basis] when both are the lineup's basis (the day starts before `CurationBasis.fromMs`).
 * A value: with the snapshot of the day's cut it decides every curated pool, keep and weight of the day, so the day
 * build works the curation out once per cut and [DayCuration].
 */
internal data class DayCuration(
    val on: List<ChannelId>,
    val household: Boolean,
    val basis: Boolean,
) {
    /** This value as text, for the days' key. */
    fun text(): String = "on=${on.joinToString(",") { it.value }} household=$household basis=$basis"
}

/**
 * A switched-on channel that can be scheduled, with the day that holds the moment asked about. [extras] is whether the
 * extras filter applies to the day (its start is at or after the lineup's boundary, [LineupDays.scheduled]).
 * [curation] is the curation of the day for a curated starter (what it airs is cut from it) and for a leftovers
 * channel (what it claims from it), and null for any other channel: it travels with the day, and the days' key on
 * disk hashes it (`PersistedDays.keyOf`). [starting] is a channel switched on for a later boundary
 * (`LineupChannel.onSinceMs` after the moment asked about): its day is empty, and a leftovers channel that starts
 * later claims nothing either, as `leavesAtMs` in reverse.
 */
internal class Scheduled(
    val channel: LineupChannel,
    val settings: SchedulerSettings,
    val n: Long,
    val extras: Boolean = false,
    val curation: DayCuration? = null,
    val starting: Boolean = false,
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
 * day pool is its pool (`PoolEvaluator`) for the day (`EpochPool.build`). A curated starter's (spec 23: an unedited
 * starter whose rules for the day are programmed) is its pool as `Curation.curate` cuts it over the day's on-set (the
 * basis's before its boundary, else the curated starters scheduled that day), worked out once per cut-off and on-set,
 * with the trim window of its keeps and its weights; one switched on after the basis was taken airs its pool without
 * the titles homed on the basis channels, at weight 2. The curation only ever takes items out of pools the holds
 * already narrowed. A leftovers channel airs what is left after claiming, against its own snapshot:
 * - every item in the epoch pool of every switched-on channel that is neither curated nor a leftovers channel, and
 *   every item the day's curation claims: the basis on-set's before its boundary, scheduled today or not, and the
 *   scheduled curated starters' from it (A5, F5), so a switch in the middle of a day leaves the leftovers' day alone;
 * - every item of the pool of every opt-in catalog recipe, on or not, as the lineup's channel holds it for the day
 *   (`rulesFor`), and, unless that channel is an unedited starter of the recipe, also as the catalog resolves it;
 * - every item of the pool of every channel the user hid, under its rules for the day;
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
    private val laidOut: (n: Long, ms: Long) -> Unit = { _, _ -> },
    // Told when a leftovers channel's day is not its curated starters' day (never with the shipped timings).
    private val claimsMismatch: () -> Unit = {},
) {
    private val ceilings = Ceilings(catalog)
    private val recipes: Map<String, Recipe> by lazy { catalog.recipes.associateBy { it.key } }

    /**
     * The channels of [lineup] that are scheduled at [atMs], in number order. Reads nothing. A day is filtered of
     * extras when its start is at or after [extrasFromMs] (the account's `RefreshMeta.extrasFromMs`; null is never).
     * Whether a channel is on the air on that day is the engine's shared rule, decided by the day and never by the
     * moment asked about, so every lookup of one day agrees: a channel that leaves the lineup by the day's start
     * ([leavesBy]) is not scheduled; one that starts on a later day ([startsAfter], a planner switch-on for the
     * boundary) is [Scheduled.starting], with an empty day (`HiddenReason.EmptyToday`, so the guide leaves it out until
     * it starts); which starters are curated is [isCuratedOn], as the planner reads it. A starter's settings are those
     * of the rules for the day that holds [atMs] (`rulesFor`), not today's newest.
     */
    fun scheduled(
        lineup: Lineup,
        atMs: Long,
        extrasFromMs: Long?,
    ): List<Scheduled> {
        val ruled = ruledAt(lineup, atMs)
        // The curated starters on the air on each day start: the on-set of that day, unless the basis holds it.
        // A starter the planner switched on for a later boundary is not on the air on this day: it has a day with
        // nothing in it, and takes no part in that day's curation.
        val curatedOn = HashMap<Long, MutableList<ChannelId>>()
        for ((channel, settings) in ruled) {
            val day = lineupDayOf(settings, atMs)
            if (!channel.startsAfter(day) && isCurated(channel, day.startMs)) {
                curatedOn.getOrPut(day.startMs) { ArrayList() }.add(channel.id)
            }
        }
        val curations = HashMap<Long, DayCuration>()
        return ruled.map { (channel, settings) ->
            val n = Epochs.epochNumber(settings, atMs)
            val day = lineupDayOf(settings, atMs)
            val start = day.startMs
            val starting = channel.startsAfter(day)
            val reads = !starting && (channel.source is ChannelSource.Leftovers || isCurated(channel, start))
            val curation =
                if (reads) curations.getOrPut(start) { curationOf(lineup, start, curatedOn[start].orEmpty()) } else null
            Scheduled(channel, settings, n, extrasFromMs != null && start >= extrasFromMs, curation, starting)
        }
    }

    // The channels of [lineup] on, numbered, with settings, and not gone by the day of each that holds [atMs]
    // ([leavesBy]), in number order, each with its settings.
    private fun ruledAt(
        lineup: Lineup,
        atMs: Long,
    ): List<Pair<LineupChannel, SchedulerSettings>> {
        val ruled = ArrayList<Pair<LineupChannel, SchedulerSettings>>()
        for (channel in lineup.channels.sortedWith(CHANNEL_ORDER)) {
            val numberedOn = channel.state == ChannelState.On && channel.number != null
            val settings = if (numberedOn) settingsOf(channel, lineup, atMs) else null
            if (settings != null && !channel.leavesBy(lineupDayOf(settings, atMs))) ruled += channel to settings
        }
        return ruled
    }

    // [channel] curated on the day that starts at [dayStartMs]: the engine's shared rule with this catalog's recipes.
    private fun isCurated(
        channel: LineupChannel,
        dayStartMs: Long,
    ): Boolean = channel.isCuratedOn(dayStartMs, recipes.keys)

    // The curation of the day that starts at [dayStartMs]: the basis's on-set and flag before its boundary (A5),
    // else the curated starters scheduled that day ([scheduledOn]) and the flag in force.
    private fun curationOf(
        lineup: Lineup,
        dayStartMs: Long,
        scheduledOn: List<ChannelId>,
    ): DayCuration {
        val basis = lineup.curationBasis
        return if (basis != null && dayStartMs < basis.fromMs) {
            DayCuration(basis.on.sortedBy { it.value }, basis.household, basis = true)
        } else {
            DayCuration(scheduledOn.sortedBy { it.value }, lineup.householdFor(dayStartMs), basis = false)
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
     *   `chosenByViewer`, `shelfReason` or `viewerChoicesKnown`: no day depends on them;
     * - the catalog's version (`catalog`: a curated day reads its recipes) and the curation's (`curation`,
     *   `Curation.VERSION_NAME`). Which starters a day curates, and their rules and flag, follow from the channels, the
     *   basis and the day; the curation reads a starter's recipe number, never the channel's, for its ties.
     *
     * Numbers and names that change neither are left out: a renumber or a swap on one side of the Kids band keeps every
     * built day. The day's channel and name are read from the current lineup ([rebound]).
     */
    fun inputsOf(
        lineup: Lineup,
        extrasFromMs: Long?,
    ): String = sha256Hex(inputsJsonOf(lineup, extrasFromMs).toString().encodeToByteArray())

    /** What [inputsOf] hashes. */
    fun inputsJsonOf(
        lineup: Lineup,
        extrasFromMs: Long?,
    ): JsonObject {
        val channels =
            lineup.channels.sortedBy { it.id.value }.map {
                channelInputs(it, policyOf(it, lineup), householdInputs(it, lineup))
            }
        val claims = claimOrder(lineup).map { JsonPrimitive(it.id.value) }
        return JsonObject(
            linkedMapOf(
                "server" to JsonPrimitive(lineup.serverId.value),
                "offset" to JsonPrimitive(lineup.utcOffsetMinutes),
                "channels" to JsonArray(channels),
                "claims" to JsonArray(claims),
                "extrasFrom" to JsonPrimitive(extrasFromMs),
                "household" to JsonPrimitive(lineup.useMyCollections),
                "basis" to basisInputs(lineup),
                "catalog" to JsonPrimitive(catalog.version),
                "curation" to JsonPrimitive(Curation.VERSION_NAME),
            ),
        )
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

    // What one channel contributes to [inputsOf] and to its play key: the one definition of "its days would change".
    // [household] is an unedited starter's "Use my collections" part (its days follow the flag), null for any other.
    // [starting] is null for [inputsOf], which covers every day: the channel's leave time and when it started. For a
    // play key, which covers the one day played, it is whether the channel starts on a later day ([startsAfter]):
    // neither the leave time nor the moment of a switch-on changes that day (a leave at the boundary, or a switch off
    // and on again, moves no key until the boundary).
    private fun channelInputs(
        channel: LineupChannel,
        policy: PoolPolicy,
        household: JsonElement?,
        starting: Boolean? = null,
    ): JsonObject {
        val fields =
            linkedMapOf<String, JsonElement>(
                "id" to JsonPrimitive(channel.id.value),
                "state" to JsonPrimitive(channel.state.name),
                "ceiling" to JsonPrimitive(ceilings.of(channel)),
                "policy" to JsonPrimitive(nameOf(policy)),
            )
        if (starting == null) fields["leavesAt"] = JsonPrimitive(channel.leavesAtMs)
        fields["source"] = LineupCodec.sourceOf(unnamed(channel.source))
        // When a channel on the air started, or starts (a later boundary): its days before then are empty.
        if (channel.state == ChannelState.On) {
            fields[if (starting == null) "onSince" else "starting"] =
                if (starting == null) JsonPrimitive(channel.onSinceMs) else JsonPrimitive(starting)
        }
        if (household != null) fields["household"] = household
        return JsonObject(fields)
    }

    // An unedited starter's household part of [inputsOf]: the flag, and the flag before the basis's boundary.
    private fun householdInputs(
        channel: LineupChannel,
        lineup: Lineup,
    ): JsonElement? {
        if (!channel.isUneditedStarter()) return null
        val basis = lineup.curationBasis
        return JsonObject(
            linkedMapOf(
                "use" to JsonPrimitive(lineup.useMyCollections),
                "basisFromMs" to JsonPrimitive(basis?.fromMs),
                "basisHousehold" to JsonPrimitive(basis?.household),
            ),
        )
    }

    /**
     * [channel]'s play key in [lineup] for the day being played at [atMs], as a digest: it changes whenever this
     * channel's own inputs to that day change (a new play order, a reshuffle, other sources or filters, its pool policy
     * in [lineup]; never a name or a number that changes no pool). It holds only what changes that day: a starter's
     * rules in force that day (`rulesFor`), so new rules waiting for the boundary move it at the boundary; whether the
     * channel starts on a later day, never its leave time (a starter leaving at the boundary keeps its key until
     * then) or the moment it was switched on. An unedited starter's key holds the "Use my collections" flag in force
     * that day, never the flag set for later days; a curated starter's also holds the curation in force that day (F8):
     * the basis's on-set and flag while its boundary is ahead, else the curated on-set, with each member's rules for
     * the day. So a switch, a leave or a flag change in the middle of a day, which waits for the boundary, moves no
     * key until then; at the boundary the background roll writes a new revision and the keys are worked out for the
     * new day. A leftovers channel's days also depend on what the other channels claim, so the guide compares the
     * whole list of keys.
     */
    fun playKeyOf(
        channel: LineupChannel,
        lineup: Lineup,
        atMs: Long,
    ): PlayKey = PlayKeys(lineup, atMs).of(channel)

    /**
     * [playKeyOf] of every channel of [lineup] at [atMs], with what they share worked out once. At a boundary the keys
     * follow the days when the lineup does: the background roll writes a new revision (the basis and past previous
     * rules cleared), and a reader caches keys per revision and day.
     */
    fun playKeysOf(
        lineup: Lineup,
        atMs: Long,
    ): Map<ChannelId, PlayKey> {
        val keys = PlayKeys(lineup, atMs)
        return lineup.channels.associate { it.id to keys.of(it) }
    }

    // The play keys of one lineup at one moment: the scheduled channels and each day's curation part, once.
    private inner class PlayKeys(
        private val lineup: Lineup,
        private val atMs: Long,
    ) {
        private val scheduled by lazy { scheduled(lineup, atMs, null) }
        private val curations = HashMap<Long, String>()

        fun of(channel: LineupChannel): PlayKey {
            val day = dayOf(channel)
            val start = day?.first?.takeIf { channel.isUneditedStarter() }
            // The flag in force today only: a toggle waits for the boundary, where the roll's new revision moves the
            // key; never the basis's boundary itself, which a switch elsewhere sets.
            val household = start?.let { JsonObject(linkedMapOf("today" to JsonPrimitive(lineup.householdFor(it)))) }
            // A starter's rules as in force on the day played: a change waiting for the boundary moves no key today.
            val source = channel.source
            val forDay =
                if (day != null && source is ChannelSource.Starter) {
                    channel.copy(source = source.withRulesNow(source.rulesFor(day.first)))
                } else {
                    channel
                }
            // The policy of the day played: a kids leftovers channel that starts later is off for it (#623).
            val policy =
                day?.let { policyOf(channel, lineup, LineupDay.startingAt(it.first)) } ?: policyOf(channel, lineup)
            val inputs = channelInputs(forDay, policy, household, day?.second ?: false)
            val curated = day != null && !day.second && isCurated(channel, day.first)
            val fields = if (curated) JsonObject(inputs + ("curation" to curationPart(checkNotNull(start)))) else inputs
            return PlayKey(sha256Hex(fields.toString().encodeToByteArray()))
        }

        // The start of the day of [channel] that holds [atMs], and whether it starts on a later day; null when its
        // settings are refused.
        private fun dayOf(channel: LineupChannel): Pair<Long, Boolean>? {
            val settings = settingsOf(channel, lineup, atMs) ?: return null
            val day = lineupDayOf(settings, atMs)
            return day.startMs to channel.startsAfter(day)
        }

        // A member of the day's on-set in the curation part: its ID, its rules for the day, and (as [channelInputs])
        // the ceiling and pool policy it is curated under; a member no longer in the lineup by its ID alone.
        private fun memberPart(
            id: ChannelId,
            channel: LineupChannel?,
            start: Long,
        ): JsonObject {
            val source = channel?.source as? ChannelSource.Starter
            val fields = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id.value))
            if (channel != null && source != null) {
                fields["rules"] = LineupCodec.sourceOf(unnamed(source.withRulesNow(source.rulesFor(start))))
                fields["ceiling"] = JsonPrimitive(ceilings.of(channel))
                fields["policy"] = JsonPrimitive(nameOf(policyOf(channel, lineup)))
            }
            return JsonObject(fields)
        }

        // The curated starters scheduled on the day that starts at [start].
        private fun curatedOn(start: Long): List<ChannelId> =
            scheduled.filter { it.dayStartMs == start && !it.starting && isCurated(it.channel, start) }.map {
                it.channel.id
            }

        // The curation in force on the day that starts at [start]: its on-set, each member's rules for the day (a
        // member no longer in the lineup by its ID alone), and the flag. One digest per day start.
        private fun curationPart(start: Long): JsonPrimitive {
            val digest =
                curations.getOrPut(start) {
                    val curation = curationOf(lineup, start, curatedOn(start))
                    val byId = lineup.channels.associateBy { it.id }
                    val members = curation.on.map { id -> memberPart(id, byId[id], start) }
                    val part =
                        JsonObject(
                            linkedMapOf(
                                "on" to JsonArray(members),
                                "household" to JsonPrimitive(curation.household),
                                "version" to JsonPrimitive(Curation.VERSION_NAME),
                            ),
                        )
                    sha256Hex(part.toString().encodeToByteArray())
                }
            return JsonPrimitive(digest)
        }
    }

    /**
     * Everything that decides one scheduled channel's built day, besides the library snapshot of its cut-off (#589).
     * Two equal inputs, built from the same library state, give the same [ChannelDay] (name and number apart: they are
     * [rebound]). It is deliberately finer than [inputsOf], which covers every day of the lineup: it holds what the
     * channel's own day reads on the day played, so a lineup change that moves nothing today (a catalog swap's rules
     * waiting for the boundary, a channel starting later) leaves it equal. When in doubt it holds more, never less:
     * an unequal input only costs a build.
     * - [key] is the channel's play key for the day ([playKeyOf]): its rules in force that day, state, ceiling and
     *   pool policy, the household flag of that day, and for a curated starter the curation in force (every member's
     *   rules, ceiling and policy). [server] and [offset] are what every starter's and leftovers' settings derive from.
     * - [settings], [n], [cut], [starting] and [curation] are the day's [Scheduled] itself.
     * - [recipes] are the recipes of a curated starter's or leftovers channel's curation members (the curation reads
     *   a recipe's number, theme and kid flag), the channel's own first.
     * - [claims] is a leftovers channel's: what its day reads of every other channel (see [claimsOf]).
     */
    internal data class DayInput(
        val key: PlayKey,
        val server: CanonicalId,
        val offset: Int,
        val settings: SchedulerSettings,
        val n: Long,
        val cut: Cut,
        val starting: Boolean,
        val curation: DayCuration?,
        val recipes: List<Recipe?>,
        val claims: ClaimsInput?,
    )

    /**
     * What a leftovers channel's day reads of the rest of the lineup: [channels] (see [ClaimedChannel]), the order the
     * leftovers claim in ([claimOrder]), the household flag of the day (the opt-in recipes read it), and the catalog's
     * opt-in recipes ([optIn]: those not followed as an unedited starter are resolved from the catalog).
     */
    internal data class ClaimsInput(
        val channels: List<ClaimedChannel>,
        val claimOrder: List<ChannelId>,
        val household: Boolean,
        val optIn: List<Recipe>,
    )

    /** One channel as a leftovers day reads it: its play key, its rules for the day, and its scheduled day if any. */
    internal data class ClaimedChannel(
        val id: ChannelId,
        val key: PlayKey,
        val rules: ChannelRules?,
        val settings: SchedulerSettings?,
        val n: Long?,
        val cut: Cut?,
        val starting: Boolean?,
        val curation: DayCuration?,
    )

    /** The [DayInput] of every channel of [scheduled], for [lineup] at [atMs] (the moment [scheduled] is for). */
    fun dayInputsOf(
        lineup: Lineup,
        scheduled: List<Scheduled>,
        atMs: Long,
    ): Map<ChannelId, DayInput> {
        val keys = PlayKeys(lineup, atMs)
        val keyOf = HashMap<ChannelId, PlayKey>()
        val key = { channel: LineupChannel -> keyOf.getOrPut(channel.id) { keys.of(channel) } }
        val byId = lineup.channels.associateBy { it.id }
        val onAir = scheduled.associateBy { it.channel.id }
        val optIn = catalog.recipes.filter { it.optIn }
        val optInIds = optIn.mapTo(HashSet()) { LineupIds.starter(it.key) }
        val claiming = claimOrder(lineup).mapTo(HashSet()) { it.id }
        val out = LinkedHashMap<ChannelId, DayInput>()
        for (s in scheduled) {
            val members = s.curation?.on.orEmpty()
            val recipes =
                if (s.curation == null) emptyList() else (listOf(s.channel.id) + members).map { recipeOf(byId[it]) }
            val claims =
                if (s.channel.source !is ChannelSource.Leftovers) {
                    null
                } else {
                    // What a leftovers day reads of a channel: one that airs, one the user hid, an opt-in recipe's, a
                    // member of its curation, and every leftovers channel in the claim order (one that starts later
                    // claims nothing today, which its [ClaimedChannel.starting] says). Not one that is off, on the
                    // shelf, or starting later and none of those.
                    val relevant =
                        lineup.channels.filter {
                            val on = onAir[it.id]
                            (on != null && !on.starting) || it.state == ChannelState.UserHidden ||
                                it.id in optInIds || it.id in members || it.id in claiming
                        }
                    claimsOf(lineup, s, relevant.sortedBy { it.id.value }, onAir, key, optIn)
                }
            out[s.channel.id] =
                DayInput(
                    key(s.channel),
                    lineup.serverId,
                    lineup.utcOffsetMinutes,
                    s.settings,
                    s.n,
                    s.cut,
                    s.starting,
                    s.curation,
                    recipes,
                    claims,
                )
        }
        return out
    }

    // A leftovers day's reads of the rest of the lineup, as values ([ClaimsInput]).
    @Suppress("LongParameterList") // What the leftovers day reads, one for one.
    private fun claimsOf(
        lineup: Lineup,
        s: Scheduled,
        relevant: List<LineupChannel>,
        onAir: Map<ChannelId, Scheduled>,
        key: (LineupChannel) -> PlayKey,
        optIn: List<Recipe>,
    ): ClaimsInput {
        val channels =
            relevant.map {
                val on = onAir[it.id]
                val rules =
                    when (val source = it.source) {
                        is ChannelSource.Starter -> source.rulesFor(s.dayStartMs)
                        is ChannelSource.Custom -> source.rules
                        is ChannelSource.Leftovers, is ChannelSource.Unusable -> null
                    }?.copy(name = null)
                ClaimedChannel(
                    it.id,
                    key(it),
                    rules,
                    on?.settings,
                    on?.n,
                    on?.cut,
                    on?.starting,
                    on?.curation,
                )
            }
        return ClaimsInput(channels, claimOrder(lineup).map { it.id }, lineup.householdFor(s.dayStartMs), optIn)
    }

    private fun recipeOf(channel: LineupChannel?): Recipe? {
        val starter = channel?.source as? ChannelSource.Starter
        return starter?.let { recipes[it.recipeKey] }
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
     * throws from it. [finished] is told each day that was built (not a reused one) as soon as it is.
     * [evaluated] is called once per `PoolEvaluator.evaluate` run, to count them. A channel in
     * [reused] is given that day, as the channel [scheduled] shows it, and nothing of it is worked out: the caller
     * vouches that its [DayInput] and the library are those the day was built from (#589). A snapshot is needed only
     * for the cut of a day that is built, and for every cut when a leftovers channel's day is (its claims read them).
     */
    @Suppress("LongParameterList") // The lineup, its days, both kinds of snapshot and the two hooks.
    fun days(
        lineup: Lineup,
        scheduled: List<Scheduled>,
        snapshots: Map<Long, RuleSnapshot>,
        filteredSnapshots: Map<Long, RuleSnapshot> = emptyMap(),
        beforeChannel: () -> Unit = {},
        reused: Map<ChannelId, ChannelDay> = emptyMap(),
        finished: (ChannelDay) -> Unit = {},
        evaluated: () -> Unit = {},
    ): List<ChannelDay> {
        val build = Build(lineup, snapshots, filteredSnapshots, evaluated)
        return build.days(scheduled, beforeChannel, reused, finished)
    }

    /**
     * The day of [s] as a day kept on disk ([PersistedDays]) is made back into: [pool] is the day pool as it was kept
     * (already windowed, so it is made with [TitleKeeps.NONE]: the window is never applied twice), [films] is its
     * film-to-collection map, [hidden] its empty reason and [weights] its curated weights. Everything else of the day
     * comes from [s] and the catalog.
     */
    fun restore(
        s: Scheduled,
        pool: List<ScheduleItem>,
        films: FilmCollections,
        hidden: HiddenReason?,
        weights: TitleWeights = TitleWeights.NONE,
    ): ChannelDay = dayOf(s, pool, films, hidden, TitleKeeps.NONE, weights)

    // The day of [s] from the pool of its epoch: the day pool is that with the window of [keeps] applied, once.
    @Suppress("LongParameterList") // The day's facts, one for one.
    private fun dayOf(
        s: Scheduled,
        pool: List<ScheduleItem>,
        films: FilmCollections,
        hidden: HiddenReason?,
        keeps: TitleKeeps,
        weights: TitleWeights,
    ): ChannelDay {
        val ceilingRank = RatingLadder.rankOf(ceilings.of(s.channel))
        val name = s.channel.displayName(catalog)
        return ChannelDay.of(
            s.channel,
            name,
            s.settings,
            s.n,
            pool,
            films,
            hidden,
            ceilingRank,
            keeps,
            weights,
            laidOut,
        )
    }

    /**
     * How many items of the snapshots are eligible for a day of a switched-on channel and yet air nowhere: in no
     * channel's epoch pool (before the trim window), and not held back on purpose (opt-in recipes, hidden channels, and
     * until the basis's boundary what the basis on-set claims: a curated starter switched off today airs again
     * tomorrow).
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
            val curations = group.mapNotNull { it.curation }.distinct()
            val curated = curations.flatMapTo(HashSet()) { build.curated(cut, it).claimed }
            val left = eligible - aired - build.withheld(cut) - build.hiddenLeftovers(cut) - curated
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

    // The broadcast day of [settings] that holds [atMs], for the engine's shared day predicates.
    private fun lineupDayOf(
        settings: SchedulerSettings,
        atMs: Long,
    ): LineupDay {
        val n = Epochs.epochNumber(settings, atMs)
        return LineupDay(Epochs.epochStart(settings, n), Epochs.epochStart(settings, n + 1))
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
     * must be added to [inputsOf] AND to [DayInput] (or [ClaimsInput]) too, or two lineups that build different days
     * would share the kept ones, and a stale day taken over from an earlier build would be written to disk under the
     * new lineup's key. `DayInputsDifferentialTest` checks [inputsOf] over every edit; `DayInputReuseTest` checks
     * [DayInput] and drops each of its fields in turn to show a test fails for it.
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
            // Not the day start: two days of one cut-off with the same rules share a pool.
            val key =
                EvaluationKey(
                    cut.cutoffMs,
                    cut.extras,
                    rules.sources,
                    rules.effectiveExclusions,
                    rules.filters,
                    rules.maxRating,
                    policy,
                )
            evaluations[key]?.let { return it }
            evaluated()
            return PoolEvaluator.evaluate(rules, snapshotOf(cut), policy).also { evaluations[key] = it }
        }

        private val leftovers = claimOrder(lineup)

        // A day kept from an earlier build, as [s]'s channel shows it. Its pool stands for its epoch pool, which the
        // claims below read of a day that is not curated (no window is applied to it); a curated one claims through
        // its curation.
        private fun reuse(
            s: Scheduled,
            day: ChannelDay,
        ): ChannelDay {
            epochPools[s] = day.pool
            return if (day.channel === s.channel) day else day.shownAs(s.channel, s.channel.displayName(catalog))
        }

        private fun builtDay(s: Scheduled): ChannelDay = if (s.starting) startingDay(s) else ruledDay(s)

        fun days(
            scheduled: List<Scheduled>,
            beforeChannel: () -> Unit = {},
            reused: Map<ChannelId, ChannelDay> = emptyMap(),
            finished: (ChannelDay) -> Unit = {},
        ): List<ChannelDay> {
            val ruled = HashMap<Scheduled, ChannelDay>()
            for (s in scheduled.filter { it.channel.source !is ChannelSource.Leftovers }) {
                beforeChannel()
                ruled[s] = reused[s.channel.id]?.let { reuse(s, it) } ?: builtDay(s).also(finished)
            }
            // The leftovers claim exactly (F5): the epoch pool of every channel that is on and not curated, and per
            // cut the curated claims of the day's curation ([ClaimChain]). Trimmed items stay claimed (plan Resolution
            // 24): a channel claims its whole epoch pool, and a curated one all its members, not its windowed day pool,
            // so the leftovers never air what the window set aside, whatever the day. The claim chain below does the
            // same. Days restored from disk claim nothing (the leftovers' stored pools already left these items out);
            // only a build from the snapshots, here, makes claims.
            val claimedByOn = HashSet<CanonicalId>()
            for (s in ruled.keys) if (!s.isCuratedDay()) epochPoolOf(s).mapTo(claimedByOn) { it.id }
            val byId = scheduled.associateBy { it.channel.id }
            // The leftovers channels claim in turn, so each one's day is worked out in claim order: the chain of
            // claims is walked once per cut-off, not again from the start for every channel. The days go back in the
            // order of [scheduled].
            val claimOrder = leftovers.withIndex().associate { (index, channel) -> channel.id to index }
            val chains = HashMap<Pair<Cut, DayCuration?>, ClaimChain>()
            val leftoversDays = HashMap<Scheduled, ChannelDay>()
            val inClaimOrder =
                scheduled
                    .filter { it.channel.source is ChannelSource.Leftovers }
                    .sortedBy { claimOrder[it.channel.id] ?: leftovers.size }
            val curatedCuts = curatedCutsOf(ruled.keys)
            for (s in inClaimOrder) {
                beforeChannel()
                leftoversDays[s] =
                    reused[s.channel.id]?.let { reuse(s, it) } ?: if (s.starting) {
                        // Starts at a later boundary: an empty day today, and it takes no place in the chain (#620).
                        startingDay(s).also(finished)
                    } else {
                        val claims = claimsFor(s, curatedCuts)
                        val chain = chains.getOrPut(s.cut to claims?.second) { ClaimChain(s.cut, claims, claimedByOn) }
                        leftoversDay(s, chain, claimOrder[s.channel.id] ?: leftovers.size, byId).also(finished)
                    }
            }
            return scheduled.map { checkNotNull(ruled[it] ?: leftoversDays[it]) }
        }

        // The curation each cut's curated starters read: a leftovers channel of that cut claims from the same one.
        private fun curatedCutsOf(ruled: Collection<Scheduled>): Map<Cut, DayCuration> {
            val cuts = HashMap<Cut, DayCuration>()
            for (s in ruled) {
                val curation = s.curation
                if (curation != null && s.isCuratedDay()) cuts[s.cut] = curation
            }
            return cuts
        }

        // The cut and curation a leftovers channel claims from: the curated starters' own. Its day is theirs with every
        // shipped timing (a test holds it); should it ever not be, that is reported and it claims from theirs (the one
        // of the same day start, else the earliest), never failing the build.
        private fun claimsFor(
            s: Scheduled,
            curatedCuts: Map<Cut, DayCuration>,
        ): Pair<Cut, DayCuration>? {
            val curation = s.curation
            val same = curatedCuts[s.cut]
            return when {
                curatedCuts.isEmpty() -> {
                    curation?.let { s.cut to it }
                }

                same != null && same == curation -> {
                    s.cut to same
                }

                else -> {
                    claimsMismatch()
                    val cut = curatedCuts.keys.firstOrNull { it.dayStartMs == s.dayStartMs } ?: curatedCuts.keys.min()
                    cut to curatedCuts.getValue(cut)
                }
            }
        }

        /**
         * What the leftovers channels walked so far have claimed from one cut-off's snapshot, and how far that is. It
         * starts from [claimedByOn], what the day's curation ([claims]: its cut and curation) claims (before the
         * basis's boundary the basis on-set's claims, scheduled today or not; from it the scheduled curated channels':
         * A5, F5) and what is withheld.
         */
        private inner class ClaimChain(
            val cut: Cut,
            claims: Pair<Cut, DayCuration>?,
            claimedByOn: Set<CanonicalId>,
        ) {
            val claimed: MutableSet<CanonicalId> =
                HashSet(claimedByOn).apply {
                    if (claims != null) addAll(curated(claims.first, claims.second).claimed)
                    addAll(withheld(cut))
                }
            var next = 0
        }

        // The rules a channel airs on the day that starts at [dayStartMs]: a starter's rules for that day, and for an
        // unedited one, without the household collections when the flag in force that day is off (null: nothing is
        // left, a day with no source). A custom channel's rules are its own. A collection the snapshot lacks counts
        // as household-only (`HouseholdSources.without`), so on a snapshot without a full collection pass a partial
        // drop is silent; such collections resolve to nothing anyway, and a day with no source left says so.
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
            val curation = s.curation
            return if (curation != null && s.isCuratedDay()) {
                curatedDay(s, curation, rules, snapshot)
            } else {
                plainDay(s, rules, snapshot)
            }
        }

        // A day that is not curated: the channel's pool for the epoch, as its rules give it.
        private fun plainDay(
            s: Scheduled,
            rules: ChannelRules,
            snapshot: RuleSnapshot,
        ): ChannelDay {
            val ruled =
                ruledDayOf(ceilings, rules, s.channel, snapshot, s.settings, s.n) { r, policy ->
                    evaluate(r, s.cut, policy)
                }
            epochPools[s] = ruled.day
            return dayOf(s, ruled.day, ruled.films, hiddenReason(ruled.pool, ruled.day), TitleKeeps.NONE, NO_WEIGHTS)
        }

        /**
         * A curated starter's day (spec 23): its pool as the day's [curation] cuts it, the trim window of its keeps
         * (applied once, when the day is made) and its weights; the films map of the curated order (every box set).
         * A starter switched on after the basis's on-set was taken is not in it: it airs its pool without the titles
         * homed on the basis channels, each at weight 2, uncapped, until the boundary.
         */
        private fun curatedDay(
            s: Scheduled,
            curation: DayCuration,
            rules: ChannelRules,
            snapshot: RuleSnapshot,
        ): ChannelDay {
            val (ruled, policy) = ceilings.policyRules(rules, s.channel)
            val pool = evaluate(ruled, s.cut, policy)
            val day = curated(s.cut, curation)
            val own = day.pools[s.channel.id]
            val epoch: List<ScheduleItem>
            val keeps: TitleKeeps
            val weights: TitleWeights
            if (own != null) {
                epoch = EpochPool.build(s.settings, own.items, s.n)
                keeps = own.keeps
                weights = own.weights
            } else {
                epoch = EpochPool.build(s.settings, withoutHomes(pool.items, day.homes), s.n)
                keeps = TitleKeeps.NONE
                weights = newOnWeights(epoch)
            }
            // What the leftovers and the airing-nowhere count read: the epoch pool, before the window (H3).
            epochPools[s] = epoch
            val films = GroupCollections.of(ruled, snapshot, epoch)
            // The window keeps at least one episode of each series, so emptiness (and its reason) is unchanged by it.
            return dayOf(s, epoch, films, hiddenReason(pool, epoch), keeps, weights)
        }

        // A starter switched on for a later boundary: nothing airs before it starts, and it claims nothing.
        private fun startingDay(s: Scheduled): ChannelDay {
            epochPools[s] = emptyList()
            // TODO(design partner): a reason of its own ("Starts at 6 AM") for a channel that starts at the boundary.
            return dayOf(s, emptyList(), NO_FILMS, HiddenReason.EmptyToday, TitleKeeps.NONE, NO_WEIGHTS)
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
            return dayOf(s, emptyList(), NO_FILMS, reason, TitleKeeps.NONE, NO_WEIGHTS)
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
                val on = byId[before.id]
                // A channel that starts at a later boundary airs nothing today, so it claims nothing (#620).
                if (on?.starting == true) continue
                val pool = leftoversPool(before, snapshot, chain.claimed, chain.cut)
                val items =
                    if (on != null) {
                        EpochPool.build(on.settings, pool.items, on.n)
                    } else {
                        pool.items
                    }
                items.mapTo(chain.claimed) { it.id }
            }
            val pool = leftoversPool(s.channel, snapshot, chain.claimed, chain.cut)
            val day = EpochPool.build(s.settings, pool.items, s.n)
            epochPools[s] = day
            // A leftovers channel is never curated: no keeps, no weights.
            return dayOf(s, day, NO_FILMS, hiddenReason(pool, day), TitleKeeps.NONE, NO_WEIGHTS)
        }

        // The curated days' cache: one curation per cut and [DayCuration], however many channels read it.
        private val curations = HashMap<Pair<Cut, DayCuration>, DayCurated>()
        private val channelsById = lineup.channels.associateBy { it.id }

        /**
         * The curation of [cut]'s snapshot over [curation]'s on-set: `Curation.curate` once per cut and curation (the
         * basis choice), for every channel that reads it. A member that is not curated that day, or has no rules left
         * (household), takes no part.
         */
        fun curated(
            cut: Cut,
            curation: DayCuration,
        ): DayCurated =
            curations.getOrPut(cut to curation) {
                val snapshot = snapshotOf(cut)
                val on = ArrayList<CurationChannel>(curation.on.size)
                for (id in curation.on) {
                    val channel = channelsById[id] ?: continue
                    curationChannel(channel, cut, snapshot)?.let { on.add(it) }
                }
                if (on.isEmpty()) {
                    DayCurated(emptyMap(), null, emptySet())
                } else {
                    val result = Curation.curate(snapshot, on)
                    val claimed = HashSet<CanonicalId>()
                    for (pool in result.pools.values) for (item in pool.claimed) claimed.add(item.id)
                    DayCurated(result.pools, result.homes, claimed)
                }
            }

        // The curation's view of [channel] on [cut]'s day: its rules for the day (household applied) under its
        // scheduled ceiling and policy, its pool from the evaluation cache, and its recipe's number for the ties (a
        // renumber moves no home). Null when it is not curated that day or nothing of its rules is left.
        private fun curationChannel(
            channel: LineupChannel,
            cut: Cut,
            snapshot: RuleSnapshot,
        ): CurationChannel? {
            val source = channel.source as? ChannelSource.Starter
            val recipe = source?.let { recipes[it.recipeKey] }
            val curated = recipe != null && isCurated(channel, cut.dayStartMs)
            val rules = if (curated) rulesAt(channel, cut.dayStartMs, snapshot) else null
            return rules?.let { curationChannelOf(channel, checkNotNull(recipe), it, cut) }
        }

        private fun curationChannelOf(
            channel: LineupChannel,
            recipe: Recipe,
            rules: ChannelRules,
            cut: Cut,
        ): CurationChannel? {
            val (ruled, policy) = ceilings.policyRules(rules, channel)
            val settings = settingsOrNull(ruled) ?: return null
            val pool = evaluate(ruled, cut, policy).items
            val kid = policy == PoolPolicy.KidsOnly
            return CurationChannel(channel.id, recipe.number, recipe, kid, ruled, settings, pool)
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
                leftoversPool(channel, snapshot, claimed, cut).items.map { it.id }
            }
        }

        // The pool under the policy of the day of [cut]: a kids leftovers channel that starts later is off (#623).
        private fun leftoversPool(
            channel: LineupChannel,
            snapshot: RuleSnapshot,
            claimed: Set<CanonicalId>,
            cut: Cut,
        ): Pool =
            Leftovers.pool(
                snapshot,
                claimed,
                ceilings.of(channel),
                policyOf(channel, lineup, LineupDay.startingAt(cut.dayStartMs)),
            )

        // Every opt-in recipe of the catalog, as the lineup holds it on the day (its rules for that day: the rules in
        // force, so a catalog swap or a re-resolve waiting for the boundary withholds today what it withheld before,
        // D1). One the lineup holds as an unedited starter is that starter's rules, the recipe as the planner resolved
        // it. Any other (an edited starter, a custom channel from it, or none) also withholds the recipe as resolved
        // against the cut's snapshot (its collection names and library names, as the planner resolves them), from the
        // catalog as it is now. Each is evaluated under its policy: the resolved recipe by its recipe, the lineup's
        // channel by its channel. The ceiling is as today.
        private fun optInRules(cut: Cut): List<PolicyRules> {
            val snapshot = snapshotOf(cut)
            val collections by lazy { collectionNamesOf(snapshot) }
            val names by lazy { LibraryNames(snapshot, collections) }
            return catalog.recipes.filter { it.optIn }.flatMap { recipe ->
                val inLineup = lineup.channels.firstOrNull { it.id == LineupIds.starter(recipe.key) }
                val followsRecipe = (inLineup?.source as? ChannelSource.Starter)?.edited == false
                // The flag in force on the day counts here too: an opt-in channel withholds what it would air.
                val household = lineup.householdFor(cut.dayStartMs)
                val resolvedForDay =
                    if (followsRecipe) {
                        null
                    } else {
                        val offset = lineup.utcOffsetMinutes
                        val resolved = RecipeResolver.resolve(recipe, lineup.serverId, offset, collections, names)
                        if (household) resolved else HouseholdSources.without(resolved, snapshot)
                    }
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
        val cutoffMs: Long,
        val extras: Boolean,
        val sources: List<Source>,
        val exclusions: List<Source>,
        val filters: Filters?,
        val maxRating: String?,
        val policy: PoolPolicy,
    )

    /**
     * One day's curation (spec 23): each member's curated pool, where every candidate title is homed (null with no
     * member), and every item the members claim (their whole curated pools: trimmed items stay claimed).
     */
    internal class DayCurated(
        val pools: Map<ChannelId, CuratedPool>,
        val homes: Homes?,
        val claimed: Set<CanonicalId>,
    )

    // The settings of [rules], or null for settings the scheduler refuses.
    private fun settingsOrNull(rules: ChannelRules): SchedulerSettings? =
        try {
            rules.toSchedulerSettings()
        } catch (_: IllegalArgumentException) {
            null
        }

    // Whether this day is a curated starter's (its own curation), not a leftovers channel's.
    private fun Scheduled.isCuratedDay(): Boolean = curation != null && channel.source is ChannelSource.Starter

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

/** No title has a weight: a day that is not curated. */
internal val NO_WEIGHTS: TitleWeights = TitleWeights.NONE

// [items] without those of the titles [homes] gives a home (on a basis channel). A plain loop: it runs per item.
private fun withoutHomes(
    items: List<ScheduleItem>,
    homes: Homes?,
): List<ScheduleItem> {
    if (homes == null) return items
    val out = ArrayList<ScheduleItem>(items.size)
    for (item in items) if (homes.homeOf(item.groupId) == null) out.add(item)
    return out
}

// [weights] of the titles [pool] (the windowed day pool) holds only: a title none of whose items is in the day weighs
// nothing in it, and the days file then holds no weight a reader would refuse.
private fun weightsIn(
    weights: TitleWeights,
    pool: List<ScheduleItem>,
): TitleWeights {
    if (weights.isEmpty) return weights
    val titles = HashSet<CanonicalId>(pool.size * 2)
    for (item in pool) titles.add(item.groupId)
    val kept = HashMap<CanonicalId, Int>()
    for ((title, weight) in weights.entries) if (title in titles) kept[title] = weight
    return TitleWeights.of(kept)
}

// Weight 2 for every title of [pool]: a starter switched on after the basis was taken, as if it homed them all.
private fun newOnWeights(pool: List<ScheduleItem>): TitleWeights {
    val weights = HashMap<CanonicalId, Int>()
    for (item in pool) weights[item.groupId] = NEW_ON_WEIGHT
    return TitleWeights.of(weights)
}

/** The weight of a home (`CurationDefaults.WEIGHT_HOME`). */
private const val NEW_ON_WEIGHT = 2

private fun LineupChannel.isUneditedStarter(): Boolean {
    val source = source
    return source is ChannelSource.Starter && !source.edited
}

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
