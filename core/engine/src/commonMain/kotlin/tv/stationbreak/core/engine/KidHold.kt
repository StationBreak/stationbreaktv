// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.studioKey2

/**
 * The kid hold (spec 18, "The kid hold"): what a held channel will air. A held channel airs only what is made for
 * children ([isMadeForKids]) and that passes a kid rating table ([passesKidCeiling]). It is pure: it reads the entry
 * and its series and nothing else. The lists below are frozen under [VERSION_NAME]; a change to
 * either is a new version name. `kid-hold-3` adds the animated-family signal ([isAnimatedFamily]; ADR-0019 addendum).
 * `kid-hold-4` matches [KID_STUDIOS] by studio fold 2 key ([studioKey2], ADR-0026) instead of studio fold 1, so a kid
 * studio spelled with a space, blank or invisible look-alike still counts; the lists are unchanged.
 * `kid-hold-5` is `kid-hold-4` plus the rule that the rating of a known, rated series decides for its episodes
 * ([EpisodeRating.decisive]; ADR-0019 addendum 2026-11-13).
 * `kid-hold-6` (ADR-0028, #576) puts every title in one of four tiers ([RatingTier]) and holds kid channels to the
 * kids tier ([admits]): a kid rating (level ALL or 7 on [RatingMap]) plus made-for-kids evidence, with no veto. A
 * children's rating is no longer evidence on its own; a kids tag is, next to a kid rating.
 * `kid-hold-7` (ADR-0029) moves titles only up: a family title with a violence-heavy tag or the Horror genre is teen
 * ([RatingTier.FAMILY_VETO_TAGS], [RatingTier.FAMILY_VETO_GENRES]), and a series on the hand-kept bump list
 * ([KidBumpList]) is family, not kids. The kid-studio list is unchanged (TV Tokyo was never on it).
 * `kid-hold-8` (#1078, ADR-0028 addendum 2026-11-22) adds the [RatingTier.Explicit] tier above adult (explicit ratings,
 * genres and tags, [RatingMap.isExplicit]), three adult tags, and X, AO and Adults Only at level 7. What a kid channel
 * admits is unchanged: the kids tier only.
 * `kid-hold-9` (#1088) tidies input noise before matching: a dash variant or a doubled space in a rating
 * ([RatingMap.levelOf]), a tab or no-break space in a rating read for [RatingMap.isExplicit] (fail up), and odd
 * spaces and dash variants in the genres and tags of the tier lists ([NoiseTidy]). Results move only up, except a
 * rating that was unknown for its noise and now has its level.
 * `kid-hold-10` (#987) maps the non-US ratings of the Android TV ratings XML ([RatingMap], [AndroidTvRatingSystems]):
 * a rating that was unknown (adult) can now be kids, family or teen, at or above its age hint (AU PG is 10). A bare
 * age is Family at least (#1006), so a bare `6` is no longer kids. Unknown and unrated stay adult.
 * `kid-hold-11` (#1124) reads AU `R`, SG `R21`, KR `19` and JP `19` and `20` as level 7, Explicit (adult-only labels):
 * no kid channel admitted them before either. Grown-up and custom ceilings read the mapped level (`grown-up-hold-11`).
 * `kid-hold-12` (#1128) reads TH `19` as level 7 too, and a country code followed by a space (`SG R21`, `AU R`) as
 * Explicit for the Explicit tier ([RatingMap.isExplicit]). Stricter only: nothing that was Explicit stops being so.
 */
object KidHold {
    /** The name of this version of the gate lists and the rating table. */
    const val VERSION_NAME = "kid-hold-12"

    /**
     * The genres that mean made for children, in their spellings. Family is not one of them. The starter catalog's
     * grown-up recipes exclude exactly these, so a change here also changes the catalog and needs a new
     * `StarterCatalog.VERSION` and frozen manifest. Since `kid-hold-6` the same spellings as tags are evidence too, but
     * only next to a kid rating ([RatingTier.isKidRated]): a TMDb "kids" keyword never counts alone.
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
     * The kids choice that holds a channel to what [rating] held it to, never looser: the highest of
     * [CEILING_CHOICES] whose level is no higher than [rating]'s. No ceiling (null) is the top choice, and a rating
     * the map does not know has no level, which [passesKidCeiling] reads as the lowest.
     */
    fun kidsChoiceFor(rating: String?): String {
        val tier = if (rating == null) CEILING_CHOICES.lastIndex else kidRank(rating) ?: 0
        return CEILING_CHOICES[tier.coerceAtMost(CEILING_CHOICES.lastIndex)]
    }

    /**
     * The ceilings a kids channel can be given, lowest first, each on the level [kidRank] gives it. Under `kid-hold-6`
     * a kids channel airs at most level 7, so PG airs what TV-Y7 airs; it stays for stored channels and the builder
     * (which choices to offer is a UX call, ADR-0028).
     */
    val CEILING_CHOICES: List<String> = listOf("G", "TV-Y7", "PG")

