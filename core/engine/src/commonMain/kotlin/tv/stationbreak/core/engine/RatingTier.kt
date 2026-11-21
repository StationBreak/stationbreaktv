// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItemKind

/**
 * The four exclusive audience tiers of `kid-hold-6` (ADR-0028, #576). Every title is in exactly one; a rejection only
 * moves it up, toward [Adult]. Unrated and unknown ratings are [Adult]. Frozen, with its lists, under
 * `KidHold.VERSION_NAME`.
 */
enum class RatingTier {
    /** A kid rating (level ALL or 7) plus made-for-kids evidence (`KidHold.isMadeForKids`), and no veto. */
    Kids,

    /** Up to PG / TV-PG (level 10), not kids, no veto. */
    Family,

    /** PG-13 / TV-14 (levels 13 and 14), no explicit tag or adult genre. */
    Teen,

    /** Everything else: R and up, unrated, unknown, a missing series, and what a veto sent up. */
    Adult,
    ;

    companion object {
        /** Tags that send a title to [Adult] whatever its rating (folded, exact): explicit and sexual content. */
        val EXPLICIT_TAGS: List<String> =
            listOf(
                "hentai",
                "ecchi",
                "nudity",
                "female nudity",
                "male nudity",
                "full frontal nudity",
                "nude",
                "sex",
                "sex scene",
                "explicit sex",
                "sexual content",
                "sexuality",
                "rape",
                "incest",
                "harem",
                "breast",
                "breasts",
                "erotic",
                "erotica",
                "erotic movie",
                "pornography",
                "porn",
                "softcore",
                "sexploitation",
            )

        /** Genres that send a title to [Adult] whatever its rating (folded, exact). */
        val ADULT_GENRES: List<String> =
            listOf("adult", "erotic", "erotica", "hentai", "ecchi", "porn", "pornography", "sexploitation")

        /** Tags that keep a title out of [Kids] and [Family] (folded, exact): gore. It can still be [Teen]. */
        val GORE_TAGS: List<String> =
            listOf(
                "gore",
                "gory",
                "splatter",
                "splatter film",
                "body horror",
                "graphic violence",
                "extreme violence",
                "torture",
                "dismemberment",
                "decapitation",
                "cannibalism",
            )

        private const val KID_LEVEL = 1
        private const val FAMILY_LEVEL = 2
        private const val TEEN_LEVEL = 4

        private val explicitTags = EXPLICIT_TAGS.toSet()
        private val adultGenres = ADULT_GENRES.toSet()
        private val goreTags = GORE_TAGS.toSet()

        /**
         * The tier of [entry], with [series] its series from the snapshot (null when it has none or the snapshot lacks
         * it). The rating is [decisiveLevel]; tags and genres are the entry's and its known series'.
         */
        fun of(
            entry: RuleItem,
            series: RuleSeries?,
        ): RatingTier {
            val level = decisiveLevel(entry, series)
            return when {
                level == null || explicit(entry, series) -> Adult
                level <= FAMILY_LEVEL && !gory(entry, series) -> kidsOrFamily(level, entry, series)
                level <= TEEN_LEVEL -> Teen
                else -> Adult
            }
        }

        private fun kidsOrFamily(
            level: Int,
            entry: RuleItem,
            series: RuleSeries?,
        ): RatingTier = if (level <= KID_LEVEL && KidHold.isMadeForKids(entry, series)) Kids else Family

        /**
         * The rating level ([RatingMap]) that decides for [entry]: a movie's or a series-less episode's own; for an
         * episode of a known series, the series' when present, else the episode's own (`EpisodeRating.decisive`,
         * `kid-hold-5`). Null when unknown, unrated, or the episode's series is missing from the snapshot.
         */
        fun decisiveLevel(
            entry: RuleItem,
            series: RuleSeries?,
        ): Int? {
            val item = entry.item
            val rating =
                when {
                    item.kind == ScheduleItemKind.Movie || item.seriesId == null -> entry.officialRating
                    series == null -> return null
                    else -> EpisodeRating.decisive(entry, series)
                }
            return RatingMap.levelOf(rating)
        }

        /** Whether [entry]'s decisive rating is a kid one (ALL or 7). */
        internal fun isKidRated(
            entry: RuleItem,
            series: RuleSeries?,
        ): Boolean = (decisiveLevel(entry, series) ?: Int.MAX_VALUE) <= KID_LEVEL

        private fun explicit(
            entry: RuleItem,
            series: RuleSeries?,
        ): Boolean =
            anyIn(explicitTags, entry.foldedTags) ||
                anyIn(adultGenres, entry.foldedGenres) ||
                (series != null && (anyIn(explicitTags, series.foldedTags) || anyIn(adultGenres, series.foldedGenres)))

        private fun gory(
            entry: RuleItem,
            series: RuleSeries?,
        ): Boolean = anyIn(goreTags, entry.foldedTags) || (series != null && anyIn(goreTags, series.foldedTags))

        private fun anyIn(
            set: Set<String>,
            folded: List<String>,
        ): Boolean {
            for (index in folded.indices) if (folded[index] in set) return true
            return false
        }
    }
}
