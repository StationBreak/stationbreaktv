// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// The columns the as-of read takes from an item row or a version row, in the order of HeldRow.
private const val HELD_COLUMNS =
    "itemId, libraryId, kind, seriesId, seasonNumber, episodeNumber, runtimeMs, createdAtMs, premiereDate, " +
        "productionYear, deletedAtMs, seenFromMs"

// The same, qualified by the alias v, with the names the mapping expects.
private const val HELD_COLUMNS_OF_V =
    "v.itemId AS itemId, v.libraryId AS libraryId, v.kind AS kind, v.seriesId AS seriesId, " +
        "v.seasonNumber AS seasonNumber, v.episodeNumber AS episodeNumber, v.runtimeMs AS runtimeMs, " +
        "v.createdAtMs AS createdAtMs, v.premiereDate AS premiereDate, v.productionYear AS productionYear, " +
        "v.deletedAtMs AS deletedAtMs, v.seenFromMs AS seenFromMs"

/** How a purge ended (spec 09, Unit 3). */
internal sealed interface PurgeOutcome {
    /** The purge ran: [tombstones] tombstoned items and [versions] superseded versions were deleted. */
    data class Purged(
        val tombstones: Int,
        val versions: Int,
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

    /** `now` is more than the retention period after the last successful sync: the clock jumped forward. */
    ClockFarAhead,
}

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

    @Insert
    abstract suspend fun insertVersions(rows: List<ItemVersionEntity>)

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
    // foreign keys take the genres, tags and versions of a deleted item with it.
    @Query(
        "DELETE FROM item WHERE serverId = :serverId AND userId = :userId " +
            "AND deletedAtMs IS NOT NULL AND deletedAtMs < :cutoffMs",
    )
    protected abstract suspend fun deleteTombstonesBefore(
        serverId: String,
        userId: String,
        cutoffMs: Long,
    ): Int

    @Query(
        "DELETE FROM item_version WHERE serverId = :serverId AND userId = :userId AND supersededAtMs < :cutoffMs",
    )
    protected abstract suspend fun deleteVersionsSupersededBefore(
        serverId: String,
        userId: String,
        cutoffMs: Long,
    ): Int

    /**
     * Purges the account's history older than [retentionMs] at [nowMs], in one transaction after [requireActive]: the
     * versions superseded, and the tombstones set, more than that long ago. The current version of a live item is
     * never touched, and a purged tombstone takes its genres, tags and versions with it (the foreign keys). The
     * versions go first, so the count is of the ones superseded long ago and not of the ones a tombstone takes with it.
     * Both deletes are plain SQL and nothing is passed in but numbers, so no callback runs inside the transaction.
     *
     * A clock that looks wrong purges nothing (Vimes L2): [nowMs] before the last successful sync, or more than
     * [retentionMs] after it, would make the age of every row meaningless. With no success recorded yet there is
     * nothing to compare with, and the purge runs.
     */
    @Transaction
    open suspend fun purgeHistory(
        serverId: String,
        userId: String,
        nowMs: Long,
        retentionMs: Long,
    ): PurgeOutcome {
        requireActive(serverId, userId)
        val skip = clockLooksWrong(state(serverId, userId)?.lastSuccessMs, nowMs, retentionMs)
        if (skip != null) return PurgeOutcome.Skipped(skip)
        val cutoffMs = nowMs - retentionMs
        val versions = deleteVersionsSupersededBefore(serverId, userId, cutoffMs)
        val tombstones = deleteTombstonesBefore(serverId, userId, cutoffMs)
        return PurgeOutcome.Purged(tombstones = tombstones, versions = versions)
    }

    private fun clockLooksWrong(
        lastSuccessMs: Long?,
        nowMs: Long,
        retentionMs: Long,
    ): PurgeSkip? =
        when {
            lastSuccessMs == null -> null
            nowMs < lastSuccessMs -> PurgeSkip.ClockBehindLastSync
            nowMs - lastSuccessMs > retentionMs -> PurgeSkip.ClockFarAhead
            else -> null
        }

    // The as-of read (spec 09, Unit 2). All three select the same columns, from the item row or from a version, so one
    // mapping serves both. They page by item-ID range (no IN lists), so a chunk is bounded by LIMIT, not by the 999
    // variables of API 25's SQLite.

    @Query(
        "SELECT $HELD_COLUMNS FROM item " +
            "WHERE serverId = :serverId AND userId = :userId AND itemId > :after ORDER BY itemId LIMIT :limit",
    )
    protected abstract suspend fun currentChunk(
        serverId: String,
        userId: String,
        after: String,
        limit: Int,
    ): List<HeldRow>

    // Case (b): the one version that was current just before the cut-off. The intervals are contiguous, so there is at
    // most one per item.
    @Query(
        "SELECT $HELD_COLUMNS FROM item_version " +
            "WHERE serverId = :serverId AND userId = :userId AND itemId > :after AND itemId <= :last " +
            "AND seenFromMs < :cutoffMs AND supersededAtMs >= :cutoffMs",
    )
    protected abstract suspend fun coveringVersions(
        serverId: String,
        userId: String,
        after: String,
        last: String,
        cutoffMs: Long,
    ): List<HeldRow>

