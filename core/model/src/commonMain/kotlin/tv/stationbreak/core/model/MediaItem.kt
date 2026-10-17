// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.time.Duration
import kotlin.time.Instant

/** What an item is, coarsely. Anything the server adds later reads as [Other], never an error. */
enum class ItemKind {
    Movie,
    Series,
    Season,
    Episode,
    Video,
    MusicVideo,
    Folder,
    Other,
}

/**
 * An item as the server describes it. [runtime] is null when the server doesn't say; [parentId] is the folder or
 * library the item is in. F0.6 adds what the library cache and the scheduler need (S0.6.3). Every one of those fields
 * is optional, because an incomplete server answer must never fail a page: [dateCreated] is the server's
 * own UTC moment, [genres] and [tags] are empty when the server sends none, and [seriesId], [seasonNumber]
 * and [episodeNumber] only mean something for an episode. [runtime] is in whole milliseconds and [dateCreated] at
 * millisecond precision, exactly as the rules format maps them (spec 09); [premiereDate] is the UTC calendar date
 * of the premiere as `YYYY-MM-DD`.
 */
data class MediaItem(
    val id: ItemId,
    val name: String,
    val kind: ItemKind,
    val runtime: Duration?,
    val parentId: ItemId?,
    val dateCreated: Instant? = null,
    val premiereDate: String? = null,
    val productionYear: Int? = null,
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val seriesId: ItemId? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

/** One page of a library: [totalCount] counts every item, [items] the ones from [startIndex] on. */
data class ItemPage(
    val totalCount: Int,
    val startIndex: Int,
    val items: List<MediaItem>,
)

/**
 * One page of a library's video items for the cache (F0.6). [totalCount] is the whole library's count and is present
 * only on the first page ([startIndex] 0), because the server is asked for it once. [items] are in a stable order:
 * for a library that doesn't change, a page boundary never skips or repeats an item. A library that changes during a
 * walk can shift offsets, which the sync covers (see `MediaServer.libraryItems`).
 */
data class LibraryItemsPage(
    val startIndex: Int,
    val totalCount: Int?,
    val items: List<MediaItem>,
)

/** One page of item IDs, to find what the server no longer has (F0.6). [totalCount] works as in [LibraryItemsPage]. */
data class ItemIdPage(
    val startIndex: Int,
    val totalCount: Int?,
    val ids: List<ItemId>,
)
