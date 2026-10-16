// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** An item row with its genre and tag rows. */
internal class ItemRows(
    val item: ItemEntity,
    val genres: List<GenreEntity>,
    val tags: List<TagEntity>,
)

/** What [LibraryDao.writePage] did with a page's items. A replayed page is all [unchanged]. */
internal data class PageWrite(
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
)

/** What the status flow reads in one query: the account's state row and the live item count under it. */
internal data class StatusRow(
    val state: Int,
    val total: Int?,
    val failure: Int?,
    val lastSuccessMs: Long?,
    val copied: Int,
)

/**
 * The SQL of the saved library. A lookup by a list of IDs must be given at most [MAX_VARIABLES] values: the platform
 * SQLite on API 25 allows 999 variables in a statement, and the repository chunks.
 */
@Dao
@Suppress("TooManyFunctions") // The SQL surface of the cache: one method per statement or transaction.
internal abstract class LibraryDao {
    // Libraries.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertLibraries(rows: List<LibraryEntity>)

    @Query("DELETE FROM library WHERE serverId = :serverId AND userId = :userId AND libraryId NOT IN (:keep)")
    abstract suspend fun deleteLibrariesNotIn(
        serverId: String,
        userId: String,
        keep: List<String>,
    )

    @Query("DELETE FROM library WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun deleteLibraries(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM library WHERE NOT (serverId = :serverId AND userId = :userId)")
    abstract suspend fun deleteOtherLibraries(
        serverId: String,
        userId: String,
    )

    @Query("UPDATE library SET fetched = 1 WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId")
    abstract suspend fun markFetched(
        serverId: String,
        userId: String,
        libraryId: String,
    )

    @Query("SELECT libraryId FROM library WHERE serverId = :serverId AND userId = :userId AND fetched = 0")
    abstract suspend fun pendingLibraries(
        serverId: String,
        userId: String,
    ): List<String>

    // Items.

    @Query("SELECT * FROM item WHERE serverId = :serverId AND userId = :userId AND itemId IN (:ids)")
    abstract suspend fun itemsByIds(
        serverId: String,
        userId: String,
        ids: List<String>,
    ): List<ItemEntity>

    @Insert
    abstract suspend fun insertItems(rows: List<ItemEntity>)

    @Update
    abstract suspend fun updateItems(rows: List<ItemEntity>)

    @Query(
        "SELECT * FROM item WHERE serverId = :serverId AND userId = :userId AND libraryId IN (:libraries) " +
            "AND deletedAtMs IS NULL AND createdAtMs IS NOT NULL AND createdAtMs < :beforeMs " +
            "AND runtimeNanos IS NOT NULL AND runtimeNanos > 0 ORDER BY itemId",
    )
    abstract suspend fun snapshot(
        serverId: String,
        userId: String,
        libraries: List<String>,
        beforeMs: Long,
    ): List<ItemEntity>

    @Query(
        "SELECT COUNT(*) FROM item WHERE serverId = :serverId AND userId = :userId AND libraryId IN (:libraries) " +
            "AND deletedAtMs IS NULL AND (runtimeNanos IS NULL OR runtimeNanos <= 0)",
    )
    abstract suspend fun excludedCount(
        serverId: String,
        userId: String,
        libraries: List<String>,
    ): Int

    @Query("SELECT * FROM item WHERE serverId = :serverId AND userId = :userId ORDER BY itemId")
    abstract suspend fun allItems(
        serverId: String,
        userId: String,
    ): List<ItemEntity>

    @Query(
        "SELECT itemId FROM item WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId " +
            "AND deletedAtMs IS NULL ORDER BY itemId",
    )
    abstract suspend fun liveItemIds(
        serverId: String,
        userId: String,
        libraryId: String,
    ): List<String>

    @Query(
        "UPDATE item SET deletedAtMs = :atMs WHERE serverId = :serverId AND userId = :userId " +
            "AND itemId IN (:ids) AND deletedAtMs IS NULL",
    )
    abstract suspend fun tombstone(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    )

    @Query("UPDATE item SET deletedAtMs = NULL WHERE serverId = :serverId AND userId = :userId AND itemId IN (:ids)")
    abstract suspend fun revive(
        serverId: String,
        userId: String,
        ids: List<String>,
    )

    @Query("DELETE FROM item WHERE deletedAtMs IS NOT NULL AND deletedAtMs < :cutoffMs")
    abstract suspend fun purgeTombstonesBefore(cutoffMs: Long): Int

