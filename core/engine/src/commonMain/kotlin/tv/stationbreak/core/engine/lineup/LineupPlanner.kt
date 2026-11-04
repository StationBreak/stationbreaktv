// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.airableRuntimeMs
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.ChannelRules

/**
 * Chooses and keeps current an account's channel lineup (spec 12, "Choosing the lineup"). Pure: the same inputs give
 * the same lineup.
 *
 * Known effects, accepted for now:
 * - An unedited starter stored without `alignToGrid` is set to false (back to back) once, on the next start or
 *   refresh after the update (the refresher plans for it even when the library has not changed). What is on now
 *   changes at once, and each TV switches at its own next start, so two TVs can differ until both have started.
 * - Rules resolved again (on a synced call or from a newer catalog) start at the next broadcast-day start, the
 *   `boundaryMs` the caller gives: the starter keeps its previous rules for the days before it
 *   (`ChannelSource.Starter.withNewRules`), so today's schedule never changes under the viewer. A shelved starter that
 *   switches on takes its new rules at once, with no previous ones: nothing of it airs today. A change of the curated
 *   on-set is recorded in the lineup's basis the same way (`withBasisFor`). The exceptions apply at once: the back to
 *   back fix of an old file, and a viewer's reset.
 * - A starter the planner takes off for good stays on until the boundary (`leavesAtMs`) and then goes to the shelf
 *   (`Lineup.rolled`); the steps that set it come with the curation (spec 23).
 * - While a newer catalog waits for a full collection pass, a shelved starter that switches on is resolved from the
 *   newer catalog's recipe, until that catalog is applied.
 * - The viewer's number override always holds. A new recipe treats both default and override numbers as taken, so
 *   when its default is held by a viewer's override it joins the shelf with no number, and never shares one.
 */
object LineupPlanner {
    /**
     * The lineup after a library sync.
     *
     * Until a lineup is finalised it is chosen afresh at [standardOffsetMinutes]. The first choice waits for a full
     * collection pass (`collectionsSynced`), so every recipe's collection hints resolve before it ranks and the same
     * library gives every TV the same lineup; until then the lineup has no channels. With [withoutCollections] it does
     * not wait: a collection pass that never completes would otherwise keep the lineup empty, or on an old catalog,
     * for good. The caller decides when that is (the planner stays pure and only obeys). The choice is made with the
     * collection names it was given (none, if no pass has run), and a pass that lands later reaches the starters
     * through the hint pass below. With fewer than [LineupDefaults.FINALISE_THRESHOLD] filling non-opt-in recipes
     * nothing is on yet either. A kid recipe fills only with what is made for children (the kid hold, ADR-0019).
     * Otherwise the leftovers channels and the best-filling non-opt-in recipes (ties by key) are switched on up to
     * [LineupDefaults.CAP], and every other recipe goes on the shelf at its default number.
     *
     * A finalised lineup keeps its offset, its numbers and every switched-on or hidden channel's state. A shelved,
     * unedited starter that has a number and fills is switched on while there is room, best first. On every call
     * with a full collection pass (`collectionsSynced`) each unedited starter that is on or on the shelf is resolved
     * again from its recipe, keeping its seed and timing, so a collection that appears later reaches it. A call
     * without one resolves no channel again, apart from a starter it switches on. A newer catalog refreshes unedited
     * starters (keeping seed and timing), freezes starters whose recipe left as custom channels and shelves new
     * recipes (with no number when theirs is taken). A newer catalog is applied only with a full collection pass (or
     * [withoutCollections]); until then the lineup keeps its catalog version. A leftovers channel whose key is in the
     * catalog takes its rating ceiling from it on every call, deferred catalog or not. A catalog older than the
     * lineup's changes nothing.
     *
     * Each recipe's pool is evaluated at most once per call.
     *
     * A first choice can be made around [kept] channels (a reset keeps the viewer's own channels; the caller picks
     * them, and for a lineup not yet finalised, whose own channels a first choice replaces, it passes the ones to
     * keep). They go into the lineup as they are. Their numbers count as taken: a catalog channel whose default one is
     * held takes the first number free in its own band, or (a starter) goes on the shelf with no number when that band
     * is full. A leftovers channel is always on, so it never goes on the shelf for that: it takes any free number it
     * may use, its own band first and then, for the grown-up one, the other bands up to 599 (the kids one stays in the
     * Kids band, being a kid channel; only if that band is full too has it no number).
     * Their switched-on channels count toward [LineupDefaults.CAP] before any starter is chosen, so the lowest-ranked
     * starters are left off, never a kept channel. The leftovers channels are exempt: they are always switched on
     * (the "Odds and ends" decision), so the lineup can hold more than the cap when the kept channels alone fill it.
     * A finalised lineup ignores [kept]: it is planned from its own channels.
     */
    @Suppress("LongParameterList")
    fun plan(
        current: Lineup?,
        serverId: CanonicalId,
        userId: CanonicalId,
        standardOffsetMinutes: Int,
        snapshot: RuleSnapshot,
        collections: CollectionNames,
        nowMs: Long,
        boundaryMs: Long,
        catalog: StarterCatalogView = StarterCatalog,
        withoutCollections: Boolean = false,
        kept: List<LineupChannel> = emptyList(),
    ): Lineup {
        if (current != null) {
            require(current.serverId == serverId && current.userId == userId) { "The lineup is another account's" }
            if (catalog.version < current.catalogVersion) return current
        }
        return if (current == null || !current.finalised) {
            val planning = Planning(serverId, standardOffsetMinutes, snapshot, collections, catalog, nowMs, boundaryMs)
            firstChoice(planning, userId, withoutCollections, kept)
        } else {
            val offset = current.utcOffsetMinutes
            val household = current.householdFor(boundaryMs)
            val today = current.householdFor(nowMs)
            val planning =
                Planning(serverId, offset, snapshot, collections, catalog, nowMs, boundaryMs, household, today)
            refresh(current, planning, withoutCollections)
        }
    }