    private const val KID_LEVEL = 1

    private val genreSet = NameSet(KIDS_GENRES, IntArray(KIDS_GENRES.size))
    private val studioSet = NameSet(KID_STUDIOS.map(::studioKey2), IntArray(KID_STUDIOS.size))

    /** Whether a kid channel may take [entry]: it is in the kids tier ([RatingTier.of]). */
    fun admits(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = RatingTier.of(entry, series) == RatingTier.Kids

    /**
     * Whether [entry] is made for children (the evidence of the kids tier): one of its genres is in [KIDS_GENRES], or
     * one of its studios is in [KID_STUDIOS], and so, when [series] is not null, is one of the series'; or it is an
     * animated family title ([isAnimatedFamily]); or one of its or the series' tags is a [KIDS_GENRES] spelling and its
     * decisive rating is a kid one. A null [series] (a movie, an episode with no series, or one whose series the
     * snapshot lacks) contributes nothing, so such an episode matches on its own names only.
     */
    fun isMadeForKids(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean =
        anyMatch(genreSet, entry.foldedGenres) ||
            anyMatch(studioSet, entry.foldedStudios2) ||
            (
                series != null &&
                    (
                        anyMatch(genreSet, series.foldedGenres) ||
                            anyMatch(studioSet, series.foldedStudios2)
                    )
            ) ||
            isAnimatedFamily(entry, series) ||
            (
                (anyMatch(genreSet, entry.foldedTags) || (series != null && anyMatch(genreSet, series.foldedTags))) &&
                    RatingTier.isKidRated(entry, series)
            )

    /**
     * Whether [series] is made for children on its own names and rating, by the same lists as [isMadeForKids]: a kids
     * genre, a kid studio, the animated-family signal on the series alone, or a kids tag next to a kid series rating.
     * It is not a hold of its own; the curation reads it for the tone of a series (spec 23).
     */
    fun isSeriesMadeForKids(series: RuleSeries): Boolean =
        anyMatch(genreSet, series.foldedGenres) ||
            anyMatch(studioSet, series.foldedStudios2) ||
            AnimatedFamilySignal.holdsForSeries(series) ||
            (anyMatch(genreSet, series.foldedTags) && (kidRank(series.officialRating) ?: Int.MAX_VALUE) <= KID_LEVEL)

    /**
     * The animated-family signal: the genres of [entry], together with those of its known [series], include both
     * Animation and Family, and the decisive rating ([EpisodeRating.decisive]) passes. When [series] is known and has a
     * rating (see [EpisodeRating.isAbsent]), that rating decides and the entry's own is ignored; a known series with no
     * rating leaves the entry's own to decide (the Farscape guard). A rating passes when absent; or after
     * `RatingLadder.normalise` it is `G`, `TV-G`, `TV-Y`, `TV-Y7`, `TV-Y7-FV` or `Approved`; or, ASCII upper-cased and
     * trimmed, it is `NR`, `NOT RATED` or `UNRATED`. Anything else, a PG or above, a name off the ladder, fails.
     * Tags never take part. A null [series] contributes nothing.
     */
    internal fun isAnimatedFamily(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = AnimatedFamilySignal.holds(entry, series)

    /**
     * The level of [rating] on the shared ladder ([RatingMap.levelOf]): 0 for `G`, `TV-G`, `TV-Y`; 1 for `TV-Y7`,
     * `TV-Y7-FV` or a bare age of 6 or 7; 2 for `PG`, `TV-PG`; and so on. Null is unrated or unknown: no rating, a
     * blank one, `Approved`, `NR`, or a name the map does not know.
     */
    fun kidRank(rating: String?): Int? = RatingMap.levelOf(rating)

    /**
     * Whether [entry]'s decisive rating ([RatingTier.decisiveLevel]) is within the kid ceiling [maxRating]: its level
     * capped at 7 (null is 7 too, and a ceiling with no level is the lowest, ALL). An episode of a known series is
     * judged on the series' rating when it has one (not absent, see [EpisodeRating.isAbsent]), and the episode's own
     * rating is ignored; when the series has no rating, the episode is judged on its own (the Farscape guard). This
     * deliberately departs from `RatingEligibility` (rules-format rule 4) for a known rated series; that rule keeps the
     * per-episode check for open and grown-up channels. A series ID the snapshot lacks fails; an episode with no series
     * ID is checked on its own rating. A kid channel also needs [admits]; this narrows it to the channel's ceiling.
     */
    fun passesKidCeiling(
        entry: RuleItem,
        series: RuleSeries?,
        maxRating: String?,
    ): Boolean {
        val ceiling = if (maxRating == null) KID_LEVEL else minOf(kidRank(maxRating) ?: 0, KID_LEVEL)
        val level = RatingTier.decisiveLevel(entry, series)
        return level != null && level <= ceiling
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
}