    // Case (c): the oldest version, found by probing the item's firstSeenMs, for an item that nothing older than the
    // cut-off holds. The columns are the same, qualified by the version.
    @Query(
        "SELECT $HELD_COLUMNS_OF_V FROM item_version v " +
            "JOIN item i ON i.serverId = v.serverId AND i.userId = v.userId AND i.itemId = v.itemId " +
            "AND i.firstSeenMs = v.seenFromMs " +
            "WHERE v.serverId = :serverId AND v.userId = :userId AND v.itemId > :after AND v.itemId <= :last " +
            "AND v.seenFromMs >= :cutoffMs",
    )
    protected abstract suspend fun oldestVersions(
        serverId: String,
        userId: String,
        after: String,
        last: String,
        cutoffMs: Long,
    ): List<HeldRow>

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
    protected abstract suspend fun deleteOtherActive(
        serverId: String,
        userId: String,
    )

    @Query("SELECT * FROM active_account LIMIT 1")
    abstract fun observeActive(): Flow<ActiveAccountEntity?>

    /** Throws [AccountNotActiveException] unless the account is signed in. Call it first inside a transaction. */
    protected suspend fun requireActive(
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

    @Query("SELECT COUNT(*) FROM item_version")
    abstract suspend fun countVersions(): Int

    @Query("SELECT * FROM item_version WHERE serverId = :serverId AND userId = :userId ORDER BY itemId, seenFromMs")
    abstract suspend fun allVersions(
        serverId: String,
        userId: String,
    ): List<ItemVersionEntity>

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

    /** [serverId] and [userId] are signed in: every other account's rows go. */
    @Transaction
    open suspend fun activate(
        serverId: String,
        userId: String,
    ) {
        deleteOtherLibraries(serverId, userId)
        deleteOtherStates(serverId, userId)
        deleteOtherActive(serverId, userId)
        insertActive(ActiveAccountEntity(serverId, userId))
    }

    /** The account signed out: its rows go. */
    @Transaction
    open suspend fun forget(
        serverId: String,
        userId: String,
    ) {
        deleteLibraries(serverId, userId)
        deleteState(serverId, userId)
        deleteActive(serverId, userId)
    }

    /**
     * Writes one page and its checkpoint together (no checkpoint when [checkpoint] is null: a changed-since page moves
     * no position). An item the database already holds, equal, is left alone; one that differs is updated and its
     * genres and tags replaced; a new one is inserted. A change to a field the scheduler reads (genres and tags
     * included), or a row that clears a tombstone, first keeps the old row as a version (spec 09). The account must
     * still be signed in, which is checked inside this transaction. [nowMs] stamps new rows and versions.
     * [betweenItemsAndCheckpoint] runs after the items and before the checkpoint (a test seam; null in the app).
     */
    @Transaction
    @Suppress("LongParameterList") // One cohesive call: the page, its checkpoint, the time and the test seam.
    open suspend fun writePage(
        server: String,
        user: String,
        checkpoint: CheckpointEntity?,
        items: Map<String, ItemRows>,
        nowMs: Long,
        betweenItemsAndCheckpoint: (() -> Unit)?,
    ): PageWrite {
        requireActive(server, user)
        val existing =
            items.keys
                .chunked(MAX_VARIABLES)
                .flatMap { itemsByIds(server, user, it) }
                .associateBy { it.itemId }
        // The old genres and tags are read here, inside the transaction and before they are deleted below, so a version
        // can hold them and a genre-only or tag-only edit is seen as a change (Vimes H3).
        val oldGenres = genreNames(server, user, existing.keys)
        val oldTags = tagNames(server, user, existing.keys)
        val toInsert = mutableListOf<ItemEntity>()
        val toUpdate = mutableListOf<ItemEntity>()
        val versions = mutableListOf<ItemVersionEntity>()
        var unchanged = 0
        for ((id, rows) in items) {
            val old = existing[id]
            if (old == null) {
                toInsert += rows.item.copy(seenFromMs = nowMs, firstSeenMs = nowMs)
                continue
            }
            val genres = rows.genres.map { it.name }
            val tags = rows.tags.map { it.name }
            val changed =
                !old.sameScheduleFields(rows.item) || oldGenres[id].orEmpty() != genres || oldTags[id].orEmpty() != tags
            // A row that clears the tombstone is a change too, even when no field differs (Vimes H2).
            val revived = old.deletedAtMs != null && rows.item.deletedAtMs == null
            val kept = rows.item.copy(seenFromMs = old.seenFromMs, firstSeenMs = old.firstSeenMs)
            when {
                changed || revived -> {
                    val stamp = nextStamp(old, nowMs)
                    versions += old.toVersion(stamp, oldGenres[id].orEmpty(), oldTags[id].orEmpty())
                    toUpdate += rows.item.copy(seenFromMs = stamp, firstSeenMs = old.firstSeenMs)
                }

                // Only the name or the parent differs: stored, but it is no new version of the schedule's inputs.
                old != kept -> {
                    toUpdate += kept
                }

                else -> {
                    unchanged++
                }
            }
        }
        insertItems(toInsert)
        insertVersions(versions)
        updateItems(toUpdate)
        val changedIds = (toInsert + toUpdate).map { it.itemId }
        changedIds.chunked(MAX_VARIABLES).forEach {
            deleteGenres(server, user, it)
            deleteTags(server, user, it)
        }
        insertGenres(changedIds.flatMap { items.getValue(it).genres })
        insertTags(changedIds.flatMap { items.getValue(it).tags })
        betweenItemsAndCheckpoint?.invoke()
        checkpoint?.let { saveCheckpoint(it) }
        return PageWrite(toInsert.size, toUpdate.size, unchanged)
    }

    // The names of the genres (or tags) of [ids], in order, by item. Chunked under MAX_VARIABLES.
    private suspend fun genreNames(
        server: String,
        user: String,
        ids: Collection<String>,
    ): Map<String, List<String>> =
        ids
            .chunked(MAX_VARIABLES)
            .flatMap { genresFor(server, user, it) }
            .groupBy({ it.itemId }, { it.name })

    private suspend fun tagNames(
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

    /** Marks [ids] deleted at [atMs] (an existing tombstone keeps its time). */
    @Transaction
    open suspend fun tombstoneAll(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    ) {
        requireActive(serverId, userId)
        ids.chunked(MAX_VARIABLES).forEach { tombstone(serverId, userId, it, atMs) }
    }

    /**
     * Clears the tombstone of [ids] at [nowMs]. Each tombstoned row is copied as a version first, ending at the new
     * stamp (Vimes H2), so a day built from before the revival still sees the deletion. An id that is not a tombstone
     * is left alone.
     */
    @Transaction
    open suspend fun reviveAll(
        serverId: String,
        userId: String,
        ids: List<String>,
        nowMs: Long,
    ) {
        requireActive(serverId, userId)
        ids.chunked(MAX_VARIABLES).forEach { chunk ->
            val rows = itemsByIds(serverId, userId, chunk).filter { it.deletedAtMs != null }
            if (rows.isEmpty()) return@forEach
            val rowIds = rows.map { it.itemId }
            val genres = genreNames(serverId, userId, rowIds)
            val tags = tagNames(serverId, userId, rowIds)
            val stamps = rows.associate { it.itemId to nextStamp(it, nowMs) }
            insertVersions(
                rows.map {
                    val id = it.itemId
                    it.toVersion(stamps.getValue(id), genres[id].orEmpty(), tags[id].orEmpty())
                },
            )
            updateItems(rows.map { it.copy(deletedAtMs = null, seenFromMs = stamps.getValue(it.itemId)) })
        }
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

    /**
     * The version of every item of the account that this TV held before [cutoffMs], read [chunkSize] items at a time
     * in item-ID order inside one read transaction (Vimes H4, M2), and returned in item-ID order. Selection runs over
     * every item first; only then are the rows of [libraries] kept, less those whose held version was deleted before
     * the cut-off (`deletedAt < cutoffMs`). The validity checks are the caller's.
     *
     * It returns a list and takes no callback on purpose. An earlier version handed each chunk to a lambda; on API 25
     * (ART 7.1) a second caller with a different lambda class made the JIT-compiled loop deoptimise, and the next GC
     * checkpoint aborted the process with "Tried to mark 0xebadde09 not contained by any spaces" (found in PR 264's
     * on-device heap test; reproduced 11 of 12 runs under CPU load, 0 of 6 with the JIT off). A call site that always
     * sees one class never deoptimises, and a list has no call site to speak of.
     *
     * Per item: (a) the current row if it was first seen before the cut-off; else (b) the version that was current just
     * before it; else (c) the oldest version, or the current row if the item has none. Cases (b) and (c) are looked up
     * only for a chunk that has an item in need of them.
     */
    @Transaction
    open suspend fun heldAsOf(
        serverId: String,
        userId: String,
        cutoffMs: Long,
        chunkSize: Int,
        libraries: Set<String>,
    ): List<HeldRow> {
        val held = ArrayList<HeldRow>()
        var after = ""
        while (true) {
            val current = currentChunk(serverId, userId, after, chunkSize)
            if (current.isEmpty()) return held
            val last = current.last().itemId
            val needsHistory = current.any { it.seenFromMs >= cutoffMs }
            val covering = mutableMapOf<String, HeldRow>()
            val oldest = mutableMapOf<String, HeldRow>()
            if (needsHistory) {
                coveringVersions(serverId, userId, after, last, cutoffMs).associateByTo(covering) { it.itemId }
                oldestVersions(serverId, userId, after, last, cutoffMs).associateByTo(oldest) { it.itemId }
            }
            for (row in current) {
                val version = if (row.seenFromMs < cutoffMs) row else covering[row.itemId] ?: oldest[row.itemId] ?: row
                val deletedAt = version.deletedAtMs
                if (version.libraryId in libraries && (deletedAt == null || deletedAt >= cutoffMs)) held += version
            }
            if (current.size < chunkSize) return held
            after = last
        }
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
