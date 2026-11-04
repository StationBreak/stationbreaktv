// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.lineup.CurationTables
import tv.stationbreak.core.engine.lineup.NameMatcher
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.rules.RatingLadder

/** How a title is made: live action, animation, or anime. */
enum class TitleFormat { Live, Animation, Anime }

/**
 * One title the curation homes: a series (all its episodes together) or a film or series-less episode. Names are the
 * stored ones, genres and tags and studios as the server gave them (series first, then episodes, distinct).
 */
@Suppress("LongParameterList") // A plain carrier: one field per fact the curation reads.
internal class Title(
    val id: CanonicalId,
    val isSeries: Boolean,
    val genres: List<String>,
    val tags: List<String>,
    val studios: List<String>,
    val rating: String?,
    val format: TitleFormat,
    val madeForKids: Boolean,
    val adultAnimation: Boolean,
)

private const val ADULT_RANK = 4
private const val UNKNOWN_RANK = -1

internal object Titles {
    /**
     * The titles of [items], by ID. A series' ID is its title's ID; a film and an episode with no series are their
     * own. An episode whose series the snapshot lacks is grouped by its series ID and judged on its own names. A
     * title's facts come from the snapshot, not from [items]: a series takes its own fields and those of every one of
     * its episodes in the snapshot, in snapshot order, so which items are passed, and in what order, changes no fact.
     * One pass over the snapshot groups the wanted titles' entries.
     */
    fun of(
        snapshot: RuleSnapshot,
        items: Collection<ScheduleItem>,
    ): Map<CanonicalId, Title> {
        val wanted = HashSet<CanonicalId>(items.size * 2)
        for (item in items) wanted.add(item.groupId)
        val groups = LinkedHashMap<CanonicalId, MutableList<RuleItem>>()
        for (entry in snapshot.items) {
            val group = entry.item.groupId
            if (group !in wanted) continue
            groups.getOrPut(group) { ArrayList() }.add(entry)
        }
        val titles = LinkedHashMap<CanonicalId, Title>(groups.size * 2)
        for ((id, entries) in groups) {
            val first = entries[0]
            val series = if (first.item.seriesId != null) snapshot.series[id] else null
            titles[id] = build(id, first.item.seriesId != null, series, entries)
        }
        return titles
    }

    private fun build(
        id: CanonicalId,
        isSeries: Boolean,
        series: RuleSeries?,
        entries: List<RuleItem>,
    ): Title {
        val genres = LinkedHashSet<String>()
        val tags = LinkedHashSet<String>()
        val studios = LinkedHashSet<String>()
        if (series != null) {
            genres.addAll(series.genres)
            tags.addAll(series.tags)
            studios.addAll(series.studios)
        }
        for (entry in entries) {
            genres.addAll(entry.genres)
            tags.addAll(entry.tags)
            studios.addAll(entry.studios)
        }
        val rating: String? = series?.officialRating ?: highestRating(entries)
        val folded = Folded(entries, series)
        val format = formatOf(folded)
        val rank = RatingLadder.rankOf(rating) ?: UNKNOWN_RANK
        val adult =
            format == TitleFormat.Animation &&
                (rank >= ADULT_RANK || matchesAny(CurationTables.ADULT_ANIMATION_NETWORKS, folded.studios))
        return Title(
            id = id,
            isSeries = isSeries,
            genres = genres.toList(),
            tags = tags.toList(),
            studios = studios.toList(),
            rating = rating,
            format = format,
            madeForKids = madeForKids(series, entries),
            adultAnimation = adult,
        )
    }

    // The highest-ranked episode rating, ties (and ratings off the ladder) by the smaller string, so neither the order
    // nor the subset of episodes decides it. Null when no episode has one.
    private fun highestRating(entries: List<RuleItem>): String? {
        var best: String? = null
        var bestRank = UNKNOWN_RANK
        for (entry in entries) {
            val rating = entry.officialRating ?: continue
            val rank = RatingLadder.rankOf(rating) ?: UNKNOWN_RANK
            val better = rank > bestRank || (rank == bestRank && rating < best.orEmpty())
            if (best == null || better) {
                best = rating
                bestRank = rank
            }
        }
        return best
    }

    private fun madeForKids(
        series: RuleSeries?,
        entries: List<RuleItem>,
    ): Boolean {
        if (series != null) return KidHold.isSeriesMadeForKids(series)
        var found = false
        for (entry in entries) found = found || KidHold.isMadeForKids(entry, null)
        return found
    }

    private class Folded(
        entries: List<RuleItem>,
        series: RuleSeries?,
    ) {
        val genres = LinkedHashSet<String>()
        val tags = LinkedHashSet<String>()
        val studios = LinkedHashSet<String>()

        init {
            if (series != null) {
                genres.addAll(series.foldedGenres)
                tags.addAll(series.foldedTags)
                studios.addAll(series.foldedStudios)
            }
            for (entry in entries) {
                genres.addAll(entry.foldedGenres)
                tags.addAll(entry.foldedTags)
                studios.addAll(entry.foldedStudios)
            }
        }
    }

    private fun formatOf(f: Folded): TitleFormat {
        val animation = "animation" in f.genres
        return when {
            "anime" in f.genres || "anime" in f.tags -> TitleFormat.Anime
            animation && matchesAny(CurationTables.ANIME_STUDIOS, f.studios) -> TitleFormat.Anime
            animation -> TitleFormat.Animation
            else -> TitleFormat.Live
        }
    }

    private fun matchesAny(
        matchers: List<NameMatcher>,
        foldedNames: Collection<String>,
    ): Boolean {
        for (name in foldedNames) for (matcher in matchers) if (matcher.matches(name)) return true
        return false
    }
}
