// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/**
 * What a page does to its series, worked out by [SeriesDao.planPage] and written by the caller (see [PagePlan]).
 */
internal class SeriesPlan(
    val toInsert: List<SeriesEntity>,
    val toUpdate: List<SeriesEntity>,
    val versions: List<SeriesVersionEntity>,
    val unchanged: Int,
)

/** What [SeriesDao.planRevival] works out: the [versions] to keep, and the [revived] rows to write back. */
internal class SeriesRevival(
    val versions: List<SeriesVersionEntity>,
    val revived: List<SeriesEntity>,
)

/** One series as this TV held it before a cut-off: [seriesId] as stored, and the fields a rule reads. */
internal class HeldSeries(
    val seriesId: String,
    val officialRating: String?,
    val genres: List<String>,
    val tags: List<String>,
    val studios: List<String>,
    val name: String?,
) {
    // A name is text a person typed: it never reaches a log.
    override fun toString(): String = "HeldSeries(seriesId=$seriesId, name=<name>)"
}

/**
 * The SQL of the cached series and their history (spec 10, Unit 1), with the same rules as [ItemHistoryDao]. A series
 * and its `series_version` rows belong to one DAO because a series has no separate current state to keep apart. It
 * never calls another DAO; a transaction that needs it is a method of [CacheTransactionDao].
 */
@Dao
@Suppress("TooManyFunctions") // The SQL surface of one entity: one method per statement.
internal abstract class SeriesDao {
    @Query("SELECT * FROM series WHERE serverId = :serverId AND userId = :userId AND seriesId IN (:ids)")
    abstract suspend fun byIds(
        serverId: String,
        userId: String,
        ids: List<String>,
    ): List<SeriesEntity>

    // The picker's reads (the builder, spec 22): the packed genre and tag columns of the live series, decoded by the
    // caller (VersionCodec), one row per distinct text.
    @Query("SELECT DISTINCT genres FROM series WHERE serverId = :serverId AND userId = :userId AND deletedAtMs IS NULL")
    abstract suspend fun liveGenreColumns(
        serverId: String,
        userId: String,
    ): List<String>

    @Query("SELECT DISTINCT tags FROM series WHERE serverId = :serverId AND userId = :userId AND deletedAtMs IS NULL")
    abstract suspend fun liveTagColumns(
        serverId: String,
        userId: String,
    ): List<String>

    @Query(
        "SELECT DISTINCT studios FROM series WHERE serverId = :serverId AND userId = :userId " +
            "AND deletedAtMs IS NULL",
    )
    abstract suspend fun liveStudioColumns(
        serverId: String,
        userId: String,
    ): List<String>

    // [pattern] is bound, never joined into the text; see likePattern.
    @Query(
        "SELECT seriesId AS id, name AS name, NULL AS year FROM series WHERE serverId = :serverId " +
            "AND userId = :userId AND deletedAtMs IS NULL AND name LIKE :pattern ESCAPE '\\' " +
            "ORDER BY name, seriesId LIMIT :limit",
    )
    abstract suspend fun searchSeries(
        serverId: String,
        userId: String,
        pattern: String,
        limit: Int,
    ): List<TitleRow>

    // The names of the live series among [ids] (at most LibraryDao.MAX_VARIABLES of them).
    // The re-id match's reads (spec 26): the series among [ids] (at most MAX_VARIABLES - 2) that are tombstones, and
    // the live series of [libraries] (the same bound). The names are text a person typed and never reach a log.
    @Query(
        "SELECT seriesId, name, providerIds FROM series WHERE serverId = :serverId AND userId = :userId " +
            "AND deletedAtMs IS NOT NULL AND deletedAtMs >= :sinceMs AND seriesId IN (:ids)",
    )
    abstract suspend fun goneSeriesSignalRows(
        serverId: String,
        userId: String,
        sinceMs: Long,
        ids: List<String>,
    ): List<SeriesSignalRow>

    @Query(
        "SELECT seriesId, name, providerIds FROM series WHERE serverId = :serverId AND userId = :userId " +
            "AND deletedAtMs IS NULL AND libraryId IN (:libraries)",
    )
    abstract suspend fun liveSeriesSignalRows(
        serverId: String,
        userId: String,
        libraries: List<String>,
    ): List<SeriesSignalRow>

    @Query(
        "SELECT seriesId AS id, name AS name FROM series WHERE serverId = :serverId AND userId = :userId " +
            "AND deletedAtMs IS NULL AND seriesId IN (:ids)",
    )
    abstract suspend fun seriesNames(
        serverId: String,
        userId: String,
        ids: List<String>,
    ): List<NameRow>

    @Insert
    abstract suspend fun insert(rows: List<SeriesEntity>)

    @Update
    abstract suspend fun update(rows: List<SeriesEntity>)

    @Insert
    abstract suspend fun insertVersions(rows: List<SeriesVersionEntity>)

