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
 */
object LineupPlanner {
    /**
     * The lineup after a library sync.
     *
     * Until a lineup is finalised it is chosen afresh at [standardOffsetMinutes]. With fewer than
     * [LineupDefaults.FINALISE_THRESHOLD] filling non-opt-in recipes nothing is on yet (the lineup has no channels).
     * Otherwise the leftovers channels and the best-filling non-opt-in recipes (ties by key) are switched on up to
     * [LineupDefaults.CAP], and every other recipe goes on the shelf at its default number.
     *
     * A finalised lineup keeps its offset, its numbers and every switched-on or hidden channel's state. A shelved,
     * unedited starter that has a number and fills is switched on while there is room, best first; collection hints
     * are resolved once when the first full collection pass is seen; a newer catalog refreshes unedited starters
     * (keeping seed and timing), freezes starters whose recipe left as custom channels and shelves new recipes (with no
     * number when theirs is taken). A catalog older than the lineup's changes nothing.
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
    ): Lineup {
        if (current != null) {
            require(current.serverId == serverId && current.userId == userId) { "The lineup is another account's" }
            if (catalog.version < current.catalogVersion) return current
        }
        return if (current == null || !current.finalised) {
            val planning = Planning(serverId, standardOffsetMinutes, snapshot, collections, catalog, nowMs)
            firstChoice(planning, userId)
        } else {
            refresh(current, Planning(serverId, current.utcOffsetMinutes, snapshot, collections, catalog, nowMs))
        }
    }

    private fun firstChoice(
        p: Planning,
        userId: CanonicalId,
    ): Lineup {
        val rules = p.catalog.recipes.associate { it.key to p.resolve(it) }
        val ranked =
            p.ranked(
                p.catalog.recipes
                    .filter { !it.optIn }
                    .map { it to rules.getValue(it.key) },
            )
        val unfinalised = Lineup(p.serverId, userId, p.catalog.version, p.offset, false, false, emptyList())
        if (ranked.size < LineupDefaults.FINALISE_THRESHOLD) return unfinalised
        val leftovers = p.catalog.leftovers.map { p.leftoversChannel(it, it.number, ChannelState.On) }
        val on = ranked.take((LineupDefaults.CAP - leftovers.size).coerceAtLeast(0)).mapTo(HashSet()) { it.key }
        val starters =
            p.catalog.recipes.map {
                val state = if (it.key in on) ChannelState.On else ChannelState.Shelf
                p.starterChannel(it, it.number, state, rules.getValue(it.key))
            }
        return unfinalised.copy(
            finalised = true,
            hintsResolvedWithCollections = p.snapshot.collectionsSynced,
            channels = ordered(leftovers + starters),
        )
    }

    private fun refresh(
        current: Lineup,
        p: Planning,
    ): Lineup {
        val newer = p.catalog.version > current.catalogVersion
        val resolveHints = !current.hintsResolvedWithCollections && p.snapshot.collectionsSynced
        val refreshed = current.channels.map { refreshed(it, p, newer, resolveHints) }
        val withNew = if (newer) refreshed + added(refreshed, p) else refreshed
        return current.copy(
            catalogVersion = p.catalog.version,
            hintsResolvedWithCollections = current.hintsResolvedWithCollections || p.snapshot.collectionsSynced,
            channels = ordered(switchedOn(withNew, p)),
        )
    }

    // Catalog updates and the one-time hint pass. Edited, custom and unusable channels are never touched.
    private fun refreshed(
        channel: LineupChannel,
        p: Planning,
        newer: Boolean,
        resolveHints: Boolean,
    ): LineupChannel =
        when (val source = channel.source) {
            is ChannelSource.Starter -> {
                val recipe = p.recipe(source.recipeKey)
                when {
                    recipe == null -> {
                        if (newer) channel.copy(source = ChannelSource.Custom(source.rules)) else channel
                    }

                    source.edited || !(newer || resolveHints) -> {
                        channel
                    }

                    else -> {
                        val rules = p.resolve(recipe).copy(seed = source.rules.seed, timing = source.rules.timing)
                        channel.copy(source = source.copy(rules = rules))
                    }
                }
            }

            is ChannelSource.Leftovers -> {
                val recipe = p.catalog.leftovers.firstOrNull { it.key == source.recipeKey }
                val ceiling = recipe?.maxRating
                if (newer && recipe != null) channel.copy(source = source.copy(maxRating = ceiling)) else channel
            }

            is ChannelSource.Custom, is ChannelSource.Unusable -> {
                channel
            }
        }

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
            channel.copy(state = ChannelState.On, source = source, addedAtMs = p.nowMs)
        }
    }

    // Number order (channels without a number last), then ID, so the same lineup is always listed the same way.
    private fun ordered(channels: List<LineupChannel>): List<LineupChannel> =
        channels.sortedWith(compareBy<LineupChannel, Int?>(nullsLast()) { it.number }.thenBy { it.id.value })
}

/** What one call of [LineupPlanner.plan] works with. */
private class Planning(
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
                val pool = PoolEvaluator.evaluate(rules, snapshot)
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
        addedAtMs = nowMs,
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
        addedAtMs = nowMs,
    )
}