    private fun firstChoice(
        p: Planning,
        userId: CanonicalId,
        withoutCollections: Boolean,
        kept: List<LineupChannel>,
    ): Lineup {
        val unfinalised = Lineup(p.serverId, userId, p.catalog.version, p.offset, false, ordered(kept))
        return if (p.snapshot.collectionsSynced || withoutCollections) chosen(p, unfinalised, kept) else unfinalised
    }

    // The first choice proper, made once the collection pass is in (or not waited for): nothing is on yet below the
    // threshold.
    private fun chosen(
        p: Planning,
        unfinalised: Lineup,
        kept: List<LineupChannel>,
    ): Lineup {
        val keptIds = kept.mapTo(HashSet()) { it.id }
        // A kept channel with a catalog channel's ID (a starter whose recipe left, say) is the one in the lineup.
        val recipes = p.catalog.recipes.filter { LineupIds.starter(it.key) !in keptIds }
        val leftoversRecipes = p.catalog.leftovers.filter { LineupIds.leftovers(it.key) !in keptIds }
        val numbers = p.numbersAround(kept, recipes, leftoversRecipes)
        val rules = recipes.associate { it.key to p.resolve(it) }
        val ranked =
            p.ranked(
                recipes
                    .filter { !it.optIn && numbers.getValue(LineupIds.starter(it.key)) != null }
                    .map { it to rules.getValue(it.key) },
            )
        if (ranked.size < LineupDefaults.FINALISE_THRESHOLD) return unfinalised
        val leftovers =
            leftoversRecipes.map {
                val number = numbers.getValue(LineupIds.leftovers(it.key))
                p.leftoversChannel(it, number, if (number != null) ChannelState.On else ChannelState.Shelf)
            }
        val taken = kept.count { it.state == ChannelState.On } + leftovers.count { it.state == ChannelState.On }
        val room = LineupDefaults.CAP - taken
        val on = ranked.take(room.coerceAtLeast(0)).mapTo(HashSet()) { it.key }
        val starters =
            recipes.map {
                val state = if (it.key in on) ChannelState.On else ChannelState.Shelf
                p.starterChannel(it, numbers.getValue(LineupIds.starter(it.key)), state, rules.getValue(it.key))
            }
        return unfinalised.copy(finalised = true, channels = ordered(kept + leftovers + starters))
    }

