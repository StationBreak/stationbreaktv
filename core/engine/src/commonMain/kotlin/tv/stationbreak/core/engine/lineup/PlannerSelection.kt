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
 * The planner's selection by identity for a catalog that [StarterCatalogView.selectsByIdentity] (spec 23, F7): which
 * starters are candidates, which are pinned, how much room there is, and what the result does to each channel.
 * [LineupPlanner] calls it in place of its ranking.
 */
internal object PlannerSelection {
    /**
     * How a refresh decides which starters are on: [Mode.Select] once the lineup is on a catalog that selects by
     * identity; [Mode.Hold] (nothing switches on or off; the on-set stays as the last selection left it) while a newer
     * catalog waits for a collection pass over a lineup already on a selecting catalog; otherwise [Mode.Rank]
     * (switch-ons only), which includes a lineup on a ranked catalog while a newer one waits.
     */
    enum class Mode { Select, Hold, Rank }

    /** The mode of a refresh of [current] with [p]'s catalog; [newer] when that catalog is applied in this refresh. */
    fun modeOf(
        current: Lineup,
        p: Planning,
        newer: Boolean,
    ): Mode {
        val waiting = p.catalog.version > current.catalogVersion && !newer
        return when {
            waiting && current.catalogVersion >= StarterCatalog.SELECTS_BY_IDENTITY_FROM -> Mode.Hold
            waiting -> Mode.Rank
            p.catalog.selectsByIdentity -> Mode.Select
            else -> Mode.Rank
        }
    }

    /**
     * The first choice: the non-opt-in recipes with a number are the candidates (none is pinned), with [room] places.
     * [shelf] holds the reason of every recipe left off: an opt-in one is off for being opt-in (N12), one with no
     * number has no room to air (BelowCap). [qualifying] counts the candidates that would be on with room enough, for
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
        room: Int,
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
        val selected = Selection.select(p.snapshot, candidates, emptySet(), emptySet(), room)
        val belowCap = selected.shelf.values.count { it == ShelfReason.BelowCap }
        val shelf = LinkedHashMap(selected.shelf)
        for (recipe in recipes) {
            val id = LineupIds.starter(recipe.key)
            if (recipe.optIn) {
                shelf[id] = ShelfReason.OptIn
            } else if (numbers.getValue(id) == null) {
                shelf[id] = ShelfReason.BelowCap
            }
        }
        return Choice(selected.on, shelf, selected.on.size + belowCap)
    }

    /**
     * [channels] (refreshed) after the selection, for a lineup [current] at [p]'s time.
     * - An untouched shelved starter the selection picks under the boundary's household flag and today's switches on
     *   now; an untouched starter on the air it leaves out leaves at the boundary (a time already set stays); one it
     *   picks again stays.
     * - A starter the viewer switched on is pinned: on, and never leaving.
     * - Every starter left on the shelf gets its current reason: the selection's; opt-in (N12); no room to air for
     *   one with no number; and for one the viewer chose or edited (never switched on by the planner) what the homing
     *   alongside the selected channels gives it.
     * Every other channel is left as it is.
     */
    fun refreshed(
        current: Lineup,
        channels: List<LineupChannel>,
        p: Planning,
    ): List<LineupChannel> {
        val lineup = current.copy(channels = channels)
        val roles = HashMap<ChannelId, Role>()
        for (channel in channels) roleOf(channel, current.viewerChoicesKnown, p)?.let { roles[channel.id] = it }
        val room = LineupDefaults.CAP - channels.count { it.takesRoom(roles[it.id]) }
        // Selected under the boundary's flag: a starter the selection switches on starts at the boundary.
        val atBoundary = select(lineup, channels, roles, p, p.household, room)
        val kept = keptReasons(lineup, channels, roles, p, atBoundary)
        return channels.map { channel ->
            when (roles[channel.id]) {
                null -> channel
                Role.Pinned -> pinned(channel)
                Role.OptIn -> leftOut(channel, ShelfReason.OptIn, p)
                Role.Numberless -> leftOut(channel, ShelfReason.BelowCap, p)
                Role.Kept -> leftOut(channel, kept.getValue(channel.id), p)
                Role.Free -> applied(channel, atBoundary.selected, p)
            }
        }
    }

    // What a channel is to the selection:
    // - Pinned: on and the viewer's, a candidate that is always on.
    // - Free: untouched and numbered, a candidate.
    // - OptIn: an untouched opt-in starter, never a candidate unless pinned.
    // - Numberless: untouched, not opt-in, no number: it cannot go on.
    // - Kept: on the shelf by the viewer's choice or edit, never switched on by the planner; it still gets a reason.
    // - null: left as it is.
    private enum class Role { Pinned, Free, OptIn, Numberless, Kept }

    private fun roleOf(
        channel: LineupChannel,
        viewerChoicesKnown: Boolean,
        p: Planning,
    ): Role? {
        val source = channel.source as? ChannelSource.Starter
        val recipe = source?.let { p.recipe(it.recipeKey) }
        val planned = recipe != null && channel.state != ChannelState.UserHidden
        return when {
            !planned -> null
            checkNotNull(source).edited -> if (channel.state == ChannelState.Shelf) Role.Kept else null
            else -> roleOfPlanned(channel, checkNotNull(recipe), viewerChoicesKnown, p.nowMs)
        }
    }

