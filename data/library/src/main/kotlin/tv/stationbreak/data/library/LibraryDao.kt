// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

// The value of one identity key in the `key=value;key=value` text of the provider ids column (the six characters of
// ';key=' are skipped), or NULL when the key is not there (spec 26). Used by the re-id read to find a successor by id.
private const val PROVIDER_TEXT = "(';' || providerIds || ';')"

private const val IMDB_REST = "substr($PROVIDER_TEXT, instr($PROVIDER_TEXT, ';imdb=') + 6)"
private const val TMDB_REST = "substr($PROVIDER_TEXT, instr($PROVIDER_TEXT, ';tmdb=') + 6)"
private const val TVDB_REST = "substr($PROVIDER_TEXT, instr($PROVIDER_TEXT, ';tvdb=') + 6)"
private const val IMDB_VALUE =
    "CASE WHEN instr($PROVIDER_TEXT, ';imdb=') > 0 THEN substr($IMDB_REST, 1, instr($IMDB_REST, ';') - 1) END"
private const val TMDB_VALUE =
    "CASE WHEN instr($PROVIDER_TEXT, ';tmdb=') > 0 THEN substr($TMDB_REST, 1, instr($TMDB_REST, ';') - 1) END"
private const val TVDB_VALUE =
    "CASE WHEN instr($PROVIDER_TEXT, ';tvdb=') > 0 THEN substr($TVDB_REST, 1, instr($TVDB_REST, ';') - 1) END"

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

/** An item row with its genre, tag and studio rows. */
internal class ItemRows(
    val item: ItemEntity,
    val genres: List<GenreEntity>,
    val tags: List<TagEntity>,
    val studios: List<StudioEntity>,
)

/** One live item's names, with its live series' name when it has one (see [LibraryDao.programRows]). */
internal data class ProgramRow(
    val itemId: String,
    val kind: String,
    val name: String,
    val seriesName: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
) {
    // The names are text a person typed: they never reach a log.
    override fun toString(): String =
        "ProgramRow(itemId=$itemId, kind=$kind, name=<name>, " +
            "seriesName=${if (seriesName == null) "null" else "<name>"}, seasonNumber=$seasonNumber, episodeNumber=$episodeNumber)"
}