    private fun refresh(
        current: Lineup,
        p: Planning,
        withoutCollections: Boolean,
    ): Lineup {
        // A newer catalog waits for a full collection pass, so its hints resolve against every collection, unless the
        // caller has given up waiting for one. Hints missed that way resolve on the first synced refresh (rehinted).
        val newer = p.catalog.version > current.catalogVersion && (p.snapshot.collectionsSynced || withoutCollections)
        val refreshed = current.channels.map { refreshed(backToBack(it), p, newer) }
        val withNew = if (newer) refreshed + added(refreshed, p) else refreshed
        val next =
            current.copy(
                catalogVersion = if (newer) p.catalog.version else current.catalogVersion,
                channels = ordered(switchedOn(withNew, p)),
            )
        // A change of the curated on-set starts at the boundary: the days before it keep the old one (D1).
        return current.withBasisFor(next, p.nowMs, p.boundaryMs)
    }

    // Starters stored before the app wrote `alignToGrid: false` have no value, which the format reads as the grid. An
    // unedited one is set to back to back, once and whatever the catalog version (the refresh below keeps the stored
    // timing, so it would never get there otherwise). An edited or custom channel is never touched.
    private fun backToBack(channel: LineupChannel): LineupChannel {
        val source = channel.source as? ChannelSource.Starter
        if (source == null || source.edited || source.rules.timing.alignToGrid != null) return channel
        // At once and not at a boundary (F12): old files only. Previous rules, if any, keep the same timing.
        val fixed = source.mapRules { it.copy(timing = it.timing.copy(alignToGrid = false)) }
        return channel.copy(source = fixed)
    }

    // Catalog updates and the hint pass of every synced call. Edited, custom and unusable channels are never touched.
    private fun refreshed(
        channel: LineupChannel,
        p: Planning,
        newer: Boolean,
    ): LineupChannel =
        when (val source = channel.source) {
            is ChannelSource.Starter -> {
                val recipe = p.recipe(source.recipeKey)
                when {
                    recipe == null -> {
                        if (newer) {
                            // A custom channel has no day to leave on and no shelf reason. It keeps the rules of today,
                            // so a change still waiting for its boundary is not applied to a channel that is frozen.
                            channel.copy(
                                source = ChannelSource.Custom(source.rulesFor(p.nowMs)),
                                leavesAtMs = null,
                                shelfReason = null,
                            )
                        } else {
                            channel
                        }
                    }

                    source.edited || !(newer || rehinted(channel, p)) -> {
                        channel
                    }

                    else -> {
                        val rules = p.resolve(recipe).copy(seed = source.rules.seed, timing = source.rules.timing)
                        channel.copy(source = source.withNewRules(rules, p.nowMs, p.boundaryMs))
                    }
                }
            }

            is ChannelSource.Leftovers -> {
                // The catalog's ceiling wins on every call; the stored one counts only once the key has left it.
                val recipe = p.catalog.leftovers.firstOrNull { it.key == source.recipeKey }
                if (recipe != null) channel.copy(source = source.copy(maxRating = recipe.maxRating)) else channel
            }

            is ChannelSource.Custom, is ChannelSource.Unusable -> {
                channel
            }
        }

    // On a synced call an unedited starter that is on or on the shelf is resolved again, so collections that appeared
    // since reach it. Resolution is deterministic: unchanged collection names give equal rules.
    private fun rehinted(
        channel: LineupChannel,
        p: Planning,
    ): Boolean = p.snapshot.collectionsSynced && channel.state != ChannelState.UserHidden

