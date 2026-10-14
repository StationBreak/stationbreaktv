// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/** The kinds of library StationBreak can build channels from: the video-capable ones (S0.3.3). */
enum class LibraryKind {
    Movies,
    TvShows,

    /** Mixed content, which Jellyfin represents by leaving the collection type out. */
    Mixed,
    HomeVideos,
    MusicVideos,
}

/** A library on the server the user can pick content from. Music, playlists and boxsets are never one. */
data class Library(
    val id: ItemId,
    val name: String,
    val kind: LibraryKind,
)
