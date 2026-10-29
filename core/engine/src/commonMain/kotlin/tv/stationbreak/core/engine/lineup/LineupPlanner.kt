// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.airableRuntimeMs
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
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
 * - Rules resolved again (on a synced call, or from a newer catalog) take effect at once, so the schedule of that
 *   channel for the day may reshuffle once.
 * - While a newer catalog waits for a full collection pass, a shelved starter that switches on is resolved from the
 *   newer catalog's recipe, until that catalog is applied.
 * - What happens when the user's number override lands on another channel's default number is left to F2.4. Today a
 *   new recipe treats both default and override numbers as taken, so it never shares one.
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
        catalog: StarterCatalogView = StarterCatalog,
        withoutCollections: Boolean = false,
    ): Lineup {
        if (current != null) {
            require(current.serverId == serverId && current.userId == userId) { "The lineup is another account's" }
            if (catalog.version < current.catalogVersion) return current
        }
        return if (current == null || !current.finalised) {
            val planning = Planning(serverId, standardOffsetMinutes, snapshot, collections, catalog, nowMs)
            firstChoice(planning, userId, withoutCollections)
        } else {
            val planning = Planning(serverId, current.utcOffsetMinutes, snapshot, collections, catalog, nowMs)
            refresh(current, planning, withoutCollections)
        }
    }

    private fun firstChoice(
        p: Planning,
        userId: CanonicalId,
        withoutCollections: Boolean,
    ): Lineup {
        val unfinalised = Lineup(p.serverId, userId, p.catalog.version, p.offset, false, emptyList())
        return if (p.snapshot.collectionsSynced || withoutCollections) chosen(p, unfinalised) else unfinalised
    }

    // The first choice proper, made once the collection pass is in (or not waited for): nothing is on yet below the
    // threshold.
    private fun chosen(
        p: Planning,
        unfinalised: Lineup,
    ): Lineup {
        val rules = p.catalog.recipes.associate { it.key to p.resolve(it) }
        val ranked =
            p.ranked(
                p.catalog.recipes
                    .filter { !it.optIn }
                    .map { it to rules.getValue(it.key) },
            )
        if (ranked.size < LineupDefaults.FINALISE_THRESHOLD) return unfinalised
        val leftovers = p.catalog.leftovers.map { p.leftoversChannel(it, it.number, ChannelState.On) }
        val on = ranked.take((LineupDefaults.CAP - leftovers.size).coerceAtLeast(0)).mapTo(HashSet()) { it.key }
        val starters =
            p.catalog.recipes.map {
                val state = if (it.key in on) ChannelState.On else ChannelState.Shelf
                p.starterChannel(it, it.number, state, rules.getValue(it.key))
            }
        return unfinalised.copy(finalised = true, channels = ordered(leftovers + starters))
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
        return current.copy(
            catalogVersion = if (newer) p.catalog.version else current.catalogVersion,
            channels = ordered(switchedOn(withNew, p)),
        )
    }

    // Starters stored before the app wrote `alignToGrid: false` have no value, which the format reads as the grid. An
    // unedited one is set to back to back, once and whatever the catalog version (the refresh below keeps the stored
    // timing, so it would never get there otherwise). An edited or custom channel is never touched.
    private fun backToBack(channel: LineupChannel): LineupChannel {
        val source = channel.source as? ChannelSource.Starter
        if (source == null || source.edited || source.rules.timing.alignToGrid != null) return channel
        val rules = source.rules.copy(timing = source.rules.timing.copy(alignToGrid = false))
        return channel.copy(source = source.copy(rules = rules))
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
                        if (newer) channel.copy(source = ChannelSource.Custom(source.rules)) else channel
                    }

                    source.edited || !(newer || rehinted(channel, p)) -> {
                        channel
                    }

                    else -> {
                        val rules = p.resolve(recipe).copy(seed = source.rules.seed, timing = source.rules.timing)
                        channel.copy(source = source.copy(rules = rules))
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
            val source = ChannelSource.Starter(recipe.key, edited = false, rules = rules.getValue(recipe.key))
            channel.copy(state = ChannelState.On, source = source, onSinceMs = p.nowMs)
        }
    }

    // Number order (channels without a number last), then ID, so the same lineup is always listed the same way.
    private fun ordered(channels: List<LineupChannel>): List<LineupChannel> =
        channels.sortedWith(compareBy<LineupChannel, Int?>(nullsLast()) { it.number }.thenBy { it.id.value })
}

/** What one call of [LineupPlanner.plan] works with. Internal only so LineupPlannerTest can call `ranked`. */
internal class Planning(
    val serverId: CanonicalId,
    val offset: Int,
    val snapshot: RuleSnapshot,
    val collections: CollectionNames,
    val catalog: StarterCatalogView,
    val nowMs: Long,
) {
    private val recipes = catalog.recipes.associateBy { it.key }

    fun recipe(key: String): Recipe? = recipes[key]

    fun resolve(recipe: Recipe): ChannelRules = RecipeResolver.resolve(recipe, serverId, offset, collections)

    /** The [candidates] that fill, by airable runtime (most first), ties by key. Each pool is evaluated once. */
    fun ranked(candidates: List<Pair<Recipe, ChannelRules>>): List<Recipe> =
        candidates
            .mapNotNull { (recipe, rules) ->
                val pool = PoolEvaluator.evaluate(rules, snapshot, KidStatus.policyOf(recipe))
                val runtime = airableRuntimeMs(pool.items, rules.toSchedulerSettings())
                if (runtime >= LineupDefaults.MIN_AIRABLE_RUNTIME_MS) recipe to runtime else null
            }.sortedWith(compareByDescending<Pair<Recipe, Long>> { it.second }.thenBy { it.first.key })
            .map { it.first }

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
