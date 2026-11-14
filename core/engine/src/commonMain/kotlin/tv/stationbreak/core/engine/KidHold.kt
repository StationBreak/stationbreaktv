// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.studioKey2
import tv.stationbreak.core.rules.RatingLadder

/**
 * The kid hold (spec 18, "The kid hold"): what a held channel will air. A held channel airs only what is made for
 * children ([isMadeForKids]) and that passes a kid rating table ([passesKidCeiling]). It is pure: it reads the entry
 * and its series and nothing else. Tags never take part. The lists below are frozen under [VERSION_NAME]; a change to
 * either is a new version name. `kid-hold-3` adds the animated-family signal ([isAnimatedFamily]; ADR-0019 addendum).
 * `kid-hold-4` matches [KID_STUDIOS] by studio fold 2 key ([studioKey2], ADR-0026) instead of studio fold 1, so a kid
 * studio spelled with a space, blank or invisible look-alike still counts; the lists are unchanged.
 * `kid-hold-5` is `kid-hold-4` plus the rule that the rating of a known, rated series decides for its episodes
 * ([decisiveEpisodeRating]; ADR-0019 addendum 2026-11-13).
 */
object KidHold {
    /** The name of this version of the gate lists and the rating table. */
    const val VERSION_NAME = "kid-hold-5"

    /**
     * The genres that mean made for children, in their spellings. Family is not one of them. The starter catalog's
     * grown-up recipes exclude exactly these, so a change here also changes the catalog and needs a new
     * `StarterCatalog.VERSION` and frozen manifest.
     */
    val KIDS_GENRES: List<String> =
        listOf("Children", "Children's", "Children’s", "Childrens", "Kids", "Kid", "Preschool", "Pre-school")

    /**
     * The studios that mean made for children. The written rule: a children's network, channel block or label whose
     * published output is made for children; a studio with grown-up or general-family output (a big film studio's main
     * label, for example) is left off. Animation studios whose features are made for children and families also count
     * (owner decision 2026-10-28), except Studio Ghibli, which is left off on purpose. Also left off on purpose:
     * Nelvana, which also co-produces general-audience work, and a big film studio's live-action label (a DreamWorks
     * Pictures label, say).
     *
     * Matched by studio fold 2 key ([studioKey2]) against [RuleItem.foldedStudios2] and [RuleSeries.foldedStudios2],
     * so two entries that differ only by a space ("BabyTV", "Baby TV") share one key, which is harmless.
     */
    val KID_STUDIOS: List<String> =
        listOf(
            "Nickelodeon",
            "Nickelodeon Animation Studio",
            "Nickelodeon Productions",
            "Nickelodeon Movies",
            "Nick Jr.",
            "Nick Jr",
            "Nick Jr. Productions",
            "Nicktoons",
            "Noggin",
            "Disney Junior",
            "Disney Jr.",
            "Playhouse Disney",
            "Disney Channel",
            "Disney XD",
            "Toon Disney",
            "Cartoon Network",
            "Cartoon Network Studios",
            "Cartoonito",
            "Boomerang",
            "PBS Kids",
            "PBS KIDS",
            "Sesame Workshop",
            "Children's Television Workshop",
            "CBeebies",
            "CBBC",
            "BBC Children's",
            "Treehouse TV",
            "TVOKids",
            "ABC Kids",
            "Universal Kids",
            "Sprout",
            "BabyTV",
            "Baby TV",
            "Discovery Kids",
            "Qubo",
            "Kids' WB",
            "4Kids Entertainment",
            "HiT Entertainment",
            "Walt Disney Animation Studios",
            "Walt Disney Feature Animation",
            "Disney Television Animation",
            "Pixar",
            "Pixar Animation Studios",
            "DreamWorks Animation",
            "Illumination",
            "Illumination Entertainment",
            "Blue Sky Studios",
            "Sony Pictures Animation",
            "Aardman",
            "Aardman Animations",
            "Laika",
            "Hanna-Barbera",
            "Hanna-Barbera Productions",
            "Jim Henson Company",
            "The Jim Henson Company",
            "Rankin/Bass",
            "Rankin/Bass Productions",
            "WildBrain",
            "DHX Media",
        )