    // Catalog channels the lineup does not have yet. A taken default number is left unset rather than shared.
    private fun added(
        channels: List<LineupChannel>,
        p: Planning,
    ): List<LineupChannel> {
        val ids = channels.mapTo(HashSet()) { it.id }
        val taken = channels.flatMapTo(HashSet()) { listOfNotNull(it.defaultNumber, it.numberOverride) }
        var on = channels.count { it.state == ChannelState.On }
        val leftovers =
            p.catalog.leftovers.filter { LineupIds.leftovers(it.key) !in ids }.map {
                val number = it.number.takeIf(taken::add)
                val state = if (number != null && on < LineupDefaults.CAP) ChannelState.On else ChannelState.Shelf
                if (state == ChannelState.On) on++
                p.leftoversChannel(it, number, state)
            }
        val starters =
            p.catalog.recipes.filter { LineupIds.starter(it.key) !in ids }.map {
                p.starterChannel(it, it.number.takeIf(taken::add), ChannelState.Shelf, p.resolve(it))
            }
        return leftovers + starters
    }

    // Shelved, unedited, numbered, non-opt-in starters that fill are switched on while there is room, best first.
    private fun switchedOn(
        channels: List<LineupChannel>,
        p: Planning,
    ): List<LineupChannel> {
        val room = LineupDefaults.CAP - channels.count { it.state == ChannelState.On }
        if (room <= 0) return channels
        val candidates =
            channels.mapNotNull { channel ->
                val source = channel.source as? ChannelSource.Starter ?: return@mapNotNull null
                val recipe = p.recipe(source.recipeKey) ?: return@mapNotNull null
                val shelved = channel.state == ChannelState.Shelf && channel.number != null
                if (shelved && !source.edited && !recipe.optIn) recipe to p.resolve(recipe) else null
            }
        val chosen = p.ranked(candidates).take(room)
        val rules = candidates.associate { (recipe, rules) -> recipe.key to rules }
        val byId = chosen.associateBy { LineupIds.starter(it.key) }
        return channels.map { channel ->
            val recipe = byId[channel.id] ?: return@map channel
            val source = channel.source as ChannelSource.Starter
            // Seed and timing stay, as on a refresh.
            val resolved = rules.getValue(recipe.key).copy(seed = source.rules.seed, timing = source.rules.timing)
            // Nothing of a shelved starter airs today, so there is nothing to protect: the rules apply at once.
            val next = source.withRulesNow(resolved)
            channel.copy(state = ChannelState.On, source = next, onSinceMs = p.nowMs, shelfReason = null)
        }
    }

    // Number order (channels without a number last), then ID, so the same lineup is always listed the same way.
    private fun ordered(channels: List<LineupChannel>): List<LineupChannel> =
        channels.sortedWith(compareBy<LineupChannel, Int?>(nullsLast()) { it.number }.thenBy { it.id.value })
}

