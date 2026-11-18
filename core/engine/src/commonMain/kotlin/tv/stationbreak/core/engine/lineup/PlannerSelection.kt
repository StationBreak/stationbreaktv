// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.lineup.curation.Selected
import tv.stationbreak.core.engine.lineup.curation.Selection
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.ChannelRules

/**
 * The planner's selection by identity (spec 23, F7): which starters are candidates, which are pinned, how much room
 * there is, and what the result does to each channel. [LineupPlanner] calls it.
 */
@Suppress("TooManyFunctions") // The selection's steps, with the candidate test the lineup audit shares.
internal object PlannerSelection {
    /**
     * How a refresh decides which starters are on: [Mode.Select] once the lineup is on the planner's catalog, and
     * [Mode.Hold] (nothing switches on or off; the on-set stays as it is) while a newer catalog waits for a collection
     * pass over a lineup on an older one.
     *
     * Hold, not a switch-on, over a lineup the older ranked planner left (the swap from catalog 2 to 3): a switch-on
     * would resolve the newer catalog's programmed recipe and put it on the air at once, a mid-day change of the
     * curated on-set before the swap, and the swap's inference of the viewer's choices (A7) reads the lineup as
     * catalog 2 left it. A seat freed during Hold is filled at the swap.
     */
    enum class Mode { Select, Hold }

    /** The mode of a refresh of [current] with [p]'s catalog; [newer] when that catalog is applied in this refresh. */
    fun modeOf(
        current: Lineup,
        p: Planning,
        newer: Boolean,
    ): Mode {
        val waiting = p.catalog.version > current.catalogVersion && !newer
        return if (waiting) Mode.Hold else Mode.Select
    }

    /**
     * The first choice: the non-opt-in recipes with a number are the candidates (none is pinned), with
     * [LineupDefaults.PLANNER_PICKS] places: the channels kept around it (a reset's custom channels) and the leftovers
     * take no place, as at the next boundary, so a reset and the boundary after it agree. [shelf] holds the reason of
     * every recipe left off: an opt-in one is off for being opt-in (N12), one with no number has no room to air
     * (NoNumber). [qualifying] counts the candidates that would be on with room enough, for
     * the finalise threshold.
     */
    class Choice(
        val on: Set<ChannelId>,
        val shelf: Map<ChannelId, ShelfReason>,
        val qualifying: Int,
    )

    fun firstChoice(
        p: Planning,
        recipes: List<Recipe>,
        numbers: Map<ChannelId, Int?>,
        rules: Map<String, ChannelRules>,
    ): Choice {
        val candidates = ArrayList<CurationChannel>()
        for (recipe in recipes) {
            val id = LineupIds.starter(recipe.key)
            val number = numbers.getValue(id)
            if (recipe.optIn || number == null) continue
            val policy = KidStatus.policyOf(recipe)
            // Ties order by the recipe's number, as in the day build.
            candidates += p.curationChannel(id, recipe.number, recipe, policy, rules.getValue(recipe.key), p.household)
        }
        val room = LineupDefaults.PLANNER_PICKS
        val selected = Selection.select(p.snapshot, candidates, emptySet(), emptySet(), room)
        val belowCap = selected.shelf.values.count { it == ShelfReason.BelowCap }
        val shelf = LinkedHashMap(selected.shelf)
        for (recipe in recipes) {
            val id = LineupIds.starter(recipe.key)
            if (recipe.optIn) {
                shelf[id] = ShelfReason.OptIn
            } else if (numbers.getValue(id) == null) {
                shelf[id] = ShelfReason.NoNumber
            }
        }
        return Choice(selected.on, shelf, selected.on.size + belowCap)
    }

