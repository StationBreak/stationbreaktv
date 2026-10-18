// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
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
    abstract suspend fun tombstone(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    )

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
     * the rating, the ordered genres, the ordered tags and the tombstone, and nothing else: not the name and not the
     * library (Vimes L2). A row that clears a tombstone is a change by the last of those. The stamp of a changed row is
     * `max(nowMs, old.seenFromMs + 1)`. This reads and writes nothing.
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

                // Only the name or the library differs: stored, but it is no new version.
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
}