/** What the re-id match reads of one stored movie or episode (spec 26); the text is never logged. */
@Suppress("LongParameterList") // One parameter per column the query reads.
internal class SignalRow(
    val itemId: String,
    val kind: String,
    val seriesId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val name: String,
    val productionYear: Int?,
    val runtimeMs: Long?,
    val providerIds: String,
) {
    override fun toString(): String = "SignalRow(itemId=$itemId, kind=$kind)"
}

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

    @Query("SELECT * FROM library WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun libraries(
        serverId: String,
        userId: String,
    ): List<LibraryEntity>

    // The libraries being added (spec 26): what is written to them moves no content generation.
    @Query("SELECT libraryId FROM library WHERE serverId = :serverId AND userId = :userId AND addStamp IS NOT NULL")
    abstract suspend fun addingLibraryIds(
        serverId: String,
        userId: String,
    ): List<String>

    @Update
    protected abstract suspend fun updateLibraries(rows: List<LibraryEntity>)

    // The series rows of [ids] (at most MAX_VARIABLES libraries), tombstones included: the cascade would take them.
    @Query("SELECT seriesId FROM series WHERE serverId = :serverId AND userId = :userId AND libraryId IN (:ids)")
    protected abstract suspend fun seriesIdsIn(
        serverId: String,
        userId: String,
        ids: List<String>,
    ): List<String>

    // Moves the series [ids] (at most MAX_VARIABLES of them) to [libraryId]: an unversioned write, as a page's is.
    @Query(
        "UPDATE series SET libraryId = :libraryId WHERE serverId = :serverId AND userId = :userId " +
            "AND seriesId IN (:ids)",
    )
    protected abstract suspend fun rehomeSeries(
        serverId: String,
        userId: String,
        libraryId: String,
        ids: List<String>,
    )

    // Whether [libraryId] holds any item or series row, tombstones included: both lookups use the library index.
    @Query(
        "SELECT EXISTS(SELECT 1 FROM item WHERE serverId = :serverId AND userId = :userId " +
            "AND libraryId = :libraryId) OR EXISTS(SELECT 1 FROM series WHERE serverId = :serverId " +
            "AND userId = :userId AND libraryId = :libraryId)",
    )
    protected abstract suspend fun holdsRows(
        serverId: String,
        userId: String,
        libraryId: String,
    ): Boolean

    @Query("DELETE FROM checkpoint WHERE serverId = :serverId AND userId = :userId AND libraryId IN (:ids)")
    protected abstract suspend fun deleteCheckpointsIn(
        serverId: String,
        userId: String,
        ids: List<String>,
    )

    // A library being added is never landed here (spec 26): only a row with no add stamp is marked fetched.
    @Query(
        "UPDATE library SET fetched = 1 WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId " +
            "AND addStamp IS NULL",
    )
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

    // The distinct series the live episodes of [libraryId] name, listed or not (issue 604).
    @Query(
        "SELECT DISTINCT seriesId FROM item WHERE serverId = :serverId AND userId = :userId " +
            "AND libraryId = :libraryId AND deletedAtMs IS NULL AND seriesId IS NOT NULL ORDER BY seriesId",
    )
    abstract suspend fun referencedSeriesIds(
        serverId: String,
        userId: String,
        libraryId: String,
    ): List<String>

    // The names of the live items among [ids] (at most MAX_VARIABLES of them), with the live series' name (spec 14).
    @Query(
        "SELECT item.itemId AS itemId, item.kind AS kind, item.name AS name, series.name AS seriesName, " +
            "item.seasonNumber AS seasonNumber, item.episodeNumber AS episodeNumber FROM item " +
            "LEFT JOIN series ON series.serverId = item.serverId AND series.userId = item.userId " +
            "AND series.seriesId = item.seriesId AND series.deletedAtMs IS NULL " +
            "WHERE item.serverId = :serverId AND item.userId = :userId AND item.deletedAtMs IS NULL " +
            "AND item.itemId IN (:ids)",
    )
    abstract suspend fun programRows(
        serverId: String,
        userId: String,
        ids: List<String>,
    ): List<ProgramRow>

    // The re-id match's reads (spec 26). The tombstones among [ids] (at most MAX_VARIABLES - 2 of them) deleted at or
    // after [sinceMs], the cache's retention: older ones are as good as purged.
    @Query(
        "SELECT itemId, kind, seriesId, seasonNumber, episodeNumber, name, productionYear, runtimeMs, providerIds " +
            "FROM item WHERE serverId = :serverId AND userId = :userId AND deletedAtMs IS NOT NULL " +
            "AND deletedAtMs >= :sinceMs AND kind IN ('Movie', 'Episode') AND itemId IN (:ids)",
    )
    abstract suspend fun goneSignalRows(
        serverId: String,
        userId: String,
        sinceMs: Long,
        ids: List<String>,
    ): List<SignalRow>

    // The live movies and episodes of [libraries] that can be a gone item's successor: an episode of one of
    // [seriesIds] (stored spellings), a film whose lower-cased name is one of [names] (SQLite's lower() folds ASCII
    // only, as foldNameAscii does), or an item with one of the identity ids. The caller checks every row it gets back
    // with the rules; this only keeps the rest of the library out of memory. At most MAX_VARIABLES binds in all.
    @Suppress("LongParameterList") // One list per way a successor is found.
    @Query(
        "SELECT itemId, kind, seriesId, seasonNumber, episodeNumber, name, productionYear, runtimeMs, providerIds " +
            "FROM item WHERE serverId = :serverId AND userId = :userId AND deletedAtMs IS NULL " +
            "AND kind IN ('Movie', 'Episode') AND libraryId IN (:libraries) AND (seriesId IN (:seriesIds) " +
            "OR lower(name) IN (:names) OR " + IMDB_VALUE + " IN (:imdb) OR " + TMDB_VALUE + " IN (:tmdb) " +
            "OR " + TVDB_VALUE + " IN (:tvdb))",
    )
    abstract suspend fun liveSignalRows(
        serverId: String,
        userId: String,
        libraries: List<String>,
        seriesIds: List<String>,
        names: List<String>,
        imdb: List<String>,
        tmdb: List<String>,
        tvdb: List<String>,
    ): List<SignalRow>

    // Every live movie and episode of [libraries] (at most MAX_VARIABLES - 2 of them): the re-id read when the gone
    // items are too many for a few narrowed reads to be cheaper (spec 26).
    @Query(
        "SELECT itemId, kind, seriesId, seasonNumber, episodeNumber, name, productionYear, runtimeMs, providerIds " +
            "FROM item WHERE serverId = :serverId AND userId = :userId AND deletedAtMs IS NULL " +
            "AND kind IN ('Movie', 'Episode') AND libraryId IN (:libraries)",
    )
    abstract suspend fun allLiveSignalRows(
        serverId: String,
        userId: String,
        libraries: List<String>,
    ): List<SignalRow>

    // Which of [ids] (at most MAX_VARIABLES of them) are live items of the account: no join, no names (spec 14).
    @Query(
        "SELECT itemId FROM item WHERE serverId = :serverId AND userId = :userId AND deletedAtMs IS NULL " +
            "AND itemId IN (:ids)",
    )
    abstract suspend fun liveAmong(
        serverId: String,
        userId: String,
        ids: List<String>,
    ): List<String>

    // The picker's reads (the builder, spec 22): the current state, never for building a day. No schema change.
    @Query(
        "SELECT DISTINCT g.name FROM item_genre g JOIN item i ON i.serverId = g.serverId AND i.userId = g.userId " +
            "AND i.itemId = g.itemId WHERE g.serverId = :serverId AND g.userId = :userId AND i.deletedAtMs IS NULL",
    )
    abstract suspend fun liveGenreNames(
        serverId: String,
        userId: String,
    ): List<String>

    @Query(
        "SELECT DISTINCT t.name FROM item_tag t JOIN item i ON i.serverId = t.serverId AND i.userId = t.userId " +
            "AND i.itemId = t.itemId WHERE t.serverId = :serverId AND t.userId = :userId AND i.deletedAtMs IS NULL",
    )
    abstract suspend fun liveTagNames(
        serverId: String,
        userId: String,
    ): List<String>

    @Query(
        "SELECT DISTINCT s.name FROM item_studio s JOIN item i ON i.serverId = s.serverId AND i.userId = s.userId " +
            "AND i.itemId = s.itemId WHERE s.serverId = :serverId AND s.userId = :userId AND i.deletedAtMs IS NULL",
    )
    abstract suspend fun liveStudioNames(
        serverId: String,
        userId: String,
    ): List<String>

    // [pattern] is bound, never joined into the text; likePattern escapes it for the ESCAPE clause.
    @Query(
        "SELECT itemId AS id, name AS name, productionYear AS year FROM item WHERE serverId = :serverId " +
            "AND userId = :userId AND deletedAtMs IS NULL AND kind = 'Movie' AND name LIKE :pattern ESCAPE '\\' " +
            "ORDER BY name, itemId LIMIT :limit",
    )
    abstract suspend fun searchMovies(
        serverId: String,
        userId: String,
        pattern: String,
        limit: Int,
    ): List<TitleRow>

    // The names of the live movies among [ids] (at most MAX_VARIABLES of them).
    @Query(
        "SELECT itemId AS id, name AS name FROM item WHERE serverId = :serverId AND userId = :userId " +
            "AND deletedAtMs IS NULL AND kind = 'Movie' AND itemId IN (:ids)",
    )
    abstract suspend fun movieNames(
        serverId: String,
        userId: String,
        ids: List<String>,
    ): List<NameRow>

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
    // The rows it tombstoned: an ID already a tombstone, or not held, is not counted (#480).
    abstract suspend fun tombstone(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    ): Int

    // The purge (spec 09, Unit 3). Plain DELETEs scoped to the account and driven by the two purge indexes; the
    // foreign keys take the genres, tags, studios and versions of a deleted item with it. A tombstone also needs
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

    // Genres, tags and studios.

    @Insert
    abstract suspend fun insertGenres(rows: List<GenreEntity>)

    @Insert
    abstract suspend fun insertTags(rows: List<TagEntity>)

    @Insert
    abstract suspend fun insertStudios(rows: List<StudioEntity>)

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

    @Query("DELETE FROM item_studio WHERE serverId = :serverId AND userId = :userId AND itemId IN (:ids)")
    abstract suspend fun deleteStudios(
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

    @Query(
        "SELECT * FROM item_studio WHERE serverId = :serverId AND userId = :userId AND itemId IN (:ids) " +
            "ORDER BY itemId, position",
    )
    abstract suspend fun studiosFor(
        serverId: String,
        userId: String,
        ids: List<String>,
    ): List<StudioEntity>

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun ensureMeta(row: SyncMetaEntity)

    @Query(
        "UPDATE sync_meta SET contentGeneration = contentGeneration + 1 " +
            "WHERE serverId = :serverId AND userId = :userId",
    )
    abstract suspend fun raiseGeneration(
        serverId: String,
        userId: String,
    )

    /**
     * Raises the account's content generation by one. Call it inside the transaction of the write that changed rows,
     * so a rollback takes the bump with it.
     */
    suspend fun bumpGeneration(
        serverId: String,
        userId: String,
    ) {
        ensureMetaRow(serverId, userId)
        raiseGeneration(serverId, userId)
    }

    /**
     * Where a new `sync_meta` row starts counting: set by the repository to its clock in milliseconds shifted left by
     * ten bits. A row that is deleted (sign-out, another account, a rebuild) and made again therefore starts above
     * every value the old row reached, as long as it gained fewer than 1024 bumps per millisecond it lived, and so a
     * generation stored with a lineup is never equalled by a different library state. A clock set far back could
     * break that; the lineup is then at worst refreshed or, in a freak case, taken for current.
     */
    @Volatile
    var generationSeed: () -> Long = seedFrom(Clock.System)

    /** Makes sure the account has a `sync_meta` row, starting its generation at [generationSeed]. */
    suspend fun ensureMetaRow(
        serverId: String,
        userId: String,
    ) = ensureMeta(SyncMetaEntity(serverId, userId, generationSeed()))

    @Query("SELECT contentGeneration FROM sync_meta WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun generation(
        serverId: String,
        userId: String,
    ): Long?

    @Query("SELECT * FROM sync_meta WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun metaRow(
        serverId: String,
        userId: String,
    ): SyncMetaEntity?

    /** Sets the backfill flag. Targeted on purpose: a whole-row write of `sync_meta` would reset it to its default. */
    @Query(
        "UPDATE sync_meta SET backfillPending = :pending " +
            "WHERE serverId = :serverId AND userId = :userId",
    )
    abstract suspend fun setBackfillPending(
        serverId: String,
        userId: String,
        pending: Boolean,
    )

    @Query(
        "UPDATE sync_meta SET failedPasses = :failed, passRunning = :running " +
            "WHERE serverId = :serverId AND userId = :userId",
    )
    abstract suspend fun setPassState(
        serverId: String,
        userId: String,
        failed: Int,
        running: Boolean,
    )

    @Query("DELETE FROM sync_meta WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun deleteMeta(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM sync_meta WHERE NOT (serverId = :serverId AND userId = :userId)")
    abstract suspend fun deleteOtherMeta(
        serverId: String,
        userId: String,
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

    @Query("SELECT COUNT(*) FROM item_studio")
    abstract suspend fun countStudios(): Int

    @Query("SELECT COUNT(*) FROM library")
    abstract suspend fun countLibraries(): Int

    @Query("SELECT COUNT(*) FROM checkpoint")
    abstract suspend fun countCheckpoints(): Int

    @Query("SELECT COUNT(*) FROM account_state")
    abstract suspend fun countStates(): Int

    // The transactions. They are DAO methods, not `withTransaction` blocks, so they work whether Room runs over the
    // platform's SQLiteOpenHelper (the app) or over a SQLiteDriver (the JVM tests). Each runs as one transaction: it
    // commits whole or rolls back whole.

    /**
     * The one writer of the account's library set (spec 26). Every library in [keep] is kept with no hold: a new one
     * is inserted pending, with its stamp from [addStamps] if it has one. A kept library given a stamp it did not have
     * (a new add, or the re-tick of a held or earlier library) is set pending and its checkpoint deleted, so the sync
     * walks it again from the start; one not named in [addStamps] keeps the stamp it has. Any other library is kept
     * while its hold, from [holds] or else the one it has, lies after [nowMs], and its rows are deleted otherwise (with
     * everything under them, through the foreign keys). A stamp or hold for a library it does not apply to (a stamp
     * outside [keep], a hold inside it, a hold for a library not stored) is ignored.
     *
     * The content generation moves once, and only when a library's rows are deleted: an unticked library, a hold that
     * expired, or an add dropped after its walk wrote rows (they may have moved in from a chosen library, or be a held
     * library's, re-ticked). A tick, a hold set or cleared, and an add dropped before it holds a row move nothing: none
     * of them changes what a day is built from. With [keepAdding], every library
     * stored with a stamp also counts as in [keep], read inside this transaction (the first sync does not know the
     * adds). An account that is not signed in gets no rows back.
     *
     * A new stamp may go only to a library that is new, held or already being added, never to a chosen library that
     * has landed: that throws [IllegalArgumentException] and writes nothing. Before a library's rows are deleted, each
     * series row of it that a live episode of a library that stays (kept or held) still names moves to that library
     * (the smallest such ID), so a series listed in two libraries outlives the one that wrote it last; the move alone
     * bumps nothing. [holdClockGuardMs] is the purge's forward-jump guard: when [nowMs] is more than that after the
     * account's last successful sync, the clock looks wrong and no hold expires in this call (its rows and its hold are
     * kept). Null (tests) means no guard.
     */
    @Transaction
    @Suppress("LongParameterList", "CyclomaticComplexMethod") // The selection, its stamps and holds, and the clock.
    open suspend fun applySelection(
        serverId: String,
        userId: String,
        keep: Set<String>,
        addStamps: Map<String, Long>,
        holds: Map<String, Long>,
        nowMs: Long,
        keepAdding: Boolean = false,
        holdClockGuardMs: Long? = null,
    ) {
        // An account that is not signed in (it signed out while the sync ran) gets no rows back.
        if (activeCount(serverId, userId) == 0) return
        val stored = libraries(serverId, userId)
        val lastSuccessMs = state(serverId, userId)?.lastSuccessMs
        // A clock far ahead of the last success (as the purge's guard reads it) expires no hold (Vimes, spec 26).
        val holdsFrozen = holdClockGuardMs != null && lastSuccessMs != null && nowMs - lastSuccessMs > holdClockGuardMs
        val kept = if (keepAdding) keep + stored.filter { it.addStamp != null }.map { it.libraryId } else keep
        val byId = stored.associateBy { it.libraryId }
        val toUpdate = mutableListOf<LibraryEntity>()
        val toDelete = mutableListOf<String>()
        val rewalk = mutableListOf<String>()
        var bump = false
        for (row in stored) {
            if (row.libraryId in kept) continue
            val until = holds[row.libraryId] ?: row.heldUntilMs
            if (until != null && (until > nowMs || holdsFrozen)) {
                if (until != row.heldUntilMs) toUpdate += row.copy(heldUntilMs = until)
            } else {
                toDelete += row.libraryId
                // An add's rows can be ones a day was built from: an item or series that moved in from a chosen
                // library (its versions go with it), or a held library re-ticked and then dropped. So an add goes
                // without a bump only when it holds no row at all.
                if (row.addStamp == null || holdsRows(serverId, userId, row.libraryId)) bump = true
            }
        }
        val toInsert = mutableListOf<LibraryEntity>()
        for (id in kept) {
            val row = byId[id]
            val stamp = addStamps[id]
            if (row == null) {
                toInsert += LibraryEntity(serverId, userId, id, fetched = false, heldUntilMs = null, addStamp = stamp)
                continue
            }
            val newStamp = stamp != null && stamp != row.addStamp
            require(!newStamp || row.heldUntilMs != null || row.addStamp != null) {
                "A new add stamp goes only to a library that is new, held or being added"
            }
            if (newStamp) rewalk += id
            val next =
                row.copy(
                    fetched = row.fetched && !newStamp,
                    heldUntilMs = null,
                    addStamp = stamp ?: row.addStamp,
                )
            if (next != row) toUpdate += next
        }
        if (bump) bumpGeneration(serverId, userId)
        if (toDelete.isNotEmpty()) {
            rehomeSharedSeries(serverId, userId, toDelete, stored.map { it.libraryId } - toDelete.toSet())
        }
        toDelete.chunked(MAX_VARIABLES).forEach { deleteLibrariesIn(serverId, userId, it) }
        rewalk.chunked(MAX_VARIABLES).forEach { deleteCheckpointsIn(serverId, userId, it) }
        if (toUpdate.isNotEmpty()) updateLibraries(toUpdate)
        toInsert.chunked(MAX_VARIABLES).forEach { insertLibraries(it) }
    }

    /**
     * Before [gone] are deleted: each of their series rows that a live episode of one of [staying] names moves to the
     * smallest such library, so the cascade does not take a series another library still uses. The episodes are read
     * per staying library through the library index ([referencedSeriesIds]), never by scanning for a series ID.
     */
    private suspend fun rehomeSharedSeries(
        serverId: String,
        userId: String,
        gone: List<String>,
        staying: List<String>,
    ) {
        if (staying.isEmpty()) return
        val orphans = HashSet<String>()
        for (chunk in gone.chunked(MAX_VARIABLES)) orphans.addAll(seriesIdsIn(serverId, userId, chunk))
        for (library in staying.sorted()) {
            if (orphans.isEmpty()) return
            val named = referencedSeriesIds(serverId, userId, library).filter { it in orphans }
            for (chunk in named.chunked(MAX_VARIABLES)) rehomeSeries(serverId, userId, library, chunk)
            orphans.removeAll(named.toSet())
        }
    }

    /** The names of the genres, tags and studios of [ids], in order, by item. Chunked under MAX_VARIABLES. */
    suspend fun sideNames(
        server: String,
        user: String,
        ids: Collection<String>,
    ): SideNames {
        val chunks = ids.chunked(MAX_VARIABLES)
        return SideNames(
            genres = chunks.flatMap { genresFor(server, user, it) }.groupBy({ it.itemId }, { it.name }),
            tags = chunks.flatMap { tagsFor(server, user, it) }.groupBy({ it.itemId }, { it.name }),
            studios = chunks.flatMap { studiosFor(server, user, it) }.groupBy({ it.itemId }, { it.name }),
        )
    }

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
        saveFailed(serverId, userId, state(serverId, userId), failure)
    }

    /**
     * A stay-current pass stopped for [failure]: as [failSync], but it does nothing while the account runs a first sync
     * (one may start during a long pass), so that sync's state survives. False when it did nothing for that reason.
     */
    @Transaction
    open suspend fun failStayCurrent(
        serverId: String,
        userId: String,
        failure: Int,
    ): Boolean {
        requireActive(serverId, userId)
        val old = state(serverId, userId)
        if (old?.state == AccountStateEntity.STATE_FIRST_RUNNING) return false
        saveFailed(serverId, userId, old, failure)
        return true
    }

    private suspend fun saveFailed(
        serverId: String,
        userId: String,
        old: AccountStateEntity?,
        failure: Int,
    ) {
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

    /** [libraryId] has been read to its end. A library being added (it has an add stamp) is left pending. */
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
        saveIdle(serverId, userId, nowMs)
    }

    /**
     * An incremental pass ended well at [nowMs]. It saves what [finishSync] saves, unless the account is running a
     * first sync (a library-selection change started one during the pass): then it does nothing, so that sync's state
     * survives, and returns false.
     */
    @Transaction
    open suspend fun finishIncremental(
        serverId: String,
        userId: String,
        nowMs: Long,
    ): Boolean {
        requireActive(serverId, userId)
        if (state(serverId, userId)?.state == AccountStateEntity.STATE_FIRST_RUNNING) return false
        saveIdle(serverId, userId, nowMs)
        return true
    }

    /**
     * The studios re-read ended well (spec 18, ruling R9): when [allWalked] (every chosen library was read) the
     * account's `backfillPending` flag is cleared. Nothing else is written: the state and the last success stay as
     * they are. While the account runs a first sync it does nothing and returns false, so the flag stays set. First
     * sync ends through [finishSync], which never touches the flag.
     */
    @Transaction
    open suspend fun finishBackfill(
        serverId: String,
        userId: String,
        allWalked: Boolean,
    ): Boolean {
        requireActive(serverId, userId)
        if (state(serverId, userId)?.state == AccountStateEntity.STATE_FIRST_RUNNING) return false
        if (allWalked) setBackfillPending(serverId, userId, false)
        return true
    }

    // account_state may be saved whole. sync_meta never is (see setBackfillPending).
    private suspend fun saveIdle(
        serverId: String,
        userId: String,
        nowMs: Long,
    ) = saveState(AccountStateEntity(serverId, userId, AccountStateEntity.STATE_IDLE, null, null, nowMs))

    /** The snapshot's items with their genres, tags and studios, all from one moment. */
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

    /** Every item of the account, with its genres, tags and studios. */
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
        val studios = ids.chunked(MAX_VARIABLES).flatMap { studiosFor(serverId, userId, it) }.groupBy { it.itemId }
        return items.map {
            ItemRows(it, genres[it.itemId].orEmpty(), tags[it.itemId].orEmpty(), studios[it.itemId].orEmpty())
        }
    }

    companion object {
        const val GENERATION_SHIFT = 10

        /**
         * Where a new `sync_meta` row starts counting, read from [clock] when the row is made: its milliseconds
         * shifted left by [GENERATION_SHIFT] (see [generationSeed]). The repository and the 7 to 8 migration both seed
         * this way.
         */
        fun seedFrom(clock: Clock): () -> Long = { clock.now().toEpochMilliseconds() shl GENERATION_SHIFT }

        /** SQLite on API 25 allows 999 variables in a statement; lookups by a list of IDs stay well under it. */
        const val MAX_VARIABLES = 400
    }
}