    /**
     * [channels] (refreshed) after the selection, for a lineup [current] at [p]'s time.
     * - An untouched shelved starter the selection picks under the boundary's household flag and today's switches on
     *   now; an untouched starter on the air it leaves out leaves at the boundary (a time already set stays); one it
     *   picks again stays.
     * - A starter the viewer switched on is pinned: on, and never leaving. One that is only touched (a renumber, a
     *   swap, a switch on or off that was not an addition) is ranked with the free starters: inside the top
     *   [LineupDefaults.PLANNER_PICKS] it uses a seat, outside it, or with no score, it sits on top and costs none.
     *   The seats left are filled with the best free starters ([Selection.select]).
     * - A starter the viewer added ([ViewerChoice.Added], switched on while the planner had not picked it) is homed
     *   with the rest and always sits on top, never ranked: it takes no seat wherever its score falls, so the room is
     *   always the planner's own [LineupDefaults.PLANNER_PICKS] free picks, short only when too few starters qualify.
     *   Switched off, it is a kept starter (shelf) or hidden, and is no candidate.
     * - Every starter left on the shelf gets its current reason: the selection's; opt-in (N12); no room to air for
     *   one with no number (NoNumber); and for one the viewer chose or edited (never switched on by the planner) what
     *   the homing alongside the selected channels gives it.
     * Every other channel is left as it is.
     */
    fun refreshed(
        current: Lineup,
        channels: List<LineupChannel>,
        p: Planning,
    ): List<LineupChannel> {
        val lineup = current.copy(channels = channels)
        val roles = HashMap<ChannelId, Role>()
        // The planner infers the viewer's choices of an older file before any selection (`withInferredChoices`).
        check(current.viewerChoicesKnown) { "The viewer's choices are inferred before a selection" }
        for (channel in channels) roleOf(channel, p)?.let { roles[channel.id] = it }
        // Selected under the boundary's flag: a starter the selection switches on starts at the boundary.
        val atBoundary = select(lineup, channels, roles, p, p.household)
        val kept = keptReasons(lineup, channels, roles, p, atBoundary)
        return channels.map { channel ->
            when (roles[channel.id]) {
                null -> channel
                Role.Pinned, Role.Added -> pinned(channel)
                Role.OptIn -> leftOut(channel, ShelfReason.OptIn, p)
                Role.Numberless -> leftOut(channel, ShelfReason.NoNumber, p)
                Role.Kept -> leftOut(channel, kept.getValue(channel.id), p)
                Role.Free -> applied(channel, atBoundary.selected, p)
            }
        }
    }

    // What a channel is to the selection:
    // - Pinned: on and the viewer's, a candidate that is always on; ranked, so inside the top it uses a seat.
    // - Added: on and added by the viewer (an unedited starter only): homed and always on, never ranked, no seat.
    // - Free: untouched and numbered, a candidate.
    // - OptIn: an untouched opt-in starter, never a candidate unless pinned.
    // - Numberless: untouched, not opt-in, no number: it cannot go on.
    // - Kept: on the shelf by the viewer's choice or edit, never switched on by the planner; it still gets a reason.
    // - null: left as it is.
    private enum class Role { Pinned, Added, Free, OptIn, Numberless, Kept }

    private fun roleOf(
        channel: LineupChannel,
        p: Planning,
    ): Role? {
        val source = channel.source as? ChannelSource.Starter
        val recipe = source?.let { p.recipe(it.recipeKey) }
        val planned = recipe != null && channel.state != ChannelState.UserHidden
        return when {
            !planned -> null
            checkNotNull(source).edited -> if (channel.state == ChannelState.Shelf) Role.Kept else null
            else -> roleOfPlanned(channel, checkNotNull(recipe))
        }
    }

    // The role of an unedited starter that is not hidden and whose recipe is in the catalog.
    private fun roleOfPlanned(
        channel: LineupChannel,
        recipe: Recipe,
    ): Role {
        val on = channel.state == ChannelState.On
        // Opt-in before Kept: a shelved opt-in starter the viewer touched is still off for being opt-in (N12), and
        // shows that reason, not one from the homing.
        return when {
            channel.viewerChoice == ViewerChoice.Added && on -> Role.Added
            channel.chosenByViewer && on -> Role.Pinned
            recipe.optIn -> Role.OptIn
            channel.chosenByViewer -> Role.Kept
            channel.number == null -> Role.Numberless
            else -> Role.Free
        }
    }

