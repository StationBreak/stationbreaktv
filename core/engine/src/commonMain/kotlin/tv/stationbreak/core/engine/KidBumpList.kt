// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleSeries

/**
 * The hand-kept bump-to-family list (#990, ADR-0028 addendum): kid-rated series that must not air on kids channels
 * although nothing else vetoes them. A listed series is [RatingTier.Family] instead of [RatingTier.Kids]; the list
 * never moves a title down, so a listed id on a series whose rating is TV-14 or above leaves it where its rating put
 * it. Frozen under `KidHold.VERSION_NAME`. Not a general exclusions library: an entry needs a reason, a provider id and
 * a test.
 *
 * A series matches when one of its provider ids ([RuleSeries.providerIds]; keys `imdb`, `tmdb`, `tvdb`, as the cache
 * keeps them) equals one of the entry's. The title is never the match, so a mis-scraped or renamed title cannot hit.
 * Episodes follow their known series; a movie or an episode whose series is unknown is never listed.
 */
internal object KidBumpList {
    /** One listed series: [reason] in a line, and at least one id in [ids] (key to value). */
    class Entry(
        val reason: String,
        val ids: Map<String, String>,
    )

    /** The entries. Each is commented with its title and year, which are for people only. */
    val ENTRIES: List<Entry> =
        listOf(
            // The Ren & Stimpy Show (1991, Nickelodeon). Ids from thetvdb.com, themoviedb.org and imdb.com.
            Entry(
                reason = "Gross-out humor and innuendo under a TV-Y7; not a kids-band promise.",
                ids = mapOf("tvdb" to "76088", "tmdb" to "504", "imdb" to "tt0101178"),
            ),
        )

    /** Whether [series] is on the list; null (no known series) never is. */
    fun lists(series: RuleSeries?): Boolean {
        if (series == null || series.providerIds.isEmpty()) return false
        return ENTRIES.any { entry -> entry.ids.any { (key, value) -> series.providerIds[key] == value } }
    }
}
