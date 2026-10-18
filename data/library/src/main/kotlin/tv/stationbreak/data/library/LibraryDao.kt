// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** How a purge ended (spec 09, Unit 3). */
internal sealed interface PurgeOutcome {
    /**
     * The purge ran: [tombstones] tombstoned items and [versions] superseded item versions were deleted, and
     * [seriesTombstones] and [seriesVersions] the same for series, and [collectionTombstones], [collectionVersions]
     * and [collectionMembers] (member rows superseded long ago) for collections (spec 10).
     */
    data class Purged(
        val tombstones: Int,
        val versions: Int,
        val seriesTombstones: Int = 0,
        val seriesVersions: Int = 0,
        val collectionTombstones: Int = 0,
        val collectionVersions: Int = 0,
        val collectionMembers: Int = 0,
    ) : PurgeOutcome

    /** The purge threw [cause]. The runner catches it and keeps it here; a purge failure never fails a sync. */
    data class Failed(
        val cause: Exception,
    ) : PurgeOutcome

    /** The purge did not run, and nothing was deleted: the device clock looks wrong ([reason]). */
    data class Skipped(
        val reason: PurgeSkip,
    ) : PurgeOutcome
}

/** Why a purge was skipped (Vimes L2). */
internal enum class PurgeSkip {
    /** `now` is before the last successful sync: the clock was set back. */
    ClockBehindLastSync,

    /** `now` is more than the retention minus 72 h after the last successful sync: the clock jumped forward. */
    ClockFarAhead,
}

/** An item row with its genre and tag rows. */
internal class ItemRows(
    val item: ItemEntity,
    val genres: List<GenreEntity>,
    val tags: List<TagEntity>,
)

/** What [CacheTransactionDao.writePage] did with a page's items. A replayed page is all [unchanged]. */
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

/** The account a sync belongs to is no longer the signed-in one: nothing it was about to write may be kept. */
internal class AccountNotActiveException : IllegalStateException("The account is not signed in any more")

/**
 * The SQL of the saved library. A lookup by a list of IDs must be given at most [MAX_VARIABLES] values: the platform
 * SQLite on API 25 allows 999 variables in a statement, and the repository chunks.
 */
@Dao
@Suppress("TooManyFunctions", "LargeClass") // The SQL surface of the cache: one method per statement or transaction.
internal abstract class LibraryDao {
    // Libraries.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertLibraries(rows: List<LibraryEntity>)

    @Query("SELECT libraryId FROM library WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun libraryIds(
        serverId: String,
        userId: String,
    ): List<String>