    /**
     * Whether the selection treats [channel] as a candidate: an untouched numbered starter (free), a pinned one or an
     * added one (the lineup audit and the planner agree on it).
     */
    fun isCandidate(
        channel: LineupChannel,
        p: Planning,
    ): Boolean {
        val role = roleOf(channel, p)
        return role == Role.Pinned || role == Role.Added || role == Role.Free
    }

    // One selection and the channels it was made over.
    private class Run(
        val candidates: List<CurationChannel>,
        val selected: Selected,
    )

    // Every pinned, added and free starter is in play. The room is the planner's picks, whatever else is on: only a
    // pinned (touched) starter inside the top of the one order takes a seat from it. An added one, and a pinned opt-in
    // starter (the planner never picks one), take part in the homing only and always sit on top (`Selection.select`).
    @Suppress("LongParameterList") // What one selection reads.
    private fun select(
        lineup: Lineup,
        channels: List<LineupChannel>,
        roles: Map<ChannelId, Role>,
        p: Planning,
        household: Boolean,
    ): Run {
        val candidates = ArrayList<CurationChannel>()
        val pinned = HashSet<ChannelId>()
        val unranked = HashSet<ChannelId>()
        val optIn = HashSet<ChannelId>()
        for (channel in channels) {
            val role = roles[channel.id]
            if (role != Role.Pinned && role != Role.Added && role != Role.Free) continue
            if (role == Role.Pinned || role == Role.Added) pinned += channel.id
            if (role == Role.Added) unranked += channel.id
            if (p.recipe((channel.source as ChannelSource.Starter).recipeKey)?.optIn == true) optIn += channel.id
            candidates += curationOf(channel, lineup, p, household)
        }
        val picked = Selection.select(p.snapshot, candidates, pinned, optIn, LineupDefaults.PLANNER_PICKS, unranked)
        return Run(candidates, picked)
    }

    // The curation's view of a starter in the lineup: what it airs on the air, or would air once switched on (an
    // unedited one); an edited one airs its own rules, with no household change (`HouseholdSources`, F4).
    internal fun curationOf(
        channel: LineupChannel,
        lineup: Lineup,
        p: Planning,
        household: Boolean,
    ): CurationChannel {
        val input = reasonInputOf(channel, lineup, p, household)
        val recipe = checkNotNull(p.recipe((channel.source as ChannelSource.Starter).recipeKey))
        // Ties order by the recipe's number, as in the day build, so a renumber never moves a home.
        return p.curationChannel(channel.id, recipe.number, recipe, input.policy, input.rules, input.household)
    }

    // The reasons of the kept starters: homed alongside the channels the selection put on (under the boundary's
    // flag), each is Empty with nothing that fits, TooThin when it would not qualify, else BelowCap. With a memo and
    // the snapshot's generation ([Planning.keptReasons]), a plan whose kept starters, selected channels, catalog and
    // snapshot generation are those of the plan before reuses that plan's reasons and skips the homing: the same
    // inputs give the same reasons. No kept starter: no homing either.
    @Suppress("LongParameterList") // What the homing reads.
    private fun keptReasons(
        lineup: Lineup,
        channels: List<LineupChannel>,
        roles: Map<ChannelId, Role>,
        p: Planning,
        run: Run,
    ): Map<ChannelId, ShelfReason> {
        val keptChannels = channels.filter { roles[it.id] == Role.Kept }
        val memo = p.keptReasons
        val generation = p.snapshotGeneration
        val key =
            if (keptChannels.isNotEmpty() && memo != null && generation != null) {
                ReasonsKey(
                    generation,
                    p.snapshot.libraries
                        .map { it.value }
                        .sorted(),
                    p.snapshot.collectionsSynced,
                    p.catalog.version,
                    keptChannels.map { reasonInputOf(it, lineup, p, p.household) },
                    channels.filter { it.id in run.selected.on }.map { reasonInputOf(it, lineup, p, p.household) },
                )
            } else {
                null
            }
        return when {
            keptChannels.isEmpty() -> emptyMap()
            memo != null && key != null && key == memo.inputs -> memo.reasons
            else -> homedReasons(lineup, keptChannels, p, run).also { if (key != null) memo?.keep(key, it) }
        }
    }

