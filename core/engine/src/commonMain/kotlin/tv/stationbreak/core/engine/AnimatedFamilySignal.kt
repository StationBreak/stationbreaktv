// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.rules.RatingLadder

/**
 * The animated-family signal of `kid-hold-3` (ADR-0019 addendum), behind [KidHold.isAnimatedFamily]. Pure; it reads
 * the folded genres and the ratings and nothing else. Its lists are frozen under `KidHold.VERSION_NAME`.
 */
internal object AnimatedFamilySignal {
    private const val ASCII_CASE_OFFSET = 'a' - 'A'

    /** The two folded genre names the signal needs together. Pinned under both hold versions. */
    internal const val GENRE_ANIMATION = "animation"
    internal const val GENRE_FAMILY = "family"

    /** The ladder names the signal accepts, besides a null, blank or unrated spelling. Pinned under both versions. */
    internal val RATINGS: Set<String> = setOf("G", "TV-G", "TV-Y", "TV-Y7", "TV-Y7-FV", "Approved")

    /** The spellings of unrated, after ASCII upper-casing and trimming. Pinned under both hold versions. */
    internal val UNRATED_SPELLINGS: Set<String> = setOf("NR", "NOT RATED", "UNRATED")

    /** The signal on [entry] and its known [series] (see [KidHold.isAnimatedFamily]). */
    fun holds(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean {
        if (!genresHold(entry.foldedGenres, series?.foldedGenres)) return false
        val own = entry.officialRating
        val seriesRating = series?.officialRating
        val effective = if (own.isNullOrBlank()) seriesRating else own
        return ratingPasses(effective) && ratingPasses(seriesRating)
    }

    /** The signal on [series] alone. */
    fun holdsForSeries(series: RuleSeries): Boolean {
        val rating = series.officialRating
        return genresHold(series.foldedGenres, null) && ratingPasses(rating)
    }

    private fun genresHold(
        genres: List<String>,
        more: List<String>?,
    ): Boolean {
        val animated = contains(genres, GENRE_ANIMATION) || (more != null && contains(more, GENRE_ANIMATION))
        return animated && (contains(genres, GENRE_FAMILY) || (more != null && contains(more, GENRE_FAMILY)))
    }

    private fun contains(
        folded: List<String>,
        name: String,
    ): Boolean {
        for (index in folded.indices) if (folded[index] == name) return true
        return false
    }

    private fun ratingPasses(rating: String?): Boolean {
        val name = if (rating == null || rating.isBlank()) null else RatingLadder.normalise(rating)
        return when {
            rating == null || rating.isBlank() -> true
            name != null -> name in RATINGS
            else -> upperAscii(rating.trim()) in UNRATED_SPELLINGS
        }
    }

    private fun upperAscii(text: String): String =
        buildString(text.length) { for (c in text) append(if (c in 'a'..'z') c - ASCII_CASE_OFFSET else c) }
}
