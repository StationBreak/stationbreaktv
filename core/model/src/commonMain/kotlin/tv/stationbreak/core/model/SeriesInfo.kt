// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * A series as the server describes it, for the library cache (spec 10, Unit 1): the fields a channel rule can match on
 * that an episode does not carry. [officialRating] is the server's text exactly as sent: null when it sends none, and
 * `""` and `" "` are kept as they are, never trimmed and never read as null. [genres] and [tags] are in the server's
 * order and empty when it sends none.
 */
data class SeriesInfo(
    val id: ItemId,
    val name: String,
    val officialRating: String?,
    val genres: List<String>,
    val tags: List<String>,
)

/** One page of a library's series for the cache. [totalCount] works as in [LibraryItemsPage]: page one only. */
data class SeriesPage(
    val startIndex: Int,
    val totalCount: Int?,
    val series: List<SeriesInfo>,
)
