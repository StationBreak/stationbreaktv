// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries

/**
 * The grown-up hold (ADR-0020): the name of the version of its gate, and the gate itself. A grown-up pool drops an item
 * a kid channel could take ([dropsFromGrownUp]), so nothing airs nowhere because of the holds. The gate
 * is built from [KidHold.isMadeForKids] and [KidHold.passesKidCeiling], so it changes whenever either of them does:
 * `grown-up-hold-3` moves with `kid-hold-4` (the kid studio list matched by studio fold 2, ADR-0026), and
 * `grown-up-hold-4` moves with `kid-hold-5` (an episode of a rated series follows the series rating; ADR-0019 addendum
 * 2026-11-13). `grown-up-hold-5` moves with `kid-hold-6` (ADR-0028): it drops exactly the kids tier ([RatingTier]).
 * `grown-up-hold-6` moves with `kid-hold-7` (ADR-0029): the same gate, on a kids tier that the bump list narrows.
 * `grown-up-hold-7` moves with `kid-hold-8` (#1078, ADR-0028 addendum 2026-11-22): it also drops the Explicit tier,
 * which airs on After Hours alone; under a young ceiling it admits only that band's tiers ([tierLimit]); and a
 * grown-up leftovers pool drops a title with no metadata ([hasNoMetadata]).
 * `grown-up-hold-8` moves with `kid-hold-9` (#1088): the tidy of [NoiseTidy], and a blank rating, genre or tag (empty
 * or only white space, a tab or no-break space included) is none in [hasNoMetadata].
 * `grown-up-hold-9` (#1108) moves alone: kid-hold-9 stays. A custom channel under a young ceiling follows the tier
 * limit of its band ([tierLimit]), as a built-in channel of that band does (ADR-0028 addendum 2026-11-23).
 */
object GrownUpHold {
    /** The name of this version of the grown-up gate. */
    const val VERSION_NAME = "grown-up-hold-9"

    /**
     * Whether a grown-up pool drops [entry]: it is in the kids tier ([KidHold.admits]), so some kid channel can take
     * it, or in the Explicit tier, which only After Hours airs. Everything else (family, teen and adult titles, made
     * for kids or not) is kept, and the rating ceiling of the grown-up channel applies to it. Pure: it reads the entry
     * and its [series] only.
     */
    fun dropsFromGrownUp(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = drops(RatingTier.of(entry, series))

    /** [dropsFromGrownUp] for a title whose tier is [tier]. */
    fun drops(tier: RatingTier): Boolean = tier == RatingTier.Kids || tier == RatingTier.Explicit

    /**
     * The highest tier a grown-up pool under the ceiling [maxRating] admits (#1078, as #576 specified for the young
     * bands): [RatingTier.Family] under a ceiling up to PG (the Family band and its Odds & Ends), [RatingTier.Teen]
     * up to TV-14 (the Teen band and its Odds & Ends), and [RatingTier.Adult] above that or with no ceiling.
     */
    fun tierLimit(maxRating: String?): RatingTier {
        val level = maxRating?.let(RatingMap::levelOf) ?: return RatingTier.Adult
        return when {
            level <= RatingTier.FAMILY_LEVEL -> RatingTier.Family
            level <= RatingTier.TEEN_LEVEL -> RatingTier.Teen
            else -> RatingTier.Adult
        }
    }

    /**
     * Whether [entry] is an episode whose series ID is set but whose series the snapshot lacks (#1078, fail up): the
     * series' explicit signals can't be read, so a grown-up pool with no ceiling and every grown-up leftovers pool drop
     * it. (Under a ceiling rule 4 already blocks it.)
     */
    fun seriesMissing(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = entry.item.seriesId != null && series == null

    /**
     * Whether [entry] has no metadata at all (#1078): no rating, no genre and no tag, on itself or its known [series].
     * A value that is null, empty or only white space (a tab, a no-break space...) counts as none (#1088). A grown-up
     * leftovers pool drops it; it can still air where a source matches it.
     */
    fun hasNoMetadata(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean =
        bare(entry.officialRating, entry.genres, entry.tags) &&
            (series == null || bare(series.officialRating, series.genres, series.tags))

    private fun bare(
        rating: String?,
        genres: List<String>,
        tags: List<String>,
    ): Boolean = blank(rating) && genres.all(::blank) && tags.all(::blank)

    // Unlike `EpisodeRating.isAbsent` (U+0020 only) this reads a tab or no-break space as blank: the safe direction,
    // since a leftovers pool then only drops more. Do not make the two one function (ADR-0028 addendum 2).
    private fun blank(text: String?): Boolean = text == null || NoiseTidy.tidy(text).isEmpty()
}
