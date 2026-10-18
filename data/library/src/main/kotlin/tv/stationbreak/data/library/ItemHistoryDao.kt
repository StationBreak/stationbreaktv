// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

// The columns the as-of read takes from an item row or a version row, in the order of HeldRow.
private const val HELD_COLUMNS =
    "itemId, libraryId, kind, seriesId, seasonId, seasonNumber, episodeNumber, runtimeMs, createdAtMs, premiereDate, " +
        "productionYear, officialRating, deletedAtMs, seenFromMs"

// The same from an item row, which keeps its genres and tags in side tables: the two list columns are null there.
private const val HELD_COLUMNS_OF_ITEM = "$HELD_COLUMNS, NULL AS genres, NULL AS tags"

// The same from a version row, whose genres and tags are codec columns.
private const val HELD_COLUMNS_OF_VERSION = "$HELD_COLUMNS, genres, tags"

// The same, qualified by the alias v, with the names the mapping expects.
private const val HELD_COLUMNS_OF_V =
    "v.itemId AS itemId, v.libraryId AS libraryId, v.kind AS kind, v.seriesId AS seriesId, " +
        "v.seasonId AS seasonId, v.seasonNumber AS seasonNumber, v.episodeNumber AS episodeNumber, " +
        "v.runtimeMs AS runtimeMs, v.createdAtMs AS createdAtMs, v.premiereDate AS premiereDate, " +
        "v.productionYear AS productionYear, v.officialRating AS officialRating, v.deletedAtMs AS deletedAtMs, " +
        "v.seenFromMs AS seenFromMs, v.genres AS genres, v.tags AS tags"

/**
 * One row of the as-of read as Room maps it, from an item row or a version row. [genres] and [tags] are the codec
 * columns of a version, and null for an item row, whose lists are in the side tables. [ItemHistoryDao.heldAsOf] turns
 * it into a [HeldRow].
 */
@Suppress("LongParameterList") // One property per column of the as-of read, as Room maps them.
internal class HeldRaw(
    val itemId: String,
    val libraryId: String,
    val kind: String,
    val seriesId: String?,
    val seasonId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val runtimeMs: Long?,
    val createdAtMs: Long?,
    val premiereDate: String?,
    val productionYear: Int?,
    val officialRating: String?,
    val deletedAtMs: Long?,
    val seenFromMs: Long,
    val genres: String?,
    val tags: String?,
)

// The genres and tags of the current rows of one chunk, by item.
private class SideLists {
    val genres = HashMap<String, MutableList<String>>()
    val tags = HashMap<String, MutableList<String>>()
}

// One String per distinct value within a read: a catalogue repeats a few hundred genres and tags over 15,000 items.
internal class Interner {
    private val strings = HashMap<String, String>()

    fun of(value: String): String {
        val known = strings[value]
        if (known != null) return known
        strings[value] = value
        return value
    }

    fun all(values: List<String>): List<String> {
        if (values.isEmpty()) return emptyList()
        val shared = ArrayList<String>(values.size)
        for (value in values) shared.add(of(value))
        return shared
    }
}

/**
 * What a page does to its items, worked out by [ItemHistoryDao.planPage] and written by the caller: [toInsert] are new,
 * [toUpdate] differ from the row held, [versions] are the old rows kept for the updates that changed a field the
 * scheduler reads, and [unchanged] were left alone.
 */
internal class PagePlan(
    val toInsert: List<ItemEntity>,
    val toUpdate: List<ItemEntity>,
    val versions: List<ItemVersionEntity>,
    val unchanged: Int,
)

/** What [ItemHistoryDao.planRevival] works out: the [versions] to keep, and the [revived] rows to write back. */
internal class RevivalPlan(
    val versions: List<ItemVersionEntity>,
    val revived: List<ItemEntity>,
)

/**
 * The history of the saved items (spec 09): the `item_version` rows, the rule for when a change makes one, the stamp
 * that keeps their intervals increasing, and the as-of read that returns the library as this TV held it before a
 * cut-off. Current item state is [LibraryDao]'s. This DAO never calls another one: a transaction that needs both is a
 * method of [CacheTransactionDao], which reads and writes through each in turn.
 */
@Dao
@Suppress("TooManyFunctions") // The SQL of the history and its as-of read, plus the two plans.
internal abstract class ItemHistoryDao {
    @Insert
    abstract suspend fun insertVersions(rows: List<ItemVersionEntity>)