    @Query("DELETE FROM library WHERE serverId = :serverId AND userId = :userId AND libraryId IN (:ids)")
    abstract suspend fun deleteLibrariesIn(
        serverId: String,
        userId: String,
        ids: List<String>,
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
    protected abstract suspend fun setFetched(
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
            "AND runtimeMs IS NOT NULL AND runtimeMs > 0 ORDER BY itemId",
    )
    protected abstract suspend fun snapshotChunk(
        serverId: String,
        userId: String,
        libraries: List<String>,
        beforeMs: Long,
    ): List<ItemEntity>

    @Query(
        "SELECT COUNT(*) FROM item WHERE serverId = :serverId AND userId = :userId AND libraryId IN (:libraries) " +
            "AND deletedAtMs IS NULL AND (runtimeMs IS NULL OR runtimeMs <= 0)",
    )
    protected abstract suspend fun excludedChunk(
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

    // Every row the account has, live or tombstoned, in any library: the primary key's own prefix.
    @Query("SELECT itemId FROM item WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun allItemIds(
        serverId: String,
        userId: String,
    ): List<String>

    @Query(
        "SELECT itemId FROM item WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId " +
            "AND deletedAtMs IS NOT NULL ORDER BY itemId",
    )
    abstract suspend fun tombstonedItemIds(
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

    // The purge (spec 09, Unit 3). Plain DELETEs scoped to the account and driven by the two purge indexes; the
    // foreign keys take the genres, tags and versions of a deleted item with it. A tombstone also needs
    // seenFromMs < cutoff: after a clock set back, deletedAt can be older than the row itself, and the cascade would
    // take versions superseded inside the retention (Vimes L-1 on #268).
    @Query(
        "DELETE FROM item WHERE serverId = :serverId AND userId = :userId " +
            "AND deletedAtMs IS NOT NULL AND deletedAtMs < :cutoffMs AND seenFromMs < :cutoffMs",
    )
    abstract suspend fun deleteTombstonesBefore(
        serverId: String,
        userId: String,
        cutoffMs: Long,
    ): Int

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
    protected abstract suspend fun saveState(row: AccountStateEntity)

    @Query("SELECT * FROM account_state WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun state(
        serverId: String,
        userId: String,
    ): AccountStateEntity?

    @Query("UPDATE account_state SET total = :total WHERE serverId = :serverId AND userId = :userId")
    protected abstract suspend fun updateTotal(
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

    // The signed-in account.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertActive(row: ActiveAccountEntity)

    @Query("SELECT * FROM active_account")
    abstract suspend fun activeAccounts(): List<ActiveAccountEntity>

    @Query("SELECT COUNT(*) FROM active_account WHERE serverId = :serverId AND userId = :userId")
    protected abstract suspend fun activeCount(
        serverId: String,
        userId: String,
    ): Int

    @Query("DELETE FROM active_account WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun deleteActive(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM active_account WHERE NOT (serverId = :serverId AND userId = :userId)")
    abstract suspend fun deleteOtherActive(
        serverId: String,
        userId: String,
    )

    @Query("SELECT * FROM active_account LIMIT 1")
    abstract fun observeActive(): Flow<ActiveAccountEntity?>

    /** Throws [AccountNotActiveException] unless the account is signed in. Call it first inside a transaction. */
    suspend fun requireActive(
        serverId: String,
        userId: String,
    ) {
        if (activeCount(serverId, userId) == 0) throw AccountNotActiveException()
    }

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
        // An account that is not signed in (it signed out while the sync ran) gets no rows back.
        if (activeCount(serverId, userId) == 0) return
        // A "NOT IN" list can't be chunked, so find the libraries to drop here and delete them in chunks.
        val keep = chosen.toSet()
        libraryIds(serverId, userId).filter { it !in keep }.chunked(MAX_VARIABLES).forEach {
            deleteLibrariesIn(serverId, userId, it)
        }
        val rows = chosen.map { LibraryEntity(serverId, userId, it, fetched = false) }
        rows.chunked(MAX_VARIABLES).forEach { insertLibraries(it) }
    }

    // The names of the genres (or tags) of [ids], in order, by item. Chunked under MAX_VARIABLES.
    suspend fun genreNames(
        server: String,
        user: String,
        ids: Collection<String>,
    ): Map<String, List<String>> =
        ids
            .chunked(MAX_VARIABLES)
            .flatMap { genresFor(server, user, it) }
            .groupBy({ it.itemId }, { it.name })

    suspend fun tagNames(
        server: String,
        user: String,
        ids: Collection<String>,
    ): Map<String, List<String>> =
        ids
            .chunked(MAX_VARIABLES)
            .flatMap { tagsFor(server, user, it) }
            .groupBy({ it.itemId }, { it.name })

    /** The first sync starts or starts again: running, no total yet, the last success (if any) kept. */
    @Transaction
    open suspend fun beginFirstSync(
        serverId: String,
        userId: String,
    ) {
        requireActive(serverId, userId)
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
        requireActive(serverId, userId)
        val old = state(serverId, userId)
        val failed = AccountStateEntity.STATE_FAILED
        saveState(AccountStateEntity(serverId, userId, failed, old?.total, failure, old?.lastSuccessMs))
    }

    /** A failure is cleared and the account is idle again; the total and the last success are kept. */
    @Transaction
    open suspend fun clearFailure(
        serverId: String,
        userId: String,
    ) {
        requireActive(serverId, userId)
        val old = state(serverId, userId) ?: return
        if (old.state != AccountStateEntity.STATE_FAILED) return
        val idle = AccountStateEntity.STATE_IDLE
        saveState(AccountStateEntity(serverId, userId, idle, old.total, null, old.lastSuccessMs))
    }

    /** Page one said the whole sync holds [total] items. */
    @Transaction
    open suspend fun setTotal(
        serverId: String,
        userId: String,
        total: Int,
    ) {
        requireActive(serverId, userId)
        updateTotal(serverId, userId, total)
    }

    /** [libraryId] has been read to its end. */
    @Transaction
    open suspend fun markFetched(
        serverId: String,
        userId: String,
        libraryId: String,
    ) {
        requireActive(serverId, userId)
        setFetched(serverId, userId, libraryId)
    }

    /** A sync ended well at [nowMs]: idle, and the time of the success kept with the saved library. */
    @Transaction
    open suspend fun finishSync(
        serverId: String,
        userId: String,
        nowMs: Long,
    ) {
        requireActive(serverId, userId)
        saveState(AccountStateEntity(serverId, userId, AccountStateEntity.STATE_IDLE, null, null, nowMs))
    }

    /** The snapshot's items with their genres and tags, all from one moment. */
    @Transaction
    open suspend fun snapshotRows(
        serverId: String,
        userId: String,
        libraries: List<String>,
        beforeMs: Long,
    ): List<ItemRows> {
        val items =
            libraries
                .chunked(MAX_VARIABLES)
                .flatMap { snapshotChunk(serverId, userId, it, beforeMs) }
                .sortedBy { it.itemId }
        return withSideTables(serverId, userId, items)
    }

    /** How many live items of [libraries] have no usable running time. [libraries] may be any length. */
    open suspend fun excludedCount(
        serverId: String,
        userId: String,
        libraries: List<String>,
    ): Int = libraries.chunked(MAX_VARIABLES).sumOf { excludedChunk(serverId, userId, it) }

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