/** What one call of [LineupPlanner.plan] works with. Internal only so LineupPlannerTest can call `ranked`. */
@Suppress("LongParameterList") // What one call reads, one for one.
internal class Planning(
    val serverId: CanonicalId,
    val offset: Int,
    val snapshot: RuleSnapshot,
    val collections: CollectionNames,
    val catalog: StarterCatalogView,
    val nowMs: Long,
    val boundaryMs: Long,
    // The "Use my collections" flag in force for the days the ranking decides (`Lineup.householdFor`); a first
    // choice has the default, on.
    val household: Boolean = true,
    // The flag in force today: a starter switched on airs from now, so it must fill today as well (`withRulesNow`).
    val householdToday: Boolean = household,
) {
    private val recipes = catalog.recipes.associateBy { it.key }
    private val names by lazy { LibraryNames(snapshot, collections) }

    fun recipe(key: String): Recipe? = recipes[key]

    fun resolve(recipe: Recipe): ChannelRules = RecipeResolver.resolve(recipe, serverId, offset, collections, names)

    /**
     * The number each catalog channel takes in a first choice around [kept]: its default, unless a kept channel holds
     * it (as a default or an override); then the first number that nothing holds and no catalog channel has as its
     * default, in catalog order: in its own band for a starter, and for a leftovers channel in its own band and then
     * the grown-up ones below the custom band. Null when there is none.
     */
    fun numbersAround(
        kept: List<LineupChannel>,
        recipes: List<Recipe>,
        leftovers: List<LeftoversRecipe>,
    ): Map<ChannelId, Int?> {
        val held = kept.flatMapTo(HashSet()) { listOfNotNull(it.defaultNumber, it.numberOverride) }
        val defaults =
            recipes.map { Triple(LineupIds.starter(it.key), it.number, listOf(it.band.numbers)) } +
                leftovers.map { Triple(LineupIds.leftovers(it.key), it.number, leftoversRanges(it.number)) }
        val reserved = HashSet(held)
        reserved += defaults.map { it.second }
        reserved += catalog.retiredNumbers
        return defaults.associate { (id, number, ranges) ->
            val free = ranges.asSequence().flatMap { it.asSequence() }.firstOrNull { it !in reserved }
            id to if (number !in held) number else free?.also(reserved::add)
        }
    }

    // Where a leftovers channel whose default is held may go: its band, and (not the kids one) the grown-up bands.
    private fun leftoversRanges(number: Int): List<IntRange> {
        val own = Band.of(number)?.numbers ?: return emptyList()
        val grownUp = Band.General.numbers.first..Band.Premium.numbers.last
        return if (own == Band.Kids.numbers) listOf(own) else listOf(own, grownUp)
    }

    /** The [candidates] that fill, by airable runtime (most first), ties by key. Each pool is evaluated once. */
    fun ranked(candidates: List<Pair<Recipe, ChannelRules>>): List<Recipe> =
        candidates
            .mapNotNull { (recipe, rules) ->
                // With the household's collections off, a starter ranks on the pool it will air; none left ranks empty.
                val runtime = airableUnder(recipe, rules, household)
                // One switched on airs from now, so it must also fill under the flag of today, when that differs.
                val today = if (householdToday == household) runtime else airableUnder(recipe, rules, householdToday)
                val least = LineupDefaults.MIN_AIRABLE_RUNTIME_MS
                if (runtime >= least && today >= least) {
                    recipe to runtime
                } else {
                    null
                }
            }.sortedWith(compareByDescending<Pair<Recipe, Long>> { it.second }.thenBy { it.first.key })
            .map { it.first }

    // The airable runtime of [rules]' pool with the household flag [on]; zero when no source is left.
    private fun airableUnder(
        recipe: Recipe,
        rules: ChannelRules,
        on: Boolean,
    ): Long {
        val aired = (if (on) rules else HouseholdSources.without(rules, snapshot)) ?: return 0L
        val pool = PoolEvaluator.evaluate(aired, snapshot, KidStatus.policyOf(recipe))
        return airableRuntimeMs(pool.items, aired.toSchedulerSettings())
    }

    fun starterChannel(
        recipe: Recipe,
        number: Int?,
        state: ChannelState,
        rules: ChannelRules,
    ) = LineupChannel(
        id = LineupIds.starter(recipe.key),
        defaultNumber = number,
        numberOverride = null,
        nameOverride = null,
        state = state,
        source = ChannelSource.Starter(recipe.key, edited = false, rules = rules),
        onSinceMs = nowMs,
    )

    fun leftoversChannel(
        recipe: LeftoversRecipe,
        number: Int?,
        state: ChannelState,
    ) = LineupChannel(
        id = LineupIds.leftovers(recipe.key),
        defaultNumber = number,
        numberOverride = null,
        nameOverride = null,
        state = state,
        source = ChannelSource.Leftovers(recipe.key, recipe.maxRating),
        onSinceMs = nowMs,
    )
}