    // The kept starters' reasons from a homing alongside the channels the selection put on.
    private fun homedReasons(
        lineup: Lineup,
        keptChannels: List<LineupChannel>,
        p: Planning,
        run: Run,
    ): Map<ChannelId, ShelfReason> {
        val memo = p.keptReasons
        val kept = keptChannels.map { curationOf(it, lineup, p, p.household) }
        val on = run.candidates.filter { it.id in run.selected.on }
        memo?.let { it.homings++ }
        val homes = Curation.homes(p.snapshot, on + kept)
        val reasons = HashMap<ChannelId, ShelfReason>()
        for (c in kept) {
            reasons[c.id] =
                when {
                    homes.candidates(c.id) == 0 -> ShelfReason.Empty
                    !homes.stats(c.id).qualifies -> ShelfReason.TooThin
                    else -> ShelfReason.BelowCap
                }
        }
        return reasons
    }

    private fun applied(
        channel: LineupChannel,
        atBoundary: Selected,
        p: Planning,
    ): LineupChannel {
        val picked = channel.id in atBoundary.on
        val reason = atBoundary.shelf[channel.id] ?: ShelfReason.BelowCap
        val starting = channel.state == ChannelState.On && channel.startsAfter(p.today)
        return when {
            channel.state == ChannelState.On && picked -> {
                pinned(channel)
            }

            starting -> {
                // Chosen for the boundary by an earlier plan and not now: it never aired, so it goes back on the shelf.
                // Its time is the plan's, never ahead: a Shelf starter whose start is still ahead is only ever one the
                // viewer switched off (`LineupEdits`, the undo), so the two are told apart by the stored state.
                channel.copy(state = ChannelState.Shelf, onSinceMs = p.nowMs, leavesAtMs = null, shelfReason = reason)
            }

            channel.state == ChannelState.On -> {
                leftOut(channel, reason, p)
            }

            picked -> {
                // A refresh switches nothing on in the middle of a day (D1): the starter starts at the boundary, as
                // one the selection leaves out leaves at it. Nothing of it airs before, so its rules apply at once.
                val source = channel.source as ChannelSource.Starter
                val recipe = checkNotNull(p.recipe(source.recipeKey))
                val next = source.withRulesNow(p.reResolved(recipe, source))
                channel.copy(state = ChannelState.On, source = next, onSinceMs = p.boundaryMs, shelfReason = null)
            }

            else -> {
                leftOut(channel, reason, p)
            }
        }
    }
}

// What [curationOf] reads of a starter, before any pool is evaluated: what the kept starters' reasons depend on.
@Suppress("DEPRECATION") // The reasons are read as the lineup is, across every day: the day-less policy (#629).
internal fun reasonInputOf(
    channel: LineupChannel,
    lineup: Lineup,
    p: Planning,
    household: Boolean,
): ReasonInput {
    val source = channel.source as ChannelSource.Starter
    val recipe = checkNotNull(p.recipe(source.recipeKey))
    val own = channel.state == ChannelState.On || source.edited
    val rules = if (own) source.rules else p.reResolved(recipe, source)
    return ReasonInput(channel.id, rules, KidStatus.policyOf(channel, lineup), household || source.edited)
}

// One starter's part of [ReasonsKey]: what its curation channel is made from (its recipe follows from its ID and
// the catalog's version).
internal data class ReasonInput(
    val id: ChannelId,
    val rules: ChannelRules,
    val policy: PoolPolicy,
    val household: Boolean,
)

