// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.RatingMap
import tv.stationbreak.core.engine.SourceMatcher
import tv.stationbreak.core.engine.lineup.AnchorKind
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.foldNameAscii
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.rules.Source

// The adult-signal rule of a recipe with `Theme.adultSignalOnGenreEntry` (catalog 17, #1154), read by `admits`.

internal fun wantsAdultSignal(recipe: Recipe): Boolean = recipe.theme?.adultSignalOnGenreEntry == true

// PG-13 and TV-14 are level 3 and 4 of the rating map: the lowest level that is an adult signal on its own.
private const val ADULT_SIGNAL_LEVEL = 3

// A title the recipe's genre brings in shows an adult signal when its rating is PG-13 / TV-14 or higher (an unrated one
// has none), or when it reaches the channel another way: a tag source, a theme studio or studio anchor, or an anchor.
internal fun hasAdultSignal(
    recipe: Recipe,
    title: Title,
    anchored: Set<CanonicalId>,
): Boolean {
    if ((RatingMap.levelOf(title.rating) ?: -1) >= ADULT_SIGNAL_LEVEL || title.id in anchored) return true
    val theme = recipe.theme
    val tags = foldedDistinct(recipe.sources.filterIsInstance<Source.Tag>().map { it.name })
    val studioMatchers =
        theme?.themeStudios.orEmpty() +
            theme
                ?.anchors
                .orEmpty()
                .filter { it.kind == AnchorKind.Studio }
                .map { it.match }
    return title.tags.any { foldNameAscii(it) in tags } ||
        matchesAny(studioMatchers, title.studios.map(NameMatcher::fold))
}

/**
 * The titles of [pool] that an anchor of [channel] (a collection, series or item source of its rules) brings in; empty
 * unless the recipe asks for an adult signal, the only rule that reads it. Studio anchors are read by name instead.
 */
internal fun anchoredGroups(
    snapshot: RuleSnapshot,
    channel: CurationChannel,
    pool: List<ScheduleItem>,
): Set<CanonicalId> {
    val idSources = if (wantsAdultSignal(channel.recipe)) idSourcesOf(channel.rules.sources) else emptyList()
    if (idSources.isEmpty()) return emptySet()
    val matcher = SourceMatcher(idSources, snapshot)
    val ids = pool.mapTo(HashSet()) { it.id }
    val out = HashSet<CanonicalId>()
    for (entry in snapshot.items) {
        if (entry.item.id !in ids) continue
        val seriesId = entry.item.seriesId
        val series = if (seriesId == null) null else snapshot.series[seriesId]
        if (matcher.matches(entry, series)) out.add(entry.item.groupId)
    }
    return out
}
