// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// The saved library's tables (spec 06, Unit 2). Every row is keyed by server and user (ADR-0002). All times are UTC
// milliseconds. Nothing here can hold a token or a password: the columns are IDs, names, numbers and dates.
//
// Deleting a `library` row deletes everything under it (items, their genres and tags, the checkpoint) through the
// foreign keys, so unticking a library, signing out and a change of account are each one DELETE. SQLite on API 25 is
// 3.9: no UPSERT, no window functions, no row values, at most 999 variables in a statement. The DAO sticks to that.

/** One chosen library of one account. [fetched] is false from the tick until the sync has read all of it. */
@Entity(tableName = "library", primaryKeys = ["serverId", "userId", "libraryId"])
internal data class LibraryEntity(
    val serverId: String,
    val userId: String,
    val libraryId: String,
    val fetched: Boolean,
)

/**
 * One video item. [deletedAtMs] is the tombstone: set when the server no longer has the item, cleared if it returns,
 * and the row is purged 30 days after it. The runtime is kept in whole milliseconds, exactly floorDiv(RunTimeTicks, 10000) (rules format v1); it
 * may be null, zero or negative, and then the item is stored but never in a snapshot.
 */
@Entity(
    tableName = "item",
    primaryKeys = ["serverId", "userId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryEntity::class,
            parentColumns = ["serverId", "userId", "libraryId"],
            childColumns = ["serverId", "userId", "libraryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["serverId", "userId", "libraryId"]), Index(value = ["deletedAtMs"])],
)
internal data class ItemEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val libraryId: String,
    val kind: String,
    val name: String,
    val parentId: String?,
    val seriesId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val runtimeMs: Long?,
    val createdAtMs: Long?,
    val premiereDate: String?,
    val productionYear: Int?,
    val officialRating: String?,
    val deletedAtMs: Long?,
)

/** One genre of an item, at [position] so the server's order is kept. */
@Entity(
    tableName = "item_genre",
    primaryKeys = ["serverId", "userId", "itemId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["serverId", "userId", "itemId"],
            childColumns = ["serverId", "userId", "itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class GenreEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val position: Int,
    val name: String,
)

/** One tag of an item, at [position] so the server's order is kept. */
@Entity(
    tableName = "item_tag",
    primaryKeys = ["serverId", "userId", "itemId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["serverId", "userId", "itemId"],
            childColumns = ["serverId", "userId", "itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class TagEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val position: Int,
    val name: String,
)

/**
 * Where the sync of one library stands: the next position to ask for, the whole library's count as page one said it,
 * and the run it belongs to. It is written in the same transaction as the page that moved it, so after a crash the
 * items and the checkpoint always agree.
 */
@Entity(
    tableName = "checkpoint",
    primaryKeys = ["serverId", "userId", "libraryId"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryEntity::class,
            parentColumns = ["serverId", "userId", "libraryId"],
            childColumns = ["serverId", "userId", "libraryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class CheckpointEntity(
    val serverId: String,
    val userId: String,
    val libraryId: String,
    val nextStartIndex: Int,
    val totalCount: Int?,
    val syncRun: Long,
)

/** The state of one account's sync, as [STATE_FIRST_RUNNING], [STATE_IDLE] or [STATE_FAILED]. No row: never synced. */
@Entity(tableName = "account_state", primaryKeys = ["serverId", "userId"])
internal data class AccountStateEntity(
    val serverId: String,
    val userId: String,
    val state: Int,
    val total: Int?,
    val failure: Int?,
    val lastSuccessMs: Long?,
) {
    companion object {
        const val STATE_FIRST_RUNNING = 0
        const val STATE_IDLE = 1
        const val STATE_FAILED = 2
    }
}

/**
 * The account signed in now. `useAccount` writes it (and deletes every other account's rows and marker) and `forget`
 * deletes it. Every transaction the sync runs checks it first, so a sync still running after its account signed out, or
 * after another account signed in, can write nothing back (spec 06 task 3.4, Angua M1). Normally one row.
 */
@Entity(tableName = "active_account", primaryKeys = ["serverId", "userId"])
internal data class ActiveAccountEntity(
    val serverId: String,
    val userId: String,
)
