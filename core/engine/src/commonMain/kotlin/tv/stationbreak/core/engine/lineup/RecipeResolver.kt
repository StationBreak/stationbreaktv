// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.foldAscii
import tv.stationbreak.core.engine.settingsOf
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.rules.AutoSeed
import tv.stationbreak.core.rules.AutoSeedKind
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing

private const val FORMAT_VERSION = 1

/** Turns a catalog recipe into the rules of a real channel. Pure: the same inputs give the same rules. */
object RecipeResolver {
    /**
     * The rules for [recipe] on [serverId]. The sources are the recipe's own, then a collection source for every
     * collection in [collections] whose name equals a hint (ASCII case-insensitive, as everywhere in the rules format),
     * in canonical-ID order and once each. A hint that matches nothing adds nothing. The broadcast day starts at
     * [LineupDefaults.DAY_START] at [utcOffsetMinutes]; every other timing field is left to its default.
     */
    fun resolve(
        recipe: Recipe,
        serverId: CanonicalId,
        utcOffsetMinutes: Int,
        collections: CollectionNames,
    ): ChannelRules =
        ChannelRules(
            formatVersion = FORMAT_VERSION,
            name = recipe.name,
            seed = seedOf(serverId, recipe.key),
            sources = recipe.sources + hinted(recipe.collectionHints, collections),
            exclusions = recipe.exclusions.ifEmpty { null },
            maxRating = recipe.maxRating,
            ordering = recipe.ordering?.let { Ordering(it) },
            timing = timingAt(utcOffsetMinutes),
            filler = null,
        )

    /**
     * The scheduler settings of a leftovers channel: those of a resolved recipe with the same key, no ordering of its
     * own (shuffle) and the default timing. Its pool is the engine's business, not the rules'.
     */
    fun leftoversSettings(
        recipe: LeftoversRecipe,
        serverId: CanonicalId,
        utcOffsetMinutes: Int,
    ): SchedulerSettings = settingsOf(OrderingMode.Shuffle, seedOf(serverId, recipe.key), timingAt(utcOffsetMinutes))

    private fun seedOf(
        serverId: CanonicalId,
        key: String,
    ) = AutoSeed.derive(serverId.value, AutoSeedKind.Recipe, key)

    private fun timingAt(utcOffsetMinutes: Int) =
        Timing(
            blockLength = null,
            alignToGrid = null,
            epochHours = null,
            graceHours = null,
            gridMinutes = null,
            minRuntimeSeconds = null,
            broadcastDay = BroadcastDay(utcOffsetMinutes, LineupDefaults.DAY_START),
        )

    private fun hinted(
        hints: List<String>,
        collections: CollectionNames,
    ): List<Source> {
        if (hints.isEmpty()) return emptyList()
        val wanted = hints.mapTo(HashSet()) { foldAscii(it) }
        return collections.ids
            .filter { id -> collections[id]?.let { foldAscii(it) in wanted } == true }
            .map { Source.Collection(it) }
    }
}
