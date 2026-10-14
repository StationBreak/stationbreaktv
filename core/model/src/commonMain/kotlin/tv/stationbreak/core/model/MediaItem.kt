// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import kotlin.time.Duration

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
 * The thin item the adapter's contract needs (F0.3): F0.6 and F1.5 add what their screens need. [runtime] is null when
 * the server doesn't say. [parentId] is the folder or library the item is in.
 */
data class MediaItem(
    val id: ItemId,
    val name: String,
    val kind: ItemKind,
    val runtime: Duration?,
    val parentId: ItemId?,
)

/** One page of a library: [totalCount] counts every item, [items] the ones from [startIndex] on. */
data class ItemPage(
    val totalCount: Int,
    val startIndex: Int,
    val items: List<MediaItem>,
)
