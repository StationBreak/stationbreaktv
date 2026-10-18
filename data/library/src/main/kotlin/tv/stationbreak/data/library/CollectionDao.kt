// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/** What one [CacheTransactionDao.writeCollection] did. [stamp] is null when the read changed nothing and took none. */
internal data class CollectionWrite(
    val stamp: Long?,
    val inserted: Boolean,
    val revived: Boolean,
    val added: Int,
    val removed: Int,
)

/** One member of a collection as the as-of read holds it: its ID and its kind column. */
internal class HeldMember(
    val memberId: String,
    val kind: String,
)

/** One collection as this TV held it before a cut-off, with its members in member-ID order. */
internal class HeldCollection(
    val collectionId: String,
    val members: List<HeldMember>,
)

/** One row of the member part of the as-of read, as Room maps it. */
internal class MemberRaw(
    val collectionId: String,
    val memberId: String,
    val memberKind: String,
)

/**
 * What one collection write does, worked out by [CollectionDao.planWrite] and written by the caller: the row to
 * [insert] (a new collection) or [update] (a changed one), the [version] that keeps the lifecycle it ends, the members
 * to [add] and the IDs of those to end ([removed]), all at one [stamp]. A plan with no stamp writes nothing, or at most
 * a new name.
 */
@Suppress("LongParameterList") // One property per part of the write.
internal class CollectionPlan(
    val insert: CollectionEntity?,
    val update: CollectionEntity?,
    val version: CollectionVersionEntity?,
    val add: List<CollectionMemberEntity>,
    val removed: List<String>,
    val stamp: Long?,
    val revived: Boolean,
)

/**
 * The SQL of the cached collections and their membership history (spec 10, Unit 2): the `collection` row (the current
 * lifecycle), its `collection_version` rows, its `collection_member` intervals, the diff that makes them, and the as-of
 * read. A collection has no separate current state to keep apart, so one DAO holds all three tables. It never calls
 * another DAO; a transaction that needs it is a method of [CacheTransactionDao].
 */
@Dao
@Suppress("TooManyFunctions") // The SQL surface of one entity and its history: one method per statement.
internal abstract class CollectionDao {
    @Query("SELECT * FROM collection WHERE serverId = :serverId AND userId = :userId AND collectionId = :collectionId")
    abstract suspend fun byId(
        serverId: String,
        userId: String,
        collectionId: String,
    ): CollectionEntity?

    @Insert
    abstract suspend fun insert(row: CollectionEntity)

    @Update
    abstract suspend fun update(row: CollectionEntity)

    @Insert
    abstract suspend fun insertVersion(row: CollectionVersionEntity)

    @Insert
    abstract suspend fun insertMembers(rows: List<CollectionMemberEntity>)

    // The members that are current: the rows with no end, which the diff compares the new read with.
    @Query(
        "SELECT * FROM collection_member WHERE serverId = :serverId AND userId = :userId " +
            "AND collectionId = :collectionId AND supersededAtMs IS NULL",
    )
    abstract suspend fun currentMembers(
        serverId: String,
        userId: String,
        collectionId: String,
    ): List<CollectionMemberEntity>

    // Ends the current rows of [memberIds] in one collection at the write's stamp.
    @Query(
        "UPDATE collection_member SET supersededAtMs = :stamp WHERE serverId = :serverId AND userId = :userId " +
            "AND collectionId = :collectionId AND memberId IN (:memberIds) AND supersededAtMs IS NULL",
    )
    abstract suspend fun endMembers(
        serverId: String,
        userId: String,
        collectionId: String,
        memberIds: List<String>,
        stamp: Long,
    )