    // Genres and tags.

    @Insert
    abstract suspend fun insertGenres(rows: List<GenreEntity>)

    @Insert
    abstract suspend fun insertTags(rows: List<TagEntity>)

    @Query("DELETE FROM item_genre WHERE serverId = :serverId AND userId = :userId AND itemId IN (:ids)")
    abstract suspend fun deleteGenres(
        serverId: String,
        userId: String,
        ids: List<String>,
    )

    @Query("DELETE FROM item_tag WHERE serverId = :serverId AND userId = :userId AND itemId IN (:ids)")
    abstract suspend fun deleteTags(
        serverId: String,
        userId: String,
        ids: List<String>,
    )

    @Query(
        "SELECT * FROM item_genre WHERE serverId = :serverId AND userId = :userId AND itemId IN (:ids) " +
            "ORDER BY itemId, position",
    )
    abstract suspend fun genresFor(
        serverId: String,
        userId: String,
        ids: List<String>,
    ): List<GenreEntity>

    @Query(
        "SELECT * FROM item_tag WHERE serverId = :serverId AND userId = :userId AND itemId IN (:ids) " +
            "ORDER BY itemId, position",
    )
    abstract suspend fun tagsFor(
        serverId: String,
        userId: String,
        ids: List<String>,
    ): List<TagEntity>

    // Checkpoints.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveCheckpoint(row: CheckpointEntity)

    @Query("SELECT * FROM checkpoint WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId")
    abstract suspend fun checkpoint(
        serverId: String,
        userId: String,
        libraryId: String,
    ): CheckpointEntity?

    // Sync state.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveState(row: AccountStateEntity)

    @Query("SELECT * FROM account_state WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun state(
        serverId: String,
        userId: String,
    ): AccountStateEntity?

    @Query("UPDATE account_state SET total = :total WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun setTotal(
        serverId: String,
        userId: String,
        total: Int,
    )

    @Query("DELETE FROM account_state WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun deleteState(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM account_state WHERE NOT (serverId = :serverId AND userId = :userId)")
    abstract suspend fun deleteOtherStates(
        serverId: String,
        userId: String,
    )

    // The count is of the live items under the account, so a replayed page is never counted twice and a restart
    // continues from what is really saved.
    @Query(
        "SELECT a.state AS state, a.total AS total, a.failure AS failure, a.lastSuccessMs AS lastSuccessMs, " +
            "(SELECT COUNT(*) FROM item WHERE item.serverId = a.serverId AND item.userId = a.userId " +
            "AND item.deletedAtMs IS NULL) AS copied " +
            "FROM account_state a WHERE a.serverId = :serverId AND a.userId = :userId",
    )
    abstract fun observeStatus(
        serverId: String,
        userId: String,
    ): Flow<StatusRow?>

    // Counts, for the tests and diagnostics.

    @Query("SELECT COUNT(*) FROM item")
    abstract suspend fun countItems(): Int

    @Query("SELECT COUNT(*) FROM item_genre")
    abstract suspend fun countGenres(): Int

    @Query("SELECT COUNT(*) FROM item_tag")
    abstract suspend fun countTags(): Int

    @Query("SELECT COUNT(*) FROM library")
    abstract suspend fun countLibraries(): Int

    @Query("SELECT COUNT(*) FROM checkpoint")
    abstract suspend fun countCheckpoints(): Int

    @Query("SELECT COUNT(*) FROM account_state")
    abstract suspend fun countStates(): Int

    // The transactions. They are DAO methods, not `withTransaction` blocks, so they work whether Room runs over the
    // platform's SQLiteOpenHelper (the app) or over a SQLiteDriver (the JVM tests). Each runs as one transaction: it
    // commits whole or rolls back whole.

    /** Makes [chosen] the account's libraries: the others go (with everything under them), new ones are pending. */
    @Transaction
    open suspend fun select(
        serverId: String,
        userId: String,
        chosen: List<String>,
    ) {
        deleteLibrariesNotIn(serverId, userId, chosen)
        insertLibraries(chosen.map { LibraryEntity(serverId, userId, it, fetched = false) })
    }

    /** [serverId] and [userId] are signed in: every other account's rows go. */
    @Transaction
    open suspend fun activate(
        serverId: String,
        userId: String,
    ) {
        deleteOtherLibraries(serverId, userId)
        deleteOtherStates(serverId, userId)
    }