    /**
     * The ratings that mean made for children, after `RatingLadder.normalise` (TV Parental Guidelines). Frozen under
     * both [VERSION_NAME] and `GrownUpHold.VERSION_NAME`: a change to it is a new version name of each. The grown-up
     * gate (`GrownUpHold.dropsFromGrownUp`) is built from [isMadeForKids] and [passesKidCeiling], so a change to
     * either of those is a new version name of both holds too.
     */
    val CHILDRENS_RATINGS: Set<String> = setOf("TV-Y", "TV-Y7", "TV-Y7-FV")

    /**
     * The kids choice that holds a channel to what [rating] held it to, never looser: the highest of
     * [CEILING_CHOICES] whose kid tier is no higher than [rating]'s. No ceiling (null) is PG, as [passesKidCeiling]
     * reads it, and a rating the hold does not recognise has no kid tier, which that function reads as the lowest.
     */
    fun kidsChoiceFor(rating: String?): String {
        val tier = if (rating == null) TIER_PG else kidRank(rating) ?: 0
        return CEILING_CHOICES[tier.coerceAtMost(CEILING_CHOICES.lastIndex)]
    }

    /**
     * The ceilings a kids channel can be given, lowest first: one name per kid tier up to PG, the most a kids channel
     * airs (a higher ceiling is held at PG). Each name is on the tier [kidRank] gives it, so a choice means what the
     * hold does with it.
     */
    val CEILING_CHOICES: List<String> = listOf("G", "TV-Y7", "PG")

    private const val TIER_PG = 2
    private const val TIER_TV_PG = 3
    private const val TIER_ABOVE = 4

    private val genreSet = NameSet(KIDS_GENRES, IntArray(KIDS_GENRES.size))
    private val studioSet = NameSet(KID_STUDIOS.map(::studioKey2), IntArray(KID_STUDIOS.size))

