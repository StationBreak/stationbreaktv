// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.RatingLadder

/**
 * The kid hold (spec 18, "The kid hold"): what a held channel will air. A held channel airs only what is made for
 * children ([isMadeForKids]) and that passes a kid rating table ([passesKidCeiling]). It is pure: it reads the entry
 * and its series and nothing else. Tags never take part. The lists below are frozen under [VERSION_NAME]; a change to
 * either is a new version name.
 */
object KidHold {
    /** The name of this version of the gate lists and the rating table. */
    const val VERSION_NAME = "kid-hold-1"

    /** The genres that mean made for children, in their spellings. Family is not one of them. */
    val KIDS_GENRES: List<String> =
        listOf("Children", "Children's", "Children’s", "Childrens", "Kids", "Kid", "Preschool", "Pre-school")

    /**
     * The studios that mean made for children. The written rule: a children's network, channel block or label whose
     * published output is made for children; a studio with grown-up or general-family output (a big film studio's main
     * label, for example) is left off.
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
        )

    private const val TIER_PG = 2
    private const val TIER_TV_PG = 3
    private const val TIER_ABOVE = 4

    private val genreSet = NameSet(KIDS_GENRES, IntArray(KIDS_GENRES.size))
    private val studioSet = NameSet(KID_STUDIOS, IntArray(KID_STUDIOS.size))

    /**
     * Whether [entry] is made for children: one of its genres is in [KIDS_GENRES] or one of its studios is in
     * [KID_STUDIOS], and so, when [series] is not null, is one of the series'. A null [series] (a movie, an episode with
     * no series, or one whose series the snapshot lacks) contributes nothing, so such an episode matches on its own
     * names only.
     */
    fun isMadeForKids(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean =
        anyMatch(genreSet, entry.foldedGenres) ||
            anyMatch(studioSet, entry.foldedStudios) ||
            (series != null && (anyMatch(genreSet, series.foldedGenres) || anyMatch(studioSet, series.foldedStudios)))

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
     * tier). A movie passes when its own tier exists and is within it. An episode follows `RatingEligibility`: its
     * effective rating (its own, or its series' when its own is null or empty) must pass; a series that is present with
     * a non-empty rating must pass too; a series ID the snapshot lacks fails; an episode with no series ID is checked
     * on its own rating.
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
                val own = entry.officialRating
                val effective = if (own.isNullOrEmpty()) series.officialRating else own
                val seriesRating = series.officialRating
                within(effective, ceiling) && (seriesRating.isNullOrEmpty() || within(seriesRating, ceiling))
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
}
