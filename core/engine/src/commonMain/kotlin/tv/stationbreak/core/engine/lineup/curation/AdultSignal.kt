// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.AndroidTvRatingSystems
import tv.stationbreak.core.engine.CountryRatingTables
import tv.stationbreak.core.engine.RatingMap
import tv.stationbreak.core.engine.SourceMatcher
import tv.stationbreak.core.engine.lineup.AnchorKind
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.foldNameAscii
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.rules.RatingLadder
import tv.stationbreak.core.rules.Source

// The adult-signal rule of a recipe with `Theme.adultSignalOnGenreEntry` (catalog 17, #1154), read by `admits`.

internal fun wantsAdultSignal(recipe: Recipe): Boolean = recipe.theme?.adultSignalOnGenreEntry == true

// PG-13 is rank 3 and TV-14 rank 4 on the US ladder: the lowest rank that is an adult signal on its own.
private const val ADULT_LADDER_RANK = 3

// The least age a country rating may mean, unrounded, that still counts as an adult signal (PG-13 / TV-14).
private const val ADULT_MIN_AGE = 13

private const val ADULT_COUNTRY_LEVEL = 3

/**
 * Whether [raw] says PG-13 / TV-14 or higher, read at its YOUNGEST plausible age: this is an admission test, so a
 * doubtful rating is no signal (unlike `RatingMap`, which reads at the oldest age to exclude). Either the US ladder
 * ([RatingLadder]) names it, or it has an explicit country prefix (`DE-16`) whose XML age hint, unrounded and the
 * lowest of the systems that share the spelling, is 13 or more AND whose `RatingMap` level is 3 or more (so an owner
 * override such as AU PG, which is 10, still wins). A bare single letter, an alias other than PG13, a bare age and a
 * list of ratings are no signal; 11 and 12 are not rounded up to 13.
 */
internal fun isAdultRating(raw: String?): Boolean {
    val key = raw?.trim(' ')?.uppercase().orEmpty()
    val ladder = RatingLadder.rankOf(key)
    return when {
        key.isEmpty() -> false
        ladder != null -> ladder >= ADULT_LADDER_RANK
        key == "PG13" -> true
        else -> countryAge(key) >= ADULT_MIN_AGE && (RatingMap.levelOf(key) ?: -1) >= ADULT_COUNTRY_LEVEL
    }
}

// The lowest XML age hint of a prefixed [key] (`SE-11`), or -1 when it has no known country prefix or spelling.
private fun countryAge(key: String): Int {
    val (code, rest) = CountryRatingTables.countryPrefix(key) ?: return -1
    val spelling = rest.replace(" ", "")
    var youngest = Int.MAX_VALUE
    for (system in AndroidTvRatingSystems.SYSTEMS) {
        if (code !in system.countries) continue
        for (rating in system.ratings) {
            val named = rating.names.any { it.replace(" ", "") == spelling || it.removeSuffix("+") == spelling }
            if (named) youngest = minOf(youngest, rating.ageHint)
        }
    }
    return if (youngest == Int.MAX_VALUE) -1 else youngest
}

// A title the recipe's genre brings in shows an adult signal when every rated part of it is PG-13 / TV-14 or higher
// (`Title.adultRated`; an unrated one has none), or when it reaches the channel another way: a tag source, a theme
// studio or studio anchor, or an anchor.
internal fun hasAdultSignal(
    recipe: Recipe,
    title: Title,
    anchored: Set<CanonicalId>,
): Boolean {
    if (title.adultRated || title.id in anchored) return true
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
