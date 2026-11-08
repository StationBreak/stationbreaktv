// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

// Two more invented libraries beside MOVIES and SHOWS, for the ownership tests (spec 26, schema 11).
internal val ADDS = ItemId(hexId(0xB003))
internal val MORE = ItemId(hexId(0xB004))

/** An episode with a canonical ID that names [seriesId] as given (any spelling). */
internal fun sepisode(
    n: Int,
    seriesId: String,
    runtime: kotlin.time.Duration? = 30.minutes,
) = MediaItem(
    id = ItemId(hexId(n)),
    name = "Episode $n",
    kind = ItemKind.Episode,
    runtime = runtime,
    parentId = null,
    dateCreated = EPOCH - 1.days,
    seriesId = ItemId(seriesId),
    seasonNumber = 1,
    episodeNumber = n,
)

/** The dashed, upper-case spelling of a canonical id: one more way a server may spell it. */
internal fun dashedUpper(canonical: String): String =
    listOf(
        canonical.substring(0, 8),
        canonical.substring(8, 12),
        canonical.substring(12, 16),
        canonical.substring(16, 20),
        canonical.substring(20),
    ).joinToString("-").uppercase()

/** Who owns the item [n] now (its `item.libraryId`), or null when there is no row. */
internal suspend fun RoomLibraryRepository.ownerOf(
    account: LibraryAccount,
    n: Int,
): ItemId? = currentRows(account).firstOrNull { it.itemId == hexId(n) }?.libraryId?.let(::ItemId)

/** The current row of the item [n]. */
internal suspend fun RoomLibraryRepository.rowOf(
    account: LibraryAccount,
    n: Int,
): ItemEntity? = currentRows(account).firstOrNull { it.itemId == hexId(n) }

/** The libraries that list the item [n] without owning it. */
internal suspend fun RoomLibraryRepository.alsoListersOf(
    account: LibraryAccount,
    n: Int,
): Set<ItemId> = alsoListed(account).filter { it.first.value == hexId(n) }.map { it.second }.toSet()

/**
 * True when the item [n] took a version, ending where its current row begins, that was owned by [from]: the owner
 * change was versioned (INV-4).
 */
internal suspend fun RoomLibraryRepository.movedVersioned(
    account: LibraryAccount,
    n: Int,
    from: ItemId,
): Boolean {
    val row = rowOf(account, n) ?: return false
    return versions(account).any {
        it.itemId == row.itemId && it.libraryId == from.value && it.supersededAtMs == row.seenFromMs
    }
}
