// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItemKind

/**
 * The exclusive audience tiers of `kid-hold-6` (ADR-0028, #576), with the moves up of `kid-hold-7` (ADR-0029) and the
 * [Explicit] tier of `kid-hold-8` (ADR-0028 addendum 2026-11-22, #1078). Every title is in exactly one; a rejection
 * only moves it up, toward [Explicit]. Unrated and unknown ratings are [Adult]. In declaration order, lowest first.
 * Frozen, with its lists, under `KidHold.VERSION_NAME`.
 */
enum class RatingTier {
    /** A kid rating (level ALL or 7) plus made-for-kids evidence (`KidHold.isMadeForKids`), and no veto. */
    Kids,

    /**
     * Up to PG / TV-PG (level 10), not kids, no veto. A violence-heavy tag or the Horror genre sends it to [Teen]; the
     * hand-kept bump list ([KidBumpList]) sends a kids-tier series here.
     */
    Family,

    /** PG-13 / TV-14 (levels 13 and 14), no explicit tag or adult genre. */
    Teen,

    /** Everything else: R and up, unrated, unknown, a missing series, and what a veto sent up. */
    Adult,

    /**
     * `kid-hold-8` (#1078): an Explicit rating ([RatingMap.isExplicit]), genre ([EXPLICIT_TIER_GENRES]) or tag
     * ([EXPLICIT_TIER_TAGS]) on the title or its known series, anime or not. It airs on After Hours alone.
     */
    Explicit,
    ;

    companion object {
        /**
         * Tags that send a title to [Adult] whatever its rating (folded, exact): explicit and sexual content. Those
         * that are also in [EXPLICIT_TIER_TAGS] send it further, to [Explicit]. `kid-hold-8` adds `sex comedy`,
         * `teen sex comedy` and `erotic thriller`.
         */
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
                "sex comedy",
                "teen sex comedy",
                "erotic thriller",
            )

        /**
         * Tags that send a title to [Explicit] (`kid-hold-8`, #1078; folded, exact): the TMDb keywords of explicit
         * material (verified on 2026-11-22), and the spellings of other scrapers (AniDB, AniList, NFO). The plot
         * keywords `porn industry` and `porn star` are not here on purpose, nor `ecchi`, `nudity`, `harem`, `sex`,
         * `sex comedy`, `erotic` or `erotic thriller`, which stay [Adult].
         */
        val EXPLICIT_TIER_TAGS: List<String> =
            listOf(
                "hentai",
                "pornography",
                "porn",
                "pornographic video",
                "pornographic animation",
                "porn films",
                "porn classic",
                "softcore",
                "sexploitation",
                "sexsploitation",
                "erotic movie",
                "erotic film",
                "erotica",
                "vintage erotica",
                "x rated",
                "explicite sex",
                "adult video",
                "x-rated",
                "explicit sex",
                "adult film",
                "18 restricted",
            )

        /** Genres that send a title to [Explicit] (`kid-hold-8`, #1078; folded, exact). `ecchi` stays [Adult]. */
        val EXPLICIT_TIER_GENRES: List<String> =
            listOf("hentai", "adult", "erotica", "erotic", "porn", "pornography", "sexploitation")

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

        /**
         * Tags that keep a PG-or-below title out of [Family] (folded, exact): violence-heavy content (#988).
         * `murder` and `killer` alone are not here. A kid-rated title with made-for-kids evidence stays [Kids] (the
         * kids rule is unchanged); `decapitation` is also in [GORE_TAGS], which already kept it out of both.
         */
        val FAMILY_VETO_TAGS: List<String> = listOf("mass murder", "decapitation", "child-death")

        /**
         * Genres that keep a PG-or-below title out of [Family] (folded, exact): horror (#1001). Thriller and Mystery do
         * not. As [FAMILY_VETO_TAGS], a title that is [Kids] stays [Kids].
         */
        val FAMILY_VETO_GENRES: List<String> = listOf("horror")

        private const val KID_LEVEL = 1

        /** The highest level of [Family] (PG, TV-PG) and of [Teen] (TV-14), on [RatingMap]. */
        internal const val FAMILY_LEVEL = 2
        internal const val TEEN_LEVEL = 4

        private val explicitTierTags = EXPLICIT_TIER_TAGS.toSet()
        private val explicitTierGenres = EXPLICIT_TIER_GENRES.toSet()
        private val explicitTags = EXPLICIT_TAGS.toSet()
        private val adultGenres = ADULT_GENRES.toSet()
        private val goreTags = GORE_TAGS.toSet()
        private val familyVetoTags = FAMILY_VETO_TAGS.toSet()
        private val familyVetoGenres = FAMILY_VETO_GENRES.toSet()

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
                explicitTier(entry, series) -> Explicit
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
        ): RatingTier =
            when {
                level <= KID_LEVEL && KidHold.isMadeForKids(entry, series) && !KidBumpList.lists(series) -> Kids
                familyVeto(entry, series) -> Teen
                else -> Family
            }

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

        // Fail up: the entry's own rating counts as well as its series', whichever decides the level.
        private fun explicitTier(
            entry: RuleItem,
            series: RuleSeries?,
        ): Boolean =
            RatingMap.isExplicit(entry.officialRating) ||
                anyIn(explicitTierTags, entry.foldedTags) ||
                anyIn(explicitTierGenres, entry.foldedGenres) ||
                (
                    series != null &&
                        (
                            RatingMap.isExplicit(series.officialRating) ||
                                anyIn(explicitTierTags, series.foldedTags) ||
                                anyIn(explicitTierGenres, series.foldedGenres)
                        )
                )

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

        private fun familyVeto(
            entry: RuleItem,
            series: RuleSeries?,
        ): Boolean =
            anyIn(familyVetoTags, entry.foldedTags) ||
                anyIn(familyVetoGenres, entry.foldedGenres) ||
                (
                    series != null &&
                        (anyIn(familyVetoTags, series.foldedTags) || anyIn(familyVetoGenres, series.foldedGenres))
                )

        // Genre and tag names are tidied before the match (#1088): a no-break space or an en dash does not hide one.
        private fun anyIn(
            set: Set<String>,
            folded: List<String>,
        ): Boolean {
            for (index in folded.indices) if (NoiseTidy.tidy(folded[index]) in set) return true
            return false
        }
    }
}