    // The role of an unedited starter that is not hidden and whose recipe is in the catalog.
    private fun roleOfPlanned(
        channel: LineupChannel,
        recipe: Recipe,
        viewerChoicesKnown: Boolean,
        nowMs: Long,
    ): Role {
        val on = channel.state == ChannelState.On
        // A lineup upgraded from a file before version 3 cannot tell the viewer's switch-ons from the planner's: any
        // starter on the air (and not leaving, which only the planner sets) may be the viewer's, so none is shelved.
        // A starter that starts at the boundary was switched on by the planner, never the viewer.
        val inferred = !viewerChoicesKnown && on && channel.leavesAtMs == null && channel.onSinceMs <= nowMs
        val chosen = channel.chosenByViewer || inferred
        return when {
            chosen && on -> Role.Pinned
            chosen -> Role.Kept
            recipe.optIn -> Role.OptIn
            channel.number == null -> Role.Numberless
            else -> Role.Free
        }
    }

    // A channel that holds a place for the days after the boundary: a pinned one, and any other channel on and not
    // leaving that is not an untouched candidate (the leftovers, own channels, edited starters). A leaving channel
    // goes at the boundary, so it holds no place in the selection for the days after it.
    private fun LineupChannel.takesRoom(role: Role?): Boolean =
        state == ChannelState.On &&
            when (role) {
                Role.Pinned -> true
                Role.Free, Role.OptIn -> false
                else -> leavesAtMs == null
            }

    // One selection and the channels it was made over.
    private class Run(
        val candidates: List<CurationChannel>,
        val selected: Selected,
    )

    @Suppress("LongParameterList") // What one selection reads.
    private fun select(
        lineup: Lineup,
        channels: List<LineupChannel>,
        roles: Map<ChannelId, Role>,
        p: Planning,
        household: Boolean,
        room: Int,
    ): Run {
        val candidates = ArrayList<CurationChannel>()
        val pinned = HashSet<ChannelId>()
        for (channel in channels) {
            val role = roles[channel.id]
            if (role != Role.Pinned && role != Role.Free) continue
            if (role == Role.Pinned) pinned += channel.id
            candidates += curationOf(channel, lineup, p, household)
        }
        return Run(candidates, Selection.select(p.snapshot, candidates, pinned, emptySet(), room))
    }

    // The curation's view of a starter in the lineup: what it airs on the air, or would air once switched on (an
    // unedited one); an edited one airs its own rules, with no household change (`HouseholdSources`, F4).
    private fun curationOf(
        channel: LineupChannel,
        lineup: Lineup,
        p: Planning,
        household: Boolean,
    ): CurationChannel {
        val source = channel.source as ChannelSource.Starter
        val recipe = checkNotNull(p.recipe(source.recipeKey))
        val own = channel.state == ChannelState.On || source.edited
        val rules = if (own) source.rules else p.switchOnRules(recipe, source)
        val policy = KidStatus.policyOf(channel, lineup)
        // Ties order by the recipe's number, as in the day build, so a renumber never moves a home.
        return p.curationChannel(channel.id, recipe.number, recipe, policy, rules, household || source.edited)
    }

    // The reasons of the kept starters: homed alongside the channels the selection put on (under the boundary's
    // flag), each is Empty with nothing that fits, TooThin when it would not qualify, else BelowCap.
    @Suppress("LongParameterList") // What the homing reads.
    private fun keptReasons(
        lineup: Lineup,
        channels: List<LineupChannel>,
        roles: Map<ChannelId, Role>,
        p: Planning,
        run: Run,
    ): Map<ChannelId, ShelfReason> {
        val kept = ArrayList<CurationChannel>()
        for (channel in channels) {
            if (roles[channel.id] == Role.Kept) kept += curationOf(channel, lineup, p, p.household)
        }
        if (kept.isEmpty()) return emptyMap()
        val on = run.candidates.filter { it.id in run.selected.on }
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
        val starting = channel.state == ChannelState.On && channel.onSinceMs > p.nowMs
        return when {
            channel.state == ChannelState.On && picked -> {
                pinned(channel)
            }

            starting -> {
                // Chosen for the boundary by an earlier plan and not now: it never aired, so it goes back on the shelf.
                channel.copy(state = ChannelState.Shelf, leavesAtMs = null, shelfReason = reason)
            }

            channel.state == ChannelState.On -> {
                leftOut(channel, reason, p)
            }

            picked -> {
                // A refresh switches nothing on in the middle of a day (D1): the starter starts at the boundary, as
                // one the selection leaves out leaves at it. Nothing of it airs before, so its rules apply at once.
                val source = channel.source as ChannelSource.Starter
                val recipe = checkNotNull(p.recipe(source.recipeKey))
                val next = source.withRulesNow(p.switchOnRules(recipe, source))
                channel.copy(state = ChannelState.On, source = next, onSinceMs = p.boundaryMs, shelfReason = null)
            }

            else -> {
                leftOut(channel, reason, p)
            }
        }
    }
}

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

/** The rules a shelved starter takes when switched on: its recipe's, keeping seed and timing, as on a refresh. */
internal fun Planning.switchOnRules(
    recipe: Recipe,
    source: ChannelSource.Starter,
): ChannelRules = resolve(recipe).copy(seed = source.rules.seed, timing = source.rules.timing)

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