// Everything the kept starters' reasons are worked out from, but for the library snapshot itself, which its
// content generation and libraries stand for: compared whole, never hashed, so two keys are equal only when the
// inputs are.
private data class ReasonsKey(
    val generation: Long,
    // The libraries planned, sorted: another choice of libraries can read a snapshot of the same generation.
    val libraries: List<String>,
    val collectionsSynced: Boolean,
    val catalogVersion: Int,
    val kept: List<ReasonInput>,
    val on: List<ReasonInput>,
)

// A pinned channel is on and stays: a leaving time or a reason the planner set before is cleared.
private fun pinned(channel: LineupChannel): LineupChannel =
    if (channel.leavesAtMs == null && channel.shelfReason == null) {
        channel
    } else {
        channel.copy(leavesAtMs = null, shelfReason = null)
    }

// On the shelf with [reason], or, on the air, leaving at the boundary (unless already leaving) with it.
private fun leftOut(
    channel: LineupChannel,
    reason: ShelfReason,
    p: Planning,
): LineupChannel {
    val leaves = if (channel.state == ChannelState.On) channel.leavesAtMs ?: p.boundaryMs else channel.leavesAtMs
    if (channel.leavesAtMs == leaves && channel.shelfReason == reason) return channel
    return channel.copy(leavesAtMs = leaves, shelfReason = reason)
}

/**
 * The rules a starter takes when its recipe is resolved again (a refresh, or a shelved starter switched on): its
 * recipe's, keeping seed and timing. The version is the higher of the resolver's (floored at 5, the spread day end) and
 * the write version over the stored one, so a stored file is never written lower (ADR-0026: a studio file that says 4
 * stays 4 or more; a file that says 5 stays 5).
 *
 * Because the resolver's floor is 5, the rules a keeper takes here ([withNewRules]) are always at 5. The keeper path
 * therefore never holds rules at 5 as the previous rules of rules below 5 (today spreading, tomorrow gathering): the
 * pair it makes is old rules today and v5 rules from the boundary. Edited starters and custom channels hold no
 * previous rules.
 */
internal fun Planning.reResolved(
    recipe: Recipe,
    source: ChannelSource.Starter,
): ChannelRules {
    val resolved = resolve(recipe).copy(seed = source.rules.seed, timing = source.rules.timing)
    val stored = resolved.writeVersion(previousVersion = source.rules.formatVersion)
    return resolved.copy(formatVersion = maxOf(resolved.formatVersion, stored))
}

/**
 * The curation's view of a starter: its pool under [policy], with the household's collections left out when
 * [household] is off (none left: an empty pool).
 */
@Suppress("LongParameterList") // One for each fact the curation channel carries.
internal fun Planning.curationChannel(
    id: ChannelId,
    number: Int,
    recipe: Recipe,
    policy: PoolPolicy,
    rules: ChannelRules,
    household: Boolean,
): CurationChannel {
    val aired = if (household) rules else HouseholdSources.without(rules, snapshot)
    val used = aired ?: rules
    val pool = if (aired == null) emptyList() else poolOf(id, aired, policy)
    return CurationChannel(id, number, recipe, policy == PoolPolicy.KidsOnly, used, used.toSchedulerSettings(), pool)
}

/**
 * The kept starters' shelf reasons (the shelved starters the viewer chose or edited, `PlannerSelection`) from the last
 * plan of one lineup, with what they were worked out from, so a plan whose inputs to them are unchanged skips their
 * homing, a full curation pass, on older hardware (spec 23). The caller keeps one per account in memory, never on disk,
 * and passes it to [LineupPlanner.plan] with the library snapshot's content generation, which stands for the snapshot:
 * a snapshot read while a write lands can be newer than the generation read before it, and then the reasons of the
 * plan before stand until the next plan, which the newer generation brings. One plan at a time (the caller's lock).
 */
class KeptReasonsMemo {
    internal var inputs: Any? = null
    internal var reasons: Map<ChannelId, ShelfReason> = emptyMap()

    internal fun keep(
        inputs: Any,
        reasons: Map<ChannelId, ShelfReason>,
    ) {
        this.inputs = inputs
        this.reasons = reasons
    }

    // How many times the homing ran with this memo, for tests.
    internal var homings: Int = 0
}