    /**
     * Whether [entry] is made for children: one of its genres is in [KIDS_GENRES], one of its studios is in
     * [KID_STUDIOS], or its rating is a children's one (`TV-Y`, `TV-Y7`, `TV-Y7-FV`, per the TV Parental Guidelines),
     * and so, when [series] is not null, is one of the series'; or it is an animated family title
     * ([isAnimatedFamily]). A null [series] (a movie, an episode with no series, or one whose series the snapshot
     * lacks) contributes nothing, so such an episode matches on its own names and rating only.
     */
    fun isMadeForKids(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean =
        anyMatch(genreSet, entry.foldedGenres) ||
            anyMatch(studioSet, entry.foldedStudios2) ||
            hasChildrensRating(entry, series) ||
            (
                series != null &&
                    (
                        anyMatch(genreSet, series.foldedGenres) ||
                            anyMatch(studioSet, series.foldedStudios2)
                    )
            ) ||
            isAnimatedFamily(entry, series)

    /**
     * Whether [series] is made for children on its own names and rating, by the same lists as [isMadeForKids]: a kids
     * genre, a kid studio, a children's rating, or the animated-family signal on the series alone. It is not a hold of
     * its own; the curation reads it for the tone of a series (spec 23).
     */
    fun isSeriesMadeForKids(series: RuleSeries): Boolean =
        anyMatch(genreSet, series.foldedGenres) ||
            anyMatch(studioSet, series.foldedStudios2) ||
            isChildrensRating(series.officialRating) ||
            AnimatedFamilySignal.holdsForSeries(series)

    /**
     * The animated-family signal: the genres of [entry], together with those of its known [series], include both
     * Animation and Family, and the decisive rating ([decisiveEpisodeRating]) passes. When [series] is known and has a
     * rating (see [isAbsentRating]), that rating decides and the entry's own is ignored; a known series with no rating
     * leaves the entry's own to decide (the Farscape guard). A rating passes when absent; or after `RatingLadder.normalise`
     * it is `G`, `TV-G`, `TV-Y`, `TV-Y7`, `TV-Y7-FV` or `Approved`; or, ASCII upper-cased and trimmed, it is `NR`,
     * `NOT RATED` or `UNRATED`. Anything else, a PG or above, a name off the ladder, fails. Tags never take part. A
     * null [series] contributes nothing.
     */
    internal fun isAnimatedFamily(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = AnimatedFamilySignal.holds(entry, series)

    /**
     * Whether [entry]'s rating, or the known [series]' rating, is a children's one ([CHILDRENS_RATINGS]). This is the
     * predicate behind [isMadeForKids]'s ratings rule, so the rule and this one cannot drift. A null [series]
     * contributes nothing.
     */
    private fun hasChildrensRating(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = isChildrensRating(entry.officialRating) || (series != null && isChildrensRating(series.officialRating))

    private fun isChildrensRating(rating: String?): Boolean {
        if (rating == null) return false
        return RatingLadder.normalise(rating) in CHILDRENS_RATINGS
    }

    /**
     * The kid tier of the rating [rating] after `RatingLadder.normalise`: 0 for `TV-Y`, `TV-G`, `G`; 1 for `TV-Y7`,
     * `TV-Y7-FV`; 2 for `PG`; 3 for `TV-PG`; 4 for any other ladder name except `Approved`. Null is unrated: no rating,
     * a blank one, `Approved`, `NR`, or a name not on the ladder.
     */
    fun kidRank(rating: String?): Int? {
        val name = if (rating == null) null else RatingLadder.normalise(rating)
        return when (name) {
            null, "Approved" -> null
            "TV-Y", "TV-G", "G" -> 0
            "TV-Y7", "TV-Y7-FV" -> 1
            "PG" -> TIER_PG
            "TV-PG" -> TIER_TV_PG
            else -> TIER_ABOVE
        }
    }

    /**
     * Whether [entry] passes the kid rating table under [maxRating], with [series] as in [isMadeForKids]. The ceiling
     * is the kid tier of [maxRating] capped at PG (a null ceiling is PG too, and one with no kid tier is the lowest
     * tier). A movie passes when its own tier exists and is within it. An episode of a known series is judged on the
     * series' rating when it has one (not absent, see [isAbsentRating]), and the episode's own rating is ignored; when
     * the series has no rating, the episode is judged on its own (the Farscape guard). This deliberately departs from
     * `RatingEligibility` (rules-format rule 4) for a known rated series; that rule keeps the per-episode check for
     * open and grown-up channels. A series ID the snapshot lacks fails; an episode with no series ID is checked on its
     * own rating.
     */
    fun passesKidCeiling(
        entry: RuleItem,
        series: RuleSeries?,
        maxRating: String?,
    ): Boolean {
        val ceiling = if (maxRating == null) TIER_PG else minOf(kidRank(maxRating) ?: 0, TIER_PG)
        return when {
            entry.item.kind == ScheduleItemKind.Movie -> {
                within(entry.officialRating, ceiling)
            }

            entry.item.seriesId == null -> {
                within(entry.officialRating, ceiling)
            }

            series == null -> {
                false
            }

            else -> {
                within(decisiveEpisodeRating(entry, series), ceiling)
            }
        }
    }

    private fun within(
        rating: String?,
        ceiling: Int,
    ): Boolean {
        val rank = kidRank(rating)
        return rank != null && rank <= ceiling
    }

    private fun anyMatch(
        set: NameSet,
        folded: List<String>,
    ): Boolean {
        for (index in folded.indices) {
            if (set.indicesOfFolded(folded[index]) != null) return true
        }
        return false
    }

    /**
     * Whether a series rating counts as absent: it is null, or it is empty after trimming spaces (U+0020) from both
     * ends, the same trim as `RatingLadder.normalise`. Any other character, a tab, a no-break space or an ideographic
     * space included, makes the rating present (and, being off the ladder, a rating that fails the kid table). Written
     * without a library notion of whitespace so every port reads it the same way.
     */
    internal fun isAbsentRating(rating: String?): Boolean {
        if (rating == null) return true
        for (c in rating) if (c != ' ') return false
        return true
    }

    /**
     * The rating that counts for an episode of a known [series]: the series' when it is present (not absent, see
     * [isAbsentRating]), otherwise the [entry]'s own. Shared by [passesKidCeiling] and the animated-family signal, so
     * a series rating of spaces counts as absent in both.
     */
    internal fun decisiveEpisodeRating(
        entry: RuleItem,
        series: RuleSeries,
    ): String? = if (isAbsentRating(series.officialRating)) entry.officialRating else series.officialRating
}
