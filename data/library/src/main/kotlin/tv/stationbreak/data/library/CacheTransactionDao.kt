// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Transaction

/**
 * The transactions that span more than one DAO (spec 10, Unit 1). Each is one `@Transaction` method here that reads
 * and writes through the entity DAOs ([LibraryDao], [ItemHistoryDao]) in turn; the entity DAOs never call each other.
 * It is a DAO method, not a `withTransaction` block, so it works whether Room runs over the platform's
 * SQLiteOpenHelper (the app) or over a SQLiteDriver (the JVM tests). It commits whole or rolls back whole.
 *
 * A method collects plain values into lists and loops over them. It passes no lambda into a loop: on API 25 (ART 7.1)
 * that deoptimised the JIT-compiled loop and aborted the process (see [ItemHistoryDao.heldAsOf]).
 */
@Dao
internal abstract class CacheTransactionDao(
    private val db: LibraryDatabase,
) {
    private val library get() = db.dao()
    private val history get() = db.itemHistoryDao()

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
        library.requireActive(server, user)
        val existing =
            items.keys
                .chunked(LibraryDao.MAX_VARIABLES)
                .flatMap { library.itemsByIds(server, user, it) }
                .associateBy { it.itemId }
        // The old genres and tags are read here, inside the transaction and before they are deleted below.
        val oldGenres = library.genreNames(server, user, existing.keys)
        val oldTags = library.tagNames(server, user, existing.keys)
        val plan = history.planPage(items, existing, oldGenres, oldTags, nowMs)
        library.insertItems(plan.toInsert)
        history.insertVersions(plan.versions)
        library.updateItems(plan.toUpdate)
        val changedIds = (plan.toInsert + plan.toUpdate).map { it.itemId }
        changedIds.chunked(LibraryDao.MAX_VARIABLES).forEach {
            library.deleteGenres(server, user, it)
            library.deleteTags(server, user, it)
        }
        library.insertGenres(changedIds.flatMap { items.getValue(it).genres })
        library.insertTags(changedIds.flatMap { items.getValue(it).tags })
        betweenItemsAndCheckpoint?.invoke()
        checkpoint?.let { library.saveCheckpoint(it) }
        return PageWrite(plan.toInsert.size, plan.toUpdate.size, plan.unchanged)
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
        library.requireActive(serverId, userId)
        ids.chunked(LibraryDao.MAX_VARIABLES).forEach { chunk ->
            val rows = library.itemsByIds(serverId, userId, chunk).filter { it.deletedAtMs != null }
            if (rows.isEmpty()) return@forEach
            val rowIds = rows.map { it.itemId }
            val genres = library.genreNames(serverId, userId, rowIds)
            val tags = library.tagNames(serverId, userId, rowIds)
            val plan = history.planRevival(rows, genres, tags, nowMs)
            history.insertVersions(plan.versions)
            library.updateItems(plan.revived)
        }
    }

    /**
     * Purges the account's history older than [retentionMs] at [nowMs], in one transaction after the account is
     * checked: the versions superseded, and the tombstones set, more than that long ago. The current version of a live
     * item is never touched, and a purged tombstone takes its genres, tags and versions with it (the foreign keys). The
     * versions go first, so the count is of the ones superseded long ago and not of the ones a tombstone takes with it.
     * Both deletes are plain SQL and nothing is passed in but numbers, so no callback runs inside the transaction.
     *
     * A clock that looks wrong purges nothing (Vimes L2): [nowMs] before [referenceMs], or more than the retention
     * minus the 72 h safe look-back after it, would make the age of every row meaningless. [referenceMs] is the last
     * successful sync as the caller read it **before** its run recorded a new one (Vimes M-1 on #268), so this does not
     * read the account state. With no reference there is nothing to compare with, and the purge runs.
     */
    @Transaction
    open suspend fun purgeHistory(
        serverId: String,
        userId: String,
        nowMs: Long,
        retentionMs: Long,
        referenceMs: Long?,
    ): PurgeOutcome {
        library.requireActive(serverId, userId)
        val skip = clockLooksWrong(referenceMs, nowMs, retentionMs)
        if (skip != null) return PurgeOutcome.Skipped(skip)
        val cutoffMs = nowMs - retentionMs
        val versions = history.deleteVersionsSupersededBefore(serverId, userId, cutoffMs)
        val tombstones = library.deleteTombstonesBefore(serverId, userId, cutoffMs)
        return PurgeOutcome.Purged(tombstones = tombstones, versions = versions)
    }

    private fun clockLooksWrong(
        referenceMs: Long?,
        nowMs: Long,
        retentionMs: Long,
    ): PurgeSkip? =
        when {
            referenceMs == null -> null
            nowMs < referenceMs -> PurgeSkip.ClockBehindLastSync
            nowMs - referenceMs > retentionMs - HistoryRetention.SAFE_LOOKBACK_MS -> PurgeSkip.ClockFarAhead
            else -> null
        }
}