    /** The account signed out: its rows go. */
    @Transaction
    open suspend fun forget(
        serverId: String,
        userId: String,
    ) {
        deleteLibraries(serverId, userId)
        deleteState(serverId, userId)
    }

    /**
     * Writes one page and its checkpoint together. An item the database already holds, equal, is left alone; one that
     * differs is updated and its genres and tags replaced; a new one is inserted. [betweenItemsAndCheckpoint] runs
     * after the items and before the checkpoint (a test seam; null in the app).
     */
    @Transaction
    open suspend fun writePage(
        checkpoint: CheckpointEntity,
        items: Map<String, ItemRows>,
        betweenItemsAndCheckpoint: (() -> Unit)?,
    ): PageWrite {
        val server = checkpoint.serverId
        val user = checkpoint.userId
        val existing =
            items.keys
                .chunked(MAX_VARIABLES)
                .flatMap { itemsByIds(server, user, it) }
                .associateBy { it.itemId }
        val toInsert = mutableListOf<ItemEntity>()
        val toUpdate = mutableListOf<ItemEntity>()
        var unchanged = 0
        for ((id, rows) in items) {
            val old = existing[id]
            when {
                old == null -> toInsert += rows.item
                old != rows.item -> toUpdate += rows.item
                else -> unchanged++
            }
        }
        insertItems(toInsert)
        updateItems(toUpdate)
        val changed = (toInsert + toUpdate).map { it.itemId }
        changed.chunked(MAX_VARIABLES).forEach {
            deleteGenres(server, user, it)
            deleteTags(server, user, it)
        }
        insertGenres(changed.flatMap { items.getValue(it).genres })
        insertTags(changed.flatMap { items.getValue(it).tags })
        betweenItemsAndCheckpoint?.invoke()
        saveCheckpoint(checkpoint)
        return PageWrite(toInsert.size, toUpdate.size, unchanged)
    }

    /** The first sync starts or starts again: running, no total yet, the last success (if any) kept. */
    @Transaction
    open suspend fun beginFirstSync(
        serverId: String,
        userId: String,
    ) {
        val last = state(serverId, userId)?.lastSuccessMs
        saveState(AccountStateEntity(serverId, userId, AccountStateEntity.STATE_FIRST_RUNNING, null, null, last))
    }

    /** The sync stopped for [failure]: the total and the last success are kept. */
    @Transaction
    open suspend fun failSync(
        serverId: String,
        userId: String,
        failure: Int,
    ) {
        val old = state(serverId, userId)
        val failed = AccountStateEntity.STATE_FAILED
        saveState(AccountStateEntity(serverId, userId, failed, old?.total, failure, old?.lastSuccessMs))
    }

    /** Marks [ids] deleted at [atMs] (an existing tombstone keeps its time). */
    @Transaction
    open suspend fun tombstoneAll(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    ) = ids.chunked(MAX_VARIABLES).forEach { tombstone(serverId, userId, it, atMs) }

    /** Clears the tombstone of [ids]. */
    @Transaction
    open suspend fun reviveAll(
        serverId: String,
        userId: String,
        ids: List<String>,
    ) = ids.chunked(MAX_VARIABLES).forEach { revive(serverId, userId, it) }

    /** The snapshot's items with their genres and tags, all from one moment. */
    @Transaction
    open suspend fun snapshotRows(
        serverId: String,
        userId: String,
        libraries: List<String>,
        beforeMs: Long,
    ): List<ItemRows> = withSideTables(serverId, userId, snapshot(serverId, userId, libraries, beforeMs))

    /** Every item of the account, with its genres and tags. */
    @Transaction
    open suspend fun allRows(
        serverId: String,
        userId: String,
    ): List<ItemRows> = withSideTables(serverId, userId, allItems(serverId, userId))

    private suspend fun withSideTables(
        serverId: String,
        userId: String,
        items: List<ItemEntity>,
    ): List<ItemRows> {
        val ids = items.map { it.itemId }
        val genres = ids.chunked(MAX_VARIABLES).flatMap { genresFor(serverId, userId, it) }.groupBy { it.itemId }
        val tags = ids.chunked(MAX_VARIABLES).flatMap { tagsFor(serverId, userId, it) }.groupBy { it.itemId }
        return items.map { ItemRows(it, genres[it.itemId].orEmpty(), tags[it.itemId].orEmpty()) }
    }

    companion object {
        /** SQLite on API 25 allows 999 variables in a statement; lookups by a list of IDs stay well under it. */
        const val MAX_VARIABLES = 400
    }
}