    /**
     * Ends the current rows of [memberIds] in EVERY collection of the account at [stamp]: the member was deleted from
     * the server, which does not bump its collections (spec 10, ADR-0015). A row first seen at or after [stamp] (a
     * clock that went back) ends one millisecond after it began, so no interval is ever empty. A later revival of the
     * member re-adds nothing: the next collection read decides.
     */
    @Query(
        "UPDATE collection_member SET supersededAtMs = " +
            "CASE WHEN seenFromMs >= :stamp THEN seenFromMs + 1 ELSE :stamp END " +
            "WHERE serverId = :serverId AND userId = :userId AND memberId IN (:memberIds) AND supersededAtMs IS NULL",
    )
    abstract suspend fun supersedeMember(
        serverId: String,
        userId: String,
        memberIds: List<String>,
        stamp: Long,
    )

    // Marks collections deleted. An existing tombstone keeps its time, and no version is added.
    @Query(
        "UPDATE collection SET deletedAtMs = :atMs WHERE serverId = :serverId AND userId = :userId " +
            "AND collectionId IN (:ids) AND deletedAtMs IS NULL",
    )
    abstract suspend fun tombstone(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    )

    // The purge: plain DELETEs scoped to the account and driven by the purge indexes. As for items and series, a
    // tombstone also needs seenFromMs < cutoff, because the cascade would take versions and members superseded inside
    // the retention. A current member row (null supersededAtMs) never matches the member DELETE.

    @Query(
        "DELETE FROM collection_version WHERE serverId = :serverId AND userId = :userId AND supersededAtMs < :cutoffMs",
    )
    abstract suspend fun deleteVersionsSupersededBefore(
        serverId: String,
        userId: String,
        cutoffMs: Long,
    ): Int

    @Query(
        "DELETE FROM collection_member WHERE serverId = :serverId AND userId = :userId AND supersededAtMs < :cutoffMs",
    )
    abstract suspend fun deleteMembersSupersededBefore(
        serverId: String,
        userId: String,
        cutoffMs: Long,
    ): Int

    @Query(
        "DELETE FROM collection WHERE serverId = :serverId AND userId = :userId " +
            "AND deletedAtMs IS NOT NULL AND deletedAtMs < :cutoffMs AND seenFromMs < :cutoffMs",
    )
    abstract suspend fun deleteTombstonesBefore(
        serverId: String,
        userId: String,
        cutoffMs: Long,
    ): Int

    // Sign-out and a change of account: collections belong to no library, so the library cascade does not reach them.
    // Their versions and members follow by cascade.

