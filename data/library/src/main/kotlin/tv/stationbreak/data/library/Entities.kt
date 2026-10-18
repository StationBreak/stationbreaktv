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
 * and the row is purged 30 days after it. The runtime is kept in whole milliseconds, exactly
 * floorDiv(RunTimeTicks, 10000) (rules format v1); it may be null, zero or negative, and then the item is stored
 * but never in a snapshot.
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
    indices =
        [
            Index(value = ["serverId", "userId", "libraryId"]),
            // The per-account tombstone purge (spec 09, Unit 3).
            Index(value = ["serverId", "userId", "deletedAtMs"]),
        ],
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
    val seasonId: String? = null,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val runtimeMs: Long?,
    val createdAtMs: Long?,
    val premiereDate: String?,
    val productionYear: Int?,
    val officialRating: String?,
    val deletedAtMs: Long?,
    val seenFromMs: Long,
    val firstSeenMs: Long,
)

/**
 * An earlier version of an item's schedule fields (spec 09, Unit 2): the item row as it stood until [supersededAtMs].
 * The item row is always the current version, and each item's versions form contiguous, strictly increasing intervals
 * `[seenFromMs, supersededAtMs)` that end where the next one, or the item row's own `seenFromMs`, begins (Vimes H1).
 * Only the fields the scheduler reads are kept (not the name), with [deletedAtMs] too, so a tombstone that was revived
 * stays a tombstone in the version that held it. [genres] and [tags] are one [VersionCodec] column each (Vimes M1).
 * The foreign key deletes the history with its item: sign-out, unticking a library and the purge of a tombstone (H5).
 */
@Entity(
    tableName = "item_version",
    primaryKeys = ["serverId", "userId", "itemId", "seenFromMs"],
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["serverId", "userId", "itemId"],
            childColumns = ["serverId", "userId", "itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // The purge of versions superseded long ago (spec 09, Unit 3).
    indices = [Index(value = ["serverId", "userId", "supersededAtMs"])],
)
internal data class ItemVersionEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val seenFromMs: Long,
    val supersededAtMs: Long,
    val libraryId: String,
    val kind: String,
    val seriesId: String?,
    val seasonId: String? = null,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val runtimeMs: Long?,
    val createdAtMs: Long?,
    val premiereDate: String?,
    val productionYear: Int?,
    val officialRating: String?,
    val deletedAtMs: Long?,
    val genres: String,
    val tags: String,
)

/**
 * A series of a selected library (spec 10, Unit 1): the fields a rule reads that its episodes do not carry. [genres]
 * and [tags] are one [VersionCodec] column each, because nothing queries them in SQL and the evaluator reads whole
 * series. [officialRating] is kept as the server sent it: null, `""` and `" "` are three values. The row is the current
 * version; [seenFromMs] and [firstSeenMs] work as on [ItemEntity]. Deleting a `library` row deletes its series.
 */
@Entity(
    tableName = "series",
    primaryKeys = ["serverId", "userId", "seriesId"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryEntity::class,
            parentColumns = ["serverId", "userId", "libraryId"],
            childColumns = ["serverId", "userId", "libraryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices =
        [
            // The cascade from library, and the unticking of one.
            Index(value = ["serverId", "userId", "libraryId"]),
            // The per-account tombstone purge.
            Index(value = ["serverId", "userId", "deletedAtMs"]),
        ],
)
internal data class SeriesEntity(
    val serverId: String,
    val userId: String,
    val seriesId: String,
    val libraryId: String,
    val name: String,
    val officialRating: String?,
    val genres: String,
    val tags: String,
    val deletedAtMs: Long?,
    val seenFromMs: Long,
    val firstSeenMs: Long,
)

/**
 * An earlier version of a series, with the same rules as [ItemVersionEntity]: the series row as it stood until
 * [supersededAtMs], in contiguous, strictly increasing intervals that end where the next one, or the series row's own
 * `seenFromMs`, begins. Only what a rule reads is kept, with [deletedAtMs]. The foreign key deletes the history with
 * its series.
 */
@Entity(
    tableName = "series_version",
    primaryKeys = ["serverId", "userId", "seriesId", "seenFromMs"],
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["serverId", "userId", "seriesId"],
            childColumns = ["serverId", "userId", "seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // The purge of versions superseded long ago.
    indices = [Index(value = ["serverId", "userId", "supersededAtMs"])],
)
internal data class SeriesVersionEntity(
    val serverId: String,
    val userId: String,
    val seriesId: String,
    val seenFromMs: Long,
    val supersededAtMs: Long,
    val libraryId: String,
    val officialRating: String?,
    val genres: String,
    val tags: String,
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

/**
 * The stamp of a new current row: now, but always after the stamp it replaces, so the intervals of an item's versions
 * increase strictly and never overlap, whatever the clock does (Vimes H1).
 */
internal fun nextStamp(
    old: ItemEntity,
    nowMs: Long,
): Long = nextStamp(old.seenFromMs, nowMs)

/** The same rule for any row with a stamp: [oldSeenFromMs] is the stamp of the row being replaced. */
internal fun nextStamp(
    oldSeenFromMs: Long,
    nowMs: Long,
): Long = maxOf(nowMs, oldSeenFromMs + 1)

/**
 * True when every field of a series that is versioned is equal: the rating, the ordered genres, the ordered tags and
 * the tombstone. The name and the library are not (Vimes L2).
 */
internal fun SeriesEntity.sameVersionedFields(other: SeriesEntity): Boolean =
    officialRating == other.officialRating &&
        genres == other.genres &&
        tags == other.tags &&
        deletedAtMs == other.deletedAtMs

/** This row as an earlier version, ending at [supersededAtMs]. */
internal fun SeriesEntity.toVersion(supersededAtMs: Long) =
    SeriesVersionEntity(
        serverId = serverId,
        userId = userId,
        seriesId = seriesId,
        seenFromMs = seenFromMs,
        supersededAtMs = supersededAtMs,
        libraryId = libraryId,
        officialRating = officialRating,
        genres = genres,
        tags = tags,
        deletedAtMs = deletedAtMs,
    )

/** True when every field the scheduler reads is equal. The name, the parent, the stamps and the tombstone are not. */
internal fun ItemEntity.sameScheduleFields(other: ItemEntity): Boolean =
    libraryId == other.libraryId &&
        kind == other.kind &&
        seriesId == other.seriesId &&
        seasonNumber == other.seasonNumber &&
        episodeNumber == other.episodeNumber &&
        runtimeMs == other.runtimeMs &&
        createdAtMs == other.createdAtMs &&
        premiereDate == other.premiereDate &&
        productionYear == other.productionYear &&
        officialRating == other.officialRating

/** This row as an earlier version, ending at [supersededAtMs], with the genres and tags it had. */
internal fun ItemEntity.toVersion(
    supersededAtMs: Long,
    genres: List<String>,
    tags: List<String>,
) = ItemVersionEntity(
    serverId = serverId,
    userId = userId,
    itemId = itemId,
    seenFromMs = seenFromMs,
    supersededAtMs = supersededAtMs,
    libraryId = libraryId,
    kind = kind,
    seriesId = seriesId,
    seasonId = seasonId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    runtimeMs = runtimeMs,
    createdAtMs = createdAtMs,
    premiereDate = premiereDate,
    productionYear = productionYear,
    officialRating = officialRating,
    deletedAtMs = deletedAtMs,
    genres = VersionCodec.encode(genres),
    tags = VersionCodec.encode(tags),
)
