// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.curation.CurationChannel
import tv.stationbreak.core.engine.lineup.curation.Selected
import tv.stationbreak.core.engine.lineup.curation.Selection
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.ChannelRules

/**
 * The planner's selection by identity for a themed catalog (spec 23, F7): which starters are candidates, which are
 * pinned, how much room there is, and what the result does to each channel. [LineupPlanner] calls it in place of its
 * ranking when every recipe of the catalog is themed.
 */
internal object PlannerSelection {
    /** True when the catalog is selected by identity: it has recipes and every one is themed. */
    fun isThemed(catalog: StarterCatalogView): Boolean {
        val recipes = catalog.recipes
        return recipes.isNotEmpty() && recipes.all { it.theme != null }
    }

    /**
     * The first choice: the non-opt-in recipes with a number are the candidates (none is pinned), with [room] places.
     * [shelf] holds the reason of every recipe left off but one with no number (an opt-in one is off for being opt-in,
     * N12). [qualifying] counts the candidates that would be on with room enough, for the finalise threshold.
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
            candidates += p.curationChannel(id, number, recipe, policy, rules.getValue(recipe.key), p.household)
        }
        val selected = Selection.select(p.snapshot, candidates, emptySet(), emptySet(), room)
        val belowCap = selected.shelf.values.count { it == ShelfReason.BelowCap }
        val shelf = LinkedHashMap(selected.shelf)
        for (recipe in recipes) if (recipe.optIn) shelf[LineupIds.starter(recipe.key)] = ShelfReason.OptIn
        return Choice(selected.on, shelf, selected.on.size + belowCap)
    }

    /**
     * [channels] (refreshed) after the selection, for a lineup [current] at [p]'s time. An untouched shelved starter
     * the selection picks under the boundary's household flag and today's switches on now; an untouched starter on the
     * air it leaves out leaves at the boundary (a time already set stays); one it picks again stays. Every untouched
     * shelved or leaving starter gets its reason, and an untouched opt-in one is on the shelf for being opt-in.
     * Channels the viewer chose, edited starters and every other channel are left as they are.
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
        val atBoundary = select(lineup, channels, roles, p, p.household, room)
        // A starter switched on now airs from now, so it must also be picked under today's flag when that differs.
        val today =
            if (p.householdToday == p.household) {
                atBoundary
            } else {
                select(lineup, channels, roles, p, p.householdToday, room)
            }
        return channels.map { channel ->
            when (roles[channel.id]) {
                null, Role.Pinned -> channel
                Role.OptIn -> leftOut(channel, ShelfReason.OptIn, p)
                Role.Free -> applied(channel, atBoundary, today, p)
            }
        }
    }

    // What a channel is to the selection: pinned (on, the viewer's, a candidate), free (untouched, a candidate), an
    // untouched opt-in (never a candidate unless pinned), or nothing (left as it is).
    private enum class Role { Pinned, Free, OptIn }

    private fun roleOf(
        channel: LineupChannel,
        viewerChoicesKnown: Boolean,
        p: Planning,
    ): Role? {
        val source = channel.source as? ChannelSource.Starter
        val recipe = source?.let { p.recipe(it.recipeKey) }
        val planned = recipe != null && !source.edited && channel.state != ChannelState.UserHidden
        return if (planned) roleOfPlanned(channel, checkNotNull(recipe), viewerChoicesKnown) else null
    }

    // The role of an unedited starter that is not hidden and whose recipe is in the catalog.
    private fun roleOfPlanned(
        channel: LineupChannel,
        recipe: Recipe,
        viewerChoicesKnown: Boolean,
    ): Role? {
        val staying = channel.state == ChannelState.On && channel.leavesAtMs == null
        // A lineup upgraded from a file before version 3 cannot tell the viewer's switch-ons from the planner's: any
        // starter on the air may be the viewer's, so none is shelved.
        val chosen = channel.chosenByViewer || (staying && !viewerChoicesKnown)
        return when {
            chosen && staying -> Role.Pinned
            chosen -> null
            recipe.optIn -> Role.OptIn
            channel.number == null -> null
            else -> Role.Free
        }
    }

    // On, not leaving, and not a free candidate: the leftovers, own channels, edited and pinned starters. A leaving
    // channel goes at the boundary, so it holds no place in the selection for the days after it.
    private fun LineupChannel.takesRoom(role: Role?): Boolean =
        state == ChannelState.On && leavesAtMs == null && role != Role.Free && role != Role.OptIn

    @Suppress("LongParameterList") // What one selection reads.
    private fun select(
        lineup: Lineup,
        channels: List<LineupChannel>,
        roles: Map<ChannelId, Role>,
        p: Planning,
        household: Boolean,
        room: Int,
    ): Selected {
        val candidates = ArrayList<CurationChannel>()
        val pinned = HashSet<ChannelId>()
        for (channel in channels) {
            val role = roles[channel.id]
            if (role != Role.Pinned && role != Role.Free) continue
            if (role == Role.Pinned) pinned += channel.id
            val source = channel.source as ChannelSource.Starter
            val recipe = checkNotNull(p.recipe(source.recipeKey))
            val rules = if (channel.state == ChannelState.On) source.rules else p.switchOnRules(recipe, source)
            val policy = KidStatus.policyOf(channel, lineup)
            candidates += p.curationChannel(channel.id, checkNotNull(channel.number), recipe, policy, rules, household)
        }
        return Selection.select(p.snapshot, candidates, pinned, emptySet(), room)
    }

    private fun applied(
        channel: LineupChannel,
        atBoundary: Selected,
        today: Selected,
        p: Planning,
    ): LineupChannel {
        val picked = channel.id in atBoundary.on
        val reason = atBoundary.shelf[channel.id] ?: ShelfReason.BelowCap
        return when {
            channel.state == ChannelState.On && picked -> {
                if (channel.leavesAtMs == null && channel.shelfReason == null) {
                    channel
                } else {
                    channel.copy(leavesAtMs = null, shelfReason = null)
                }
            }

            channel.state == ChannelState.On -> {
                leftOut(channel, reason, p)
            }

            picked && channel.id in today.on -> {
                val source = channel.source as ChannelSource.Starter
                val recipe = checkNotNull(p.recipe(source.recipeKey))
                // Nothing of a shelved starter airs today, so there is nothing to protect: the rules apply at once.
                val next = source.withRulesNow(p.switchOnRules(recipe, source))
                channel.copy(state = ChannelState.On, source = next, onSinceMs = p.nowMs, shelfReason = null)
            }

            else -> {
                // Picked for the boundary but not under today's flag: it waits for the plan at the boundary.
                val why = if (picked) today.shelf[channel.id] ?: ShelfReason.BelowCap else reason
                leftOut(channel, why, p)
            }
        }
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
    val pool = if (aired == null) emptyList() else PoolEvaluator.evaluate(aired, snapshot, policy).items
    return CurationChannel(id, number, recipe, policy == PoolPolicy.KidsOnly, used, used.toSchedulerSettings(), pool)
}