    @Query(
        "DELETE FROM item_version WHERE serverId = :serverId AND userId = :userId AND supersededAtMs < :cutoffMs",
    )
    abstract suspend fun deleteVersionsSupersededBefore(
        serverId: String,
        userId: String,
        cutoffMs: Long,
    ): Int

    /**
     * Works out what writing [items] over the rows the database holds ([existing], with their [oldGenres] and
     * [oldTags]) does. The caller reads the old genres and tags inside its transaction and before they are deleted, so
     * a version can hold them and a genre-only or tag-only edit is seen as a change (Vimes H3). A row that clears a
     * tombstone is a change too, even when no field differs (Vimes H2). This reads and writes nothing.
     */
    fun planPage(
        items: Map<String, ItemRows>,
        existing: Map<String, ItemEntity>,
        oldGenres: Map<String, List<String>>,
        oldTags: Map<String, List<String>>,
        nowMs: Long,
    ): PagePlan {
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
        return PagePlan(toInsert, toUpdate, versions, unchanged)
    }

    /**
     * Works out the revival of the tombstoned [rows] at [nowMs]. Each is copied as a version first, ending at the new
     * stamp (Vimes H2), so a day built from before the revival still sees the deletion. This reads and writes nothing.
     */
    fun planRevival(
        rows: List<ItemEntity>,
        genres: Map<String, List<String>>,
        tags: Map<String, List<String>>,
        nowMs: Long,
    ): RevivalPlan {
        val stamps = rows.associate { it.itemId to nextStamp(it, nowMs) }
        val versions =
            rows.map {
                val id = it.itemId
                it.toVersion(stamps.getValue(id), genres[id].orEmpty(), tags[id].orEmpty())
            }
        return RevivalPlan(versions, rows.map { it.copy(deletedAtMs = null, seenFromMs = stamps.getValue(it.itemId)) })
    }

    // The as-of read (spec 09, Unit 2). All three select the same columns, from the item row or from a version, so one
    // mapping serves both. They page by item-ID range (no IN lists), so a chunk is bounded by LIMIT, not by the 999
    // variables of API 25's SQLite.

    @Query(
        "SELECT $HELD_COLUMNS_OF_ITEM FROM item " +
            "WHERE serverId = :serverId AND userId = :userId AND itemId > :after ORDER BY itemId LIMIT :limit",
    )
    protected abstract suspend fun currentChunk(
        serverId: String,
        userId: String,
        after: String,
        limit: Int,
    ): List<HeldRaw>