    @Query(
        "SELECT seriesId FROM series WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId " +
            "AND deletedAtMs IS NULL ORDER BY seriesId",
    )
    abstract suspend fun liveIds(
        serverId: String,
        userId: String,
        libraryId: String,
    ): List<String>

    @Query(
        "SELECT seriesId FROM series WHERE serverId = :serverId AND userId = :userId AND libraryId = :libraryId " +
            "AND deletedAtMs IS NOT NULL ORDER BY seriesId",
    )
    abstract suspend fun tombstonedIds(
        serverId: String,
        userId: String,
        libraryId: String,
    ): List<String>

    // Every live series the account has, in any library.
    @Query("SELECT seriesId FROM series WHERE serverId = :serverId AND userId = :userId AND deletedAtMs IS NULL")
    abstract suspend fun allLiveIds(
        serverId: String,
        userId: String,
    ): List<String>

    // Every series the account has, live or tombstoned, in any library: the primary key's own prefix.
    @Query("SELECT seriesId FROM series WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun allIds(
        serverId: String,
        userId: String,
    ): List<String>

    @Query(
        "UPDATE series SET deletedAtMs = :atMs WHERE serverId = :serverId AND userId = :userId " +
            "AND seriesId IN (:ids) AND deletedAtMs IS NULL",
    )
    // The rows it tombstoned: an ID already a tombstone, or not held, is not counted (#480).
    abstract suspend fun tombstone(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    ): Int

    // The purge: plain DELETEs scoped to the account and driven by the two purge indexes. As for items, a tombstone
    // also needs seenFromMs < cutoff, because the cascade would take versions superseded inside the retention.
    @Query(
        "DELETE FROM series_version WHERE serverId = :serverId AND userId = :userId AND supersededAtMs < :cutoffMs",
    )
    abstract suspend fun deleteVersionsSupersededBefore(
        serverId: String,
        userId: String,
        cutoffMs: Long,
    ): Int

    @Query(
        "DELETE FROM series WHERE serverId = :serverId AND userId = :userId " +
            "AND deletedAtMs IS NOT NULL AND deletedAtMs < :cutoffMs AND seenFromMs < :cutoffMs",
    )
    abstract suspend fun deleteTombstonesBefore(
        serverId: String,
        userId: String,
        cutoffMs: Long,
    ): Int

    // The as-of read, by the IDs it is given. It follows the item read's cases (a, b, c), for series.

    // Case (b): the one version that was current just before the cut-off. The intervals are contiguous.
    @Query(
        "SELECT * FROM series_version WHERE serverId = :serverId AND userId = :userId AND seriesId IN (:ids) " +
            "AND seenFromMs < :cutoffMs AND supersededAtMs >= :cutoffMs",
    )
    protected abstract suspend fun coveringVersions(
        serverId: String,
        userId: String,
        ids: List<String>,
        cutoffMs: Long,
    ): List<SeriesVersionEntity>

    // Case (c): the oldest version, found by probing the series' firstSeenMs, for a series nothing older holds.
    @Query(
        "SELECT v.* FROM series_version v " +
            "JOIN series s ON s.serverId = v.serverId AND s.userId = v.userId AND s.seriesId = v.seriesId " +
            "AND s.firstSeenMs = v.seenFromMs " +
            "WHERE v.serverId = :serverId AND v.userId = :userId AND v.seriesId IN (:ids) " +
            "AND v.seenFromMs >= :cutoffMs",
    )
    protected abstract suspend fun oldestVersions(
        serverId: String,
        userId: String,
        ids: List<String>,
        cutoffMs: Long,
    ): List<SeriesVersionEntity>

    /**
     * The series [ids] as this TV held them before [cutoffMs], in the order of [ids], read [MAX_CHUNK] at a time into a
     * list. Per series: (a) the current row if it was first seen before the cut-off; else (b) the version that was
     * current just before it; else (c) the oldest version, or the current row if it has none. A series is left out when
     * it is unknown, or when the version held was deleted before the cut-off (`deletedAt < cutoffMs`): a tombstone is
     * kept until then. Genres, tags and studios share their strings through [strings].
     *
     * It returns a list and takes no callback, for the reason given on [ItemHistoryDao.heldAsOf].
     */
    @Transaction
    open suspend fun heldAsOf(
        serverId: String,
        userId: String,
        cutoffMs: Long,
        ids: List<String>,
        strings: Interner,
    ): List<HeldSeries> {
        val held = ArrayList<HeldSeries>()
        for (chunk in ids.chunked(MAX_CHUNK)) {
            val current = byIds(serverId, userId, chunk).sortedBy { it.seriesId }
            val covering = HashMap<String, SeriesVersionEntity>()
            val oldest = HashMap<String, SeriesVersionEntity>()
            if (current.any { it.seenFromMs >= cutoffMs }) {
                coveringVersions(serverId, userId, chunk, cutoffMs).associateByTo(covering) { it.seriesId }
                oldestVersions(serverId, userId, chunk, cutoffMs).associateByTo(oldest) { it.seriesId }
            }
            for (row in current) {
                val id = row.seriesId
                val version = if (row.seenFromMs < cutoffMs) null else covering[id] ?: oldest[id]
                val deletedAt = if (version != null) version.deletedAtMs else row.deletedAtMs
                if (deletedAt != null && deletedAt < cutoffMs) continue
                held += heldFrom(row, version, strings)
            }
        }
        return held
    }

