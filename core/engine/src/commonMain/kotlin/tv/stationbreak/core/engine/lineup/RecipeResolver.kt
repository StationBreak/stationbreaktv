// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.engine.foldAscii
import tv.stationbreak.core.engine.settingsOf
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.LineupLimits
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.rules.AutoSeed
import tv.stationbreak.core.rules.AutoSeedKind
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.RulesWriter
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing

private const val LATEST_FORMAT = 3

// The two quotes around a JSON string.
private const val QUOTES = 2

/** Turns a catalog recipe into the rules of a real channel. Pure: the same inputs give the same rules. */
object RecipeResolver {
    /** The most sources a resolved starter keeps, whatever the budget allows. */
    const val MAX_STARTER_SOURCES: Int = 48

    /** Room for catalog starters in the lineup file: today's catalog has 97, shelved ones included. */
    const val MAX_CATALOG_STARTERS: Int = 120

    /**
     * The most bytes one copy of a starter's rules takes in the lineup file (the rules text as a JSON string, escapes
     * included). The file holds the rules of every catalog starter, and the rules again as `previousRules` for at most
     * [LineupDefaults.CAP] of them (the planner gives a previous copy only to its keepers, at most
     * [LineupDefaults.CAP]: the protected starters that hold one already, then the switched-on starters by number,
     * however many the viewer switches on; any other starter takes new rules at once and holds no copy):
     * [MAX_CATALOG_STARTERS] + [LineupDefaults.CAP] copies,
     * so all those copies together total at most half the file cap. The channel envelopes (IDs, numbers, state), the
     * leftovers, the custom channels, the curation basis and the meta sit in the other half; this bounds the copies
     * only, not each starter's whole entry.
     */
    const val STARTER_RULES_BUDGET_BYTES: Int =
        (LineupLimits.MAX_FILE_BYTES / 2) / (MAX_CATALOG_STARTERS + LineupDefaults.CAP)

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
     * is dropped (studio sources by folded name), and the first ones that fit [MAX_STARTER_SOURCES] and [STARTER_RULES_BUDGET_BYTES]
     * are kept (so theme studios are cut first). Its ordering is curated. The format version is the lowest the rules
     * need.
     */
    fun resolve(
        recipe: Recipe,
        serverId: CanonicalId,
        utcOffsetMinutes: Int,
        collections: CollectionNames,
        names: LibraryNames,
    ): ChannelRules {
        val theme = recipe.theme
        val candidates =
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
                sources = candidates.take(1),
                exclusions = recipe.exclusions.ifEmpty { null },
                maxRating = recipe.maxRating,
                ordering = (if (theme == null) recipe.ordering else OrderingMode.Curated)?.let { Ordering(it) },
                timing = timingAt(utcOffsetMinutes),
                filler = null,
                filters = recipe.filters,
            )
        val fitted = rules.copy(sources = withinBudget(rules, candidates))
        return fitted.copy(formatVersion = fitted.requiredVersion)
    }

    // The leading [candidates] that fit [MAX_STARTER_SOURCES] and [STARTER_RULES_BUDGET_BYTES], always at least the
    // first. The rules with the first source alone ([shell]) are measured once; each further source adds its own entry
    // and the separator before it (`RulesWriter.sourceEntry`), measured on its own as the file escapes it, so the
    // sizes add up exactly and the rules are never written whole per source.
    private fun withinBudget(
        shell: ChannelRules,
        candidates: List<Source>,
    ): List<Source> {
        if (candidates.size <= 1) return candidates
        var used = fileBytes(shell)
        var count = 1
        while (count < candidates.size && count < MAX_STARTER_SOURCES) {
            val cost = escapedBytes(RulesWriter.SOURCE_SEPARATOR + RulesWriter.sourceEntry(candidates[count]))
            if (used + cost > STARTER_RULES_BUDGET_BYTES) break
            used += cost
            count++
        }
        return candidates.take(count)
    }

    // What [rules] take in the lineup file: the rules text held as a JSON string.
    private fun fileBytes(rules: ChannelRules): Int = escapedBytes(RulesWriter.write(rules)) + QUOTES

    // The bytes [text] takes inside a JSON string, its quotes left out. Escaping goes character by character, so the
    // escaped parts of a text add up to the escaped whole.
    private fun escapedBytes(text: String): Int = JsonPrimitive(text).toString().encodeToByteArray().size - QUOTES

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
        return out.toList()
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