    // Case (b): the one version that was current just before the cut-off. The intervals are contiguous, so there is at
    // most one per item.
    @Query(
        "SELECT $HELD_COLUMNS_OF_VERSION FROM item_version " +
            "WHERE serverId = :serverId AND userId = :userId AND itemId > :after AND itemId <= :last " +
            "AND seenFromMs < :cutoffMs AND supersededAtMs >= :cutoffMs",
    )
    protected abstract suspend fun coveringVersions(
        serverId: String,
        userId: String,
        after: String,
        last: String,
        cutoffMs: Long,
    ): List<HeldRaw>

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
    ): List<HeldRaw>

    // Genres and tags of the current rows of one chunk, by item-ID range, in the server's order.
    @Query(
        "SELECT * FROM item_genre WHERE serverId = :serverId AND userId = :userId AND itemId > :after " +
            "AND itemId <= :last ORDER BY itemId, position",
    )
    protected abstract suspend fun genresInRange(
        serverId: String,
        userId: String,
        after: String,
        last: String,
    ): List<GenreEntity>

    @Query(
        "SELECT * FROM item_tag WHERE serverId = :serverId AND userId = :userId AND itemId > :after " +
            "AND itemId <= :last ORDER BY itemId, position",
    )
    protected abstract suspend fun tagsInRange(
        serverId: String,
        userId: String,
        after: String,
        last: String,
    ): List<TagEntity>

    /**
     * The version of every item of the account that this TV held before [cutoffMs], read [chunkSize] items at a time
     * in item-ID order inside one read transaction (Vimes H4, M2), and returned in item-ID order. Selection runs over
     * every item first; only then are the rows of [libraries] kept, less those whose held version was deleted before
     * the cut-off (`deletedAt < cutoffMs`). The validity checks are the caller's.
     *
     * [strings] shares one `String` per distinct genre, tag, rating, library ID and season ID with whoever else reads
     * in the same transaction (the rule snapshot does); a caller that has no one to share with passes none.
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
    @Suppress("LongParameterList") // The account, the cut-off, the chunk, the libraries and the strings to share.
    open suspend fun heldAsOf(
        serverId: String,
        userId: String,
        cutoffMs: Long,
        chunkSize: Int,
        libraries: Set<String>,
        strings: Interner = Interner(),
    ): List<HeldRow> {
        val held = ArrayList<HeldRow>()
        var after = ""
        while (true) {
            val current = currentChunk(serverId, userId, after, chunkSize)
            if (current.isEmpty()) return held
            val last = current.last().itemId
            val chosen = chosenInChunk(serverId, userId, after, current, cutoffMs, libraries)
            // The side tables are read for the chunk only when a chosen row is a current row (its lists are not codec).
            val needsSide = chosen.any { it.genres == null }
            val side = if (needsSide) readSide(serverId, userId, after, last, strings) else SideLists()
            for (raw in chosen) held += raw.toHeld(side, strings)
            if (current.size < chunkSize) return held
            after = last
        }
    }

    // The held version of each item of a chunk, kept when it belongs to a wanted library and was not deleted before the
    // cut-off. Cases (b) and (c) are looked up only when some item of the chunk needs them.
    @Suppress("LongParameterList") // The account, the chunk, the cut-off and the libraries: one read.
    private suspend fun chosenInChunk(
        serverId: String,
        userId: String,
        after: String,
        current: List<HeldRaw>,
        cutoffMs: Long,
        libraries: Set<String>,
    ): List<HeldRaw> {
        val last = current.last().itemId
        val covering = mutableMapOf<String, HeldRaw>()
        val oldest = mutableMapOf<String, HeldRaw>()
        if (current.any { it.seenFromMs >= cutoffMs }) {
            coveringVersions(serverId, userId, after, last, cutoffMs).associateByTo(covering) { it.itemId }
            oldestVersions(serverId, userId, after, last, cutoffMs).associateByTo(oldest) { it.itemId }
        }
        val chosen = ArrayList<HeldRaw>()
        for (row in current) {
            val version = if (row.seenFromMs < cutoffMs) row else covering[row.itemId] ?: oldest[row.itemId] ?: row
            val deletedAt = version.deletedAtMs
            if (version.libraryId in libraries && (deletedAt == null || deletedAt >= cutoffMs)) chosen += version
        }
        return chosen
    }

    // The genres and tags of the current rows in an ID range, by item, in the server's order.
    private suspend fun readSide(
        serverId: String,
        userId: String,
        after: String,
        last: String,
        strings: Interner,
    ): SideLists {
        val side = SideLists()
        for (row in genresInRange(serverId, userId, after, last)) {
            side.genres.getOrPut(row.itemId) { ArrayList() }.add(strings.of(row.name))
        }
        for (row in tagsInRange(serverId, userId, after, last)) {
            side.tags.getOrPut(row.itemId) { ArrayList() }.add(strings.of(row.name))
        }
        return side
    }

    private fun HeldRaw.toHeld(
        side: SideLists,
        strings: Interner,
    ) = HeldRow(
        itemId = itemId,
        libraryId = strings.of(libraryId),
        kind = kind,
        seriesId = seriesId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        runtimeMs = runtimeMs,
        createdAtMs = createdAtMs,
        premiereDate = premiereDate,
        productionYear = productionYear,
        deletedAtMs = deletedAtMs,
        seenFromMs = seenFromMs,
        officialRating = officialRating?.let(strings::of),
        genres = if (genres != null) strings.all(VersionCodec.decode(genres)) else side.genres[itemId].orEmpty(),
        tags = if (tags != null) strings.all(VersionCodec.decode(tags)) else side.tags[itemId].orEmpty(),
        seasonId = seasonId?.let(strings::of),
    )

    // Counts and reads, for the tests and diagnostics.

    @Query("SELECT COUNT(*) FROM item_version")
    abstract suspend fun countVersions(): Int

    @Query("SELECT * FROM item_version WHERE serverId = :serverId AND userId = :userId ORDER BY itemId, seenFromMs")
    abstract suspend fun allVersions(
        serverId: String,
        userId: String,
    ): List<ItemVersionEntity>
}