    private fun heldFrom(
        row: SeriesEntity,
        version: SeriesVersionEntity?,
        strings: Interner,
    ): HeldSeries {
        val rating = if (version != null) version.officialRating else row.officialRating
        val genres = if (version != null) version.genres else row.genres
        val tags = if (version != null) version.tags else row.tags
        val studios = if (version != null) version.studios else row.studios
        return HeldSeries(
            row.seriesId,
            rating?.let(strings::of),
            strings.all(VersionCodec.decode(genres)),
            strings.all(VersionCodec.decode(tags)),
            strings.all(VersionCodec.decode(studios)),
            if (version != null) version.name else row.name,
        )
    }

    // Counts and reads, for the tests and diagnostics.

    @Query("SELECT COUNT(*) FROM series")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM series_version")
    abstract suspend fun countVersions(): Int

    @Query("SELECT * FROM series WHERE serverId = :serverId AND userId = :userId ORDER BY seriesId")
    abstract suspend fun all(
        serverId: String,
        userId: String,
    ): List<SeriesEntity>

    @Query("SELECT * FROM series_version WHERE serverId = :serverId AND userId = :userId ORDER BY seriesId, seenFromMs")
    abstract suspend fun allVersions(
        serverId: String,
        userId: String,
    ): List<SeriesVersionEntity>

    /**
     * Works out what writing [incoming] over the rows the database holds ([existing]) does. The change test compares
     * the rating, the ordered genres, tags and studios, the tombstone and the name (spec 23, D8), and nothing else: not
     * the library (Vimes L2). A series listed in two ticked libraries keeps one row whose library flips with each
     * write, by an unversioned update (Vimes L-1; items behave so too). A row that clears a tombstone is a change by
     * the last of those. The stamp of a changed row is `max(nowMs, old.seenFromMs + 1)`. This reads and writes nothing.
     */
    fun planPage(
        incoming: Map<String, SeriesEntity>,
        existing: Map<String, SeriesEntity>,
        nowMs: Long,
    ): SeriesPlan {
        val toInsert = mutableListOf<SeriesEntity>()
        val toUpdate = mutableListOf<SeriesEntity>()
        val versions = mutableListOf<SeriesVersionEntity>()
        var unchanged = 0
        for ((id, row) in incoming) {
            val old = existing[id]
            if (old == null) {
                toInsert += row.copy(seenFromMs = nowMs, firstSeenMs = nowMs)
                continue
            }
            val kept = row.copy(seenFromMs = old.seenFromMs, firstSeenMs = old.firstSeenMs)
            when {
                !old.sameVersionedFields(row) -> {
                    val stamp = nextStamp(old.seenFromMs, nowMs)
                    versions += old.toVersion(stamp)
                    toUpdate += row.copy(seenFromMs = stamp, firstSeenMs = old.firstSeenMs)
                }

                // Only the library differs: stored, but it is no new version.
                old != kept -> {
                    toUpdate += kept
                }

                else -> {
                    unchanged++
                }
            }
        }
        return SeriesPlan(toInsert, toUpdate, versions, unchanged)
    }

    /**
     * Works out the revival of the tombstoned [rows] at [nowMs]: each is copied as a version first, ending at the new
     * stamp, so a day built from before the revival still sees the deletion. This reads and writes nothing.
     */
    fun planRevival(
        rows: List<SeriesEntity>,
        nowMs: Long,
    ): SeriesRevival {
        val stamps = rows.associate { it.seriesId to nextStamp(it.seenFromMs, nowMs) }
        val versions = rows.map { it.toVersion(stamps.getValue(it.seriesId)) }
        val revived = rows.map { it.copy(deletedAtMs = null, seenFromMs = stamps.getValue(it.seriesId)) }
        return SeriesRevival(versions, revived)
    }

    companion object {
        /** The most series [heldAsOf] reads at once: their IDs go in one `IN` list, under SQLite 3.9's 999. */
        const val MAX_CHUNK = LibraryDao.MAX_VARIABLES - 3
    }
}

/** What the re-id match reads of one stored series (spec 26); the name and the ids are never logged. */
internal class SeriesSignalRow(
    val seriesId: String,
    val name: String,
    val providerIds: String,
) {
    override fun toString(): String = "SeriesSignalRow(seriesId=$seriesId)"
}
