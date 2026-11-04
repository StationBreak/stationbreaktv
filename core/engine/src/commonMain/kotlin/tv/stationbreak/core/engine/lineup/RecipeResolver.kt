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
import tv.stationbreak.core.rules.RulesLimits
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing

private const val LATEST_FORMAT = 3

/** Turns a catalog recipe into the rules of a real channel. Pure: the same inputs give the same rules. */
object RecipeResolver {
    /**
     * The rules for [recipe] on [serverId]. The sources are the recipe's own, then a collection source for every
     * collection in [collections] whose name equals a hint (ASCII case-insensitive, as everywhere in the rules format),
     * in canonical-ID order and once each. A hint that matches nothing adds nothing. The broadcast day starts at
     * [LineupDefaults.DAY_START] at [utcOffsetMinutes]. `alignToGrid` is written as false, so the channel runs back to
     * back (the format's own default is true); every other timing field is left to its default.
     *
     * A themed recipe has its own sources, then for each anchor in turn: a collection source for every collection that
     * matches (the household's own included; the day build drops those when "Use my collections" is off), the series
     * and films that match a title anchor, or a studio source; then a studio source for each studio of [names] a theme
     * studio matches. Anchors come before theme studios so the cap never cuts an anchor first. A source already present
     * is dropped (studio sources by folded name), and at most 256 are kept, the first ones. Its ordering is curated.
     * The format version is the lowest the rules need.
     */
    fun resolve(
        recipe: Recipe,
        serverId: CanonicalId,
        utcOffsetMinutes: Int,
        collections: CollectionNames,
        names: LibraryNames,
    ): ChannelRules {
        val theme = recipe.theme
        val sources =
            if (theme == null) {
                recipe.sources + hinted(recipe.collectionHints, collections)
            } else {
                themed(recipe, theme, names)
            }
        val rules =
            ChannelRules(
                formatVersion = LATEST_FORMAT,
                name = recipe.name,
                seed = seedOf(serverId, recipe.key),
                sources = sources,
                exclusions = recipe.exclusions.ifEmpty { null },
                maxRating = recipe.maxRating,
                ordering = (if (theme == null) recipe.ordering else OrderingMode.Curated)?.let { Ordering(it) },
                timing = timingAt(utcOffsetMinutes),
                filler = null,
                filters = recipe.filters,
            )
        return rules.copy(formatVersion = rules.requiredVersion)
    }

    private fun themed(
        recipe: Recipe,
        theme: Theme,
        names: LibraryNames,
    ): List<Source> {
        val out = LinkedHashSet<Source>()
        val studios = HashSet<String>()

        fun addStudio(name: String) {
            if (studios.add(NameMatcher.fold(name))) out.add(Source.Studio(name))
        }
        for (source in recipe.sources) if (source is Source.Studio) addStudio(source.name) else out.add(source)
        for (anchor in theme.anchors) {
            when (anchor.kind) {
                AnchorKind.Collection -> for (id in names.collections(anchor.match)) out.add(Source.Collection(id))
                AnchorKind.Title -> out.addAll(names.titles(anchor.match))
                AnchorKind.Studio -> for (studio in names.studios(anchor.match)) addStudio(studio)
            }
        }
        for (matcher in theme.themeStudios) for (studio in names.studios(matcher)) addStudio(studio)
        return out.take(RulesLimits.MAX_LIST_ENTRIES)
    }

    /**
     * The scheduler settings of a leftovers channel: those of a resolved recipe with the same key, no ordering of its
     * own (shuffle) and the same back-to-back timing. Its pool is the engine's business, not the rules'.
     */
    fun leftoversSettings(
        recipe: LeftoversRecipe,
        serverId: CanonicalId,
        utcOffsetMinutes: Int,
    ): SchedulerSettings =
        settingsOf(
            OrderingMode.Shuffle,
            seedOf(serverId, recipe.key),
            timingAt(utcOffsetMinutes),
            caps = null,
            runtimeFilter = null,
            // A leftovers channel shuffles: it has no curated tuning.
            curated = null,
        )

    private fun seedOf(
        serverId: CanonicalId,
        key: String,
    ) = AutoSeed.derive(serverId.value, AutoSeedKind.Recipe, key)

    private fun timingAt(utcOffsetMinutes: Int) =
        Timing(
            blockLength = null,
            alignToGrid = false,
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