    @Query("DELETE FROM collection WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun deleteCollections(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM collection WHERE NOT (serverId = :serverId AND userId = :userId)")
    abstract suspend fun deleteOtherCollections(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM collection_sync WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun deleteSyncRow(
        serverId: String,
        userId: String,
    )

    @Query("DELETE FROM collection_sync WHERE NOT (serverId = :serverId AND userId = :userId)")
    abstract suspend fun deleteOtherSyncRows(
        serverId: String,
        userId: String,
    )

    // The as-of read. It pages by collection-ID range, as the item read does, and reads members by the IDs it chose.

    @Query(
        "SELECT * FROM collection WHERE serverId = :serverId AND userId = :userId AND collectionId > :after " +
            "ORDER BY collectionId LIMIT :limit",
    )
    protected abstract suspend fun currentChunk(
        serverId: String,
        userId: String,
        after: String,
        limit: Int,
    ): List<CollectionEntity>

    // Case (b): the one lifecycle that was current just before the cut-off. The intervals are contiguous.
    @Query(
        "SELECT * FROM collection_version WHERE serverId = :serverId AND userId = :userId " +
            "AND collectionId > :after AND collectionId <= :last " +
            "AND seenFromMs < :cutoffMs AND supersededAtMs >= :cutoffMs",
    )
    protected abstract suspend fun coveringVersions(
        serverId: String,
        userId: String,
        after: String,
        last: String,
        cutoffMs: Long,
    ): List<CollectionVersionEntity>

    // Case (c): the oldest lifecycle, found by probing the collection's firstSeenMs, for one nothing older holds.
    @Query(
        "SELECT v.* FROM collection_version v " +
            "JOIN collection c ON c.serverId = v.serverId AND c.userId = v.userId " +
            "AND c.collectionId = v.collectionId AND c.firstSeenMs = v.seenFromMs " +
            "WHERE v.serverId = :serverId AND v.userId = :userId " +
            "AND v.collectionId > :after AND v.collectionId <= :last AND v.seenFromMs >= :cutoffMs",
    )
    protected abstract suspend fun oldestVersions(
        serverId: String,
        userId: String,
        after: String,
        last: String,
        cutoffMs: Long,
    ): List<CollectionVersionEntity>

    // The members held before [cutoffMs]: began before it, and ended at or after it, or not at all.
    @Query(
        "SELECT collectionId, memberId, memberKind FROM collection_member " +
            "WHERE serverId = :serverId AND userId = :userId AND collectionId IN (:ids) " +
            "AND seenFromMs < :cutoffMs AND (supersededAtMs IS NULL OR supersededAtMs >= :cutoffMs) " +
            "ORDER BY collectionId, memberId",
    )
    protected abstract suspend fun membersAt(
        serverId: String,
        userId: String,
        ids: List<String>,
        cutoffMs: Long,
    ): List<MemberRaw>

    // The same for collections first seen after the cut-off: each at its own cut-off `firstSeenMs + 1`, which is the
    // oldest set this TV held. `seenFromMs < firstSeenMs + 1` is `seenFromMs <= firstSeenMs`, and likewise for the end.
    @Query(
        "SELECT m.collectionId AS collectionId, m.memberId AS memberId, m.memberKind AS memberKind " +
            "FROM collection_member m " +
            "JOIN collection c ON c.serverId = m.serverId AND c.userId = m.userId " +
            "AND c.collectionId = m.collectionId " +
            "WHERE m.serverId = :serverId AND m.userId = :userId AND m.collectionId IN (:ids) " +
            "AND m.seenFromMs <= c.firstSeenMs AND (m.supersededAtMs IS NULL OR m.supersededAtMs > c.firstSeenMs) " +
            "ORDER BY m.collectionId, m.memberId",
    )
    protected abstract suspend fun oldestMembers(
        serverId: String,
        userId: String,
        ids: List<String>,
    ): List<MemberRaw>

    /**
     * The collections of the account that this TV held before [cutoffMs], each with its members, read [chunkSize]
     * collections at a time in collection-ID order inside one read transaction, and returned in that order. The
     * lifecycle is chosen as for an item (spec 09): (a) the current row if it was first seen before the cut-off; else
     * (b) the lifecycle that was current just before it; else (c) the oldest one, or the current row if there is none,
     * and then the members are those of the cut-off `firstSeenMs + 1`, the oldest set this TV held, which is never
     * empty for a collection that had members (Vimes H2). A collection is dropped when the chosen lifecycle was deleted
     * before the cut-off.
     *
     * A member is held when `seenFromMs < c` and `supersededAtMs` is null or at least `c`. For a cut-off older than
     * the retention, rows the purge has removed can make that a mixed set (Vimes L6): the safe range is the 72 h
     * contract of the snapshot, as for items.
     *
     * It returns a list and takes no callback, for the reason given on [ItemHistoryDao.heldAsOf].
     */
    @Transaction
    open suspend fun heldAsOf(
        serverId: String,
        userId: String,
        cutoffMs: Long,
        chunkSize: Int,
    ): List<HeldCollection> {
        require(chunkSize in 1..MAX_CHUNK) { "chunkSize must be 1 to $MAX_CHUNK" }
        val held = ArrayList<HeldCollection>()
        var after = ""
        while (true) {
            val current = currentChunk(serverId, userId, after, chunkSize)
            if (current.isEmpty()) return held
            val last = current.last().collectionId
            held += heldInChunk(serverId, userId, after, current, cutoffMs)
            if (current.size < chunkSize) return held
            after = last
        }
    }

    // The collections of one chunk that are held at the cut-off, each with its members.
    private suspend fun heldInChunk(
        serverId: String,
        userId: String,
        after: String,
        current: List<CollectionEntity>,
        cutoffMs: Long,
    ): List<HeldCollection> {
        val last = current.last().collectionId
        val covering = HashMap<String, CollectionVersionEntity>()
        val oldest = HashMap<String, CollectionVersionEntity>()
        if (current.any { it.seenFromMs >= cutoffMs }) {
            coveringVersions(serverId, userId, after, last, cutoffMs).associateByTo(covering) { it.collectionId }
            oldestVersions(serverId, userId, after, last, cutoffMs).associateByTo(oldest) { it.collectionId }
        }
        val atCutoff = ArrayList<String>()
        val atOldest = ArrayList<String>()
        for (row in current) {
            val id = row.collectionId
            val version = covering[id]
            val deletedAt = lifecycleDeletedAt(row, version, oldest[id], cutoffMs)
            when {
                deletedAt != null && deletedAt < cutoffMs -> Unit
                row.seenFromMs >= cutoffMs && version == null -> atOldest += id
                else -> atCutoff += id
            }
        }
        val members = HashMap<String, ArrayList<HeldMember>>()
        if (atCutoff.isNotEmpty()) addMembers(members, membersAt(serverId, userId, atCutoff, cutoffMs))
        if (atOldest.isNotEmpty()) addMembers(members, oldestMembers(serverId, userId, atOldest))
        val result = ArrayList<HeldCollection>()
        for (row in current) {
            val id = row.collectionId
            if (id in atCutoff || id in atOldest) result += HeldCollection(id, members[id].orEmpty())
        }
        return result
    }

    // When the lifecycle held at the cut-off was deleted: the current row's if it began before the cut-off (a), else
    // the covering version's (b), else the oldest version's, or the current row's if there is none (c).
    private fun lifecycleDeletedAt(
        row: CollectionEntity,
        covering: CollectionVersionEntity?,
        oldest: CollectionVersionEntity?,
        cutoffMs: Long,
    ): Long? =
        when {
            row.seenFromMs < cutoffMs -> row.deletedAtMs
            covering != null -> covering.deletedAtMs
            oldest != null -> oldest.deletedAtMs
            else -> row.deletedAtMs
        }

    private fun addMembers(
        into: HashMap<String, ArrayList<HeldMember>>,
        rows: List<MemberRaw>,
    ) {
        for (row in rows) {
            val kind = MemberKinds.shared(row.memberKind)
            into.getOrPut(row.collectionId) { ArrayList() }.add(HeldMember(row.memberId, kind))
        }
    }

    /**
     * Works out what writing a complete member list ([incoming], read as one) does to the collection [collectionId],
     * given the row the database holds ([existing], null for a new collection) and its [current] members. Members are
     * diffed by ID, and a kind change is a removal plus an addition. A member listed twice counts once, the first
     * listing winning. Any change, or a revival of a tombstoned collection, takes one stamp
     * `max(nowMs, existing.seenFromMs + 1)` for everything the write does; a new collection takes [nowMs] for the row
     * and its first members. A read that changes nothing takes no stamp (a new name alone is stored, unstamped). This
     * reads and writes nothing.
     */
    @Suppress("LongParameterList") // The account, the collection, what was read, what is stored and the time.
    fun planWrite(
        serverId: String,
        userId: String,
        collectionId: String,
        name: String,
        incoming: List<CollectionMemberEntity>,
        existing: CollectionEntity?,
        current: List<CollectionMemberEntity>,
        nowMs: Long,
    ): CollectionPlan {
        val wanted = LinkedHashMap<String, CollectionMemberEntity>()
        for (member in incoming) wanted.putIfAbsent(member.memberId, member)
        if (existing == null) {
            val row = CollectionEntity(serverId, userId, collectionId, name, null, nowMs, nowMs)
            val first = wanted.values.map { it.copy(seenFromMs = nowMs, supersededAtMs = null) }
            return CollectionPlan(row, null, null, first, emptyList(), nowMs, revived = false)
        }
        val held = current.associateBy { it.memberId }
        val add = wanted.values.filter { held[it.memberId]?.memberKind != it.memberKind }
        val removed = held.values.filter { wanted[it.memberId]?.memberKind != it.memberKind }.map { it.memberId }
        val revived = existing.deletedAtMs != null
        return if (add.isEmpty() && removed.isEmpty() && !revived) {
            // Nothing changed: no stamp, no version. A new name is stored as it is.
            val renamed = existing.takeIf { it.name != name }?.copy(name = name)
            CollectionPlan(null, renamed, null, emptyList(), emptyList(), null, revived = false)
        } else {
            val stamp = nextStamp(existing.seenFromMs, nowMs)
            val from = existing.seenFromMs
            val ended = CollectionVersionEntity(serverId, userId, collectionId, from, stamp, existing.deletedAtMs)
            val began = add.map { it.copy(seenFromMs = stamp, supersededAtMs = null) }
            val row = existing.copy(name = name, deletedAtMs = null, seenFromMs = stamp)
            CollectionPlan(null, row, ended, began, removed, stamp, revived)
        }
    }

    // The IDs the collection pass compares the server's list with.

    @Query(
        "SELECT collectionId FROM collection WHERE serverId = :serverId AND userId = :userId " +
            "AND deletedAtMs IS NULL ORDER BY collectionId",
    )
    abstract suspend fun liveIds(
        serverId: String,
        userId: String,
    ): List<String>

    @Query(
        "SELECT collectionId FROM collection WHERE serverId = :serverId AND userId = :userId " +
            "AND deletedAtMs IS NOT NULL ORDER BY collectionId",
    )
    abstract suspend fun tombstonedIds(
        serverId: String,
        userId: String,
    ): List<String>

    @Query(
        "SELECT COUNT(*) FROM collection_member WHERE serverId = :serverId AND userId = :userId " +
            "AND collectionId = :collectionId AND supersededAtMs IS NULL",
    )
    abstract suspend fun currentMemberCount(
        serverId: String,
        userId: String,
        collectionId: String,
    ): Int

    // The collection pass's own row (Vimes H4, M2): a row of its own, never a column of account_state.

    @Query("SELECT * FROM collection_sync WHERE serverId = :serverId AND userId = :userId")
    abstract suspend fun syncRow(
        serverId: String,
        userId: String,
    ): CollectionSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveSyncRow(row: CollectionSyncEntity)

    // Reads, for the tests and diagnostics.

    @Query("SELECT COUNT(*) FROM collection")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM collection_version")
    abstract suspend fun countVersions(): Int

    @Query("SELECT COUNT(*) FROM collection_member")
    abstract suspend fun countMembers(): Int

    @Query("SELECT COUNT(*) FROM collection_sync")
    abstract suspend fun countSyncRows(): Int
    // Reads, for the tests and diagnostics.

    @Query("SELECT * FROM collection WHERE serverId = :serverId AND userId = :userId ORDER BY collectionId")
    abstract suspend fun all(
        serverId: String,
        userId: String,
    ): List<CollectionEntity>

    @Query(
        "SELECT * FROM collection_version WHERE serverId = :serverId AND userId = :userId " +
            "ORDER BY collectionId, seenFromMs",
    )
    abstract suspend fun allVersions(
        serverId: String,
        userId: String,
    ): List<CollectionVersionEntity>

    @Query(
        "SELECT * FROM collection_member WHERE serverId = :serverId AND userId = :userId " +
            "ORDER BY collectionId, memberId, seenFromMs",
    )
    abstract suspend fun allMembers(
        serverId: String,
        userId: String,
    ): List<CollectionMemberEntity>

    companion object {
        /** The most collections [heldAsOf] reads at once: their IDs go in one `IN` list, under SQLite 3.9's 999. */
        const val MAX_CHUNK = LibraryDao.MAX_VARIABLES - 3
    }
}
