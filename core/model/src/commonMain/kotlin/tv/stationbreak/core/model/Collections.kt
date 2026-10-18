// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * A collection (a Jellyfin BoxSet) as the server describes it, for the library cache (spec 10, Unit 2). A collection
 * belongs to no library. [name] is text a person typed: it is stored, and never logged at info level or above.
 */
data class CollectionInfo(
    val id: ItemId,
    val name: String,
)

/** One page of the BoxSets the account can see. [totalCount] works as in [LibraryItemsPage]: page one only. */
data class CollectionPage(
    val startIndex: Int,
    val totalCount: Int?,
    val collections: List<CollectionInfo>,
)

/**
 * What a collection's member is, coarsely. The adapter only maps the server's type to a kind; the sync decides what to
 * store and what to skip. A BoxSet inside a BoxSet is [Collection] and is never followed. Anything else the server
 * holds in a collection (music, photos, a type added later) reads as [Other], never an error.
 */
enum class CollectionMemberKind {
    Movie,
    Episode,
    Series,
    Season,
    Collection,
    Other,
}

/** One direct member of a collection: its [id] and what it is. No `MediaItem`: a member is read for its ID and kind. */
data class CollectionMember(
    val id: ItemId,
    val kind: CollectionMemberKind,
)

/** One page of a collection's direct members. [totalCount] works as in [LibraryItemsPage]: page one only. */
data class CollectionMemberPage(
    val startIndex: Int,
    val totalCount: Int?,
    val members: List<CollectionMember>,
)
