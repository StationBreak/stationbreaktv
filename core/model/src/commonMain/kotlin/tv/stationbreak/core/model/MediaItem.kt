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
 * is optional, because an incomplete server answer must never fail a page: [dateCreated] and [dateLastSaved] are the
 * server's own UTC moments, [genres] and [tags] are empty when the server sends none, and [seriesId], [seasonNumber]
 * and [episodeNumber] only mean something for an episode.
 */
data class MediaItem(
    val id: ItemId,
    val name: String,
    val kind: ItemKind,
    val runtime: Duration?,
    val parentId: ItemId?,
    val dateCreated: Instant? = null,
    val dateLastSaved: Instant? = null,
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
