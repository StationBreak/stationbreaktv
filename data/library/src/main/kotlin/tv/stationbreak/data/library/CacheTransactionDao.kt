// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Transaction

/** What a page wrote: the [items] and the [series] it held, each counted as [PageWrite]. */
internal data class PageResult(
    val items: PageWrite,
    val series: PageWrite,
)

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
@Suppress("AbstractClassCanBeConcreteClass") // Room generates the implementation of an abstract @Dao.
internal abstract class CacheTransactionDao(
    private val db: LibraryDatabase,
) {
    private val library get() = db.dao()
    private val history get() = db.itemHistoryDao()
    private val series get() = db.seriesDao()
    private val collections get() = db.collectionDao()

    /**
     * Writes one page and its checkpoint together (no checkpoint when [checkpoint] is null: a changed-since page moves
     * no position). An item the database already holds, equal, is left alone; one that differs is updated and its
     * genres and tags replaced; a new one is inserted. A change to a field the scheduler reads (genres and tags
     * included), or a row that clears a tombstone, first keeps the old row as a version (spec 09). The account must
     * still be signed in, which is checked inside this transaction. [nowMs] stamps new rows and versions.
     *
     * The item rows (with their genres and tags) are written through [LibraryDao] first, then the versions through
     * [ItemHistoryDao], then the checkpoint. [betweenItemsAndHistory] runs after the first and before the second, and
     * [betweenItemsAndCheckpoint] after the second and before the checkpoint. Both are test seams, null in the app: a
     * test makes the write die at either point and proves nothing before it is kept.
     *
     * [seriesRows] are the page's series (a series page holds no items, and an item page no series). They are written
     * through [SeriesDao] after the item versions and before the second seam, in the same transaction.
     */
    @Transaction
    @Suppress("LongParameterList") // One cohesive call: the page, its checkpoint, the time and the test seams.
    open suspend fun writePage(
        server: String,
        user: String,
        checkpoint: CheckpointEntity?,
        items: Map<String, ItemRows>,
        seriesRows: Map<String, SeriesEntity>,
        nowMs: Long,
        betweenItemsAndHistory: (() -> Unit)?,
        betweenItemsAndCheckpoint: (() -> Unit)?,
    ): PageResult {
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
        library.updateItems(plan.toUpdate)
        val changedIds = (plan.toInsert + plan.toUpdate).map { it.itemId }
        changedIds.chunked(LibraryDao.MAX_VARIABLES).forEach {
            library.deleteGenres(server, user, it)
            library.deleteTags(server, user, it)
        }
        library.insertGenres(changedIds.flatMap { items.getValue(it).genres })
        library.insertTags(changedIds.flatMap { items.getValue(it).tags })
        betweenItemsAndHistory?.invoke()
        history.insertVersions(plan.versions)
        val seriesWrite = writeSeries(server, user, seriesRows, nowMs)
        betweenItemsAndCheckpoint?.invoke()
        checkpoint?.let { library.saveCheckpoint(it) }
        return PageResult(PageWrite(plan.toInsert.size, plan.toUpdate.size, plan.unchanged), seriesWrite)
    }

    private suspend fun writeSeries(
        server: String,
        user: String,
        rows: Map<String, SeriesEntity>,
        nowMs: Long,
    ): PageWrite {
        val existing =
            rows.keys
                .chunked(LibraryDao.MAX_VARIABLES)
                .flatMap { series.byIds(server, user, it) }
                .associateBy { it.seriesId }
        val plan = series.planPage(rows, existing, nowMs)
        series.insert(plan.toInsert)
        series.update(plan.toUpdate)
        series.insertVersions(plan.versions)
        return PageWrite(plan.toInsert.size, plan.toUpdate.size, plan.unchanged)
    }

    /**
     * Marks the series [ids] deleted at [atMs] (an existing tombstone keeps its time). No version is added. Each ID's
     * current member rows in every collection end at [atMs], in the same transaction (ADR-0015).
     */
    @Transaction
    open suspend fun tombstoneSeries(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    ) {
        library.requireActive(serverId, userId)
        tombstoneSeriesIn(serverId, userId, ids, atMs)
        supersedeMembersIn(serverId, userId, ids, atMs)
    }

    /**
     * Marks the items [ids] deleted at [atMs] (an existing tombstone keeps its time), and ends each ID's current member
     * rows in every collection of the account at [atMs], in the same transaction (ADR-0015): Jellyfin does not bump a
     * collection when a member is deleted, so the item reconcile is what sees the deletion. A later revival re-adds
     * nothing.
     */
    @Transaction
    open suspend fun tombstoneItems(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    ) {
        library.requireActive(serverId, userId)
        for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES)) library.tombstone(serverId, userId, chunk, atMs)
        supersedeMembersIn(serverId, userId, ids, atMs)
    }

    private suspend fun supersedeMembersIn(
        serverId: String,
        userId: String,
        memberIds: List<String>,
        stamp: Long,
    ) {
        for (chunk in memberIds.chunked(LibraryDao.MAX_VARIABLES)) collections.supersedeMember(serverId, userId, chunk, stamp)
    }

    /**
     * Clears the tombstone of the series [ids] at [nowMs], each tombstoned row copied as a version first (as
     * [reviveAll] does for items). An id that is not a tombstone is left alone.
     */
    @Transaction
    open suspend fun reviveSeries(
        serverId: String,
        userId: String,
        ids: List<String>,
        nowMs: Long,
    ) {
        library.requireActive(serverId, userId)
        reviveSeriesIn(serverId, userId, ids, nowMs)
    }

    private suspend fun tombstoneSeriesIn(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    ) {
        for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES)) series.tombstone(serverId, userId, chunk, atMs)
    }

    private suspend fun reviveSeriesIn(
        serverId: String,
        userId: String,
        ids: List<String>,
        nowMs: Long,
    ) {
        for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES)) {
            val rows = series.byIds(serverId, userId, chunk).filter { it.deletedAtMs != null }
            if (rows.isEmpty()) continue
            val plan = series.planRevival(rows, nowMs)
            series.insertVersions(plan.versions)
            series.update(plan.revived)
        }
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
        reviveItemsIn(serverId, userId, ids, nowMs)
    }

    private suspend fun reviveItemsIn(
        serverId: String,
        userId: String,
        ids: List<String>,
        nowMs: Long,
    ) {
        for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES)) {
            val rows = library.itemsByIds(serverId, userId, chunk).filter { it.deletedAtMs != null }
            if (rows.isEmpty()) continue
            val rowIds = rows.map { it.itemId }
            val genres = library.genreNames(serverId, userId, rowIds)
            val tags = library.tagNames(serverId, userId, rowIds)
            val plan = history.planRevival(rows, genres, tags, nowMs)
            history.insertVersions(plan.versions)
            library.updateItems(plan.revived)
        }
    }

    /**
     * One reconcile's decisions for a library, written together (spec 10, Vimes M3): the items and the series that
     * are gone are tombstoned at [nowMs], and those that came back are revived, each with its version, in one
     * transaction after the account check. Tombstones go first, with the end of their IDs' member rows in every
     * collection (ADR-0015), then the revives. [betweenTombstonesAndRevives] runs between them (a test seam; null in the
     * app).
     */
    @Transaction
    @Suppress("LongParameterList") // One cohesive call: the four decisions, the time and the test seam.
    open suspend fun reconcileWrite(
        serverId: String,
        userId: String,
        itemsGone: List<String>,
        itemsReturning: List<String>,
        seriesGone: List<String>,
        seriesReturning: List<String>,
        nowMs: Long,
        betweenTombstonesAndRevives: (() -> Unit)?,
    ) {
        library.requireActive(serverId, userId)
        for (chunk in itemsGone.chunked(LibraryDao.MAX_VARIABLES)) library.tombstone(serverId, userId, chunk, nowMs)
        tombstoneSeriesIn(serverId, userId, seriesGone, nowMs)
        // Before the seam, so a fault there rolls back the tombstones and the cascade together.
        supersedeMembersIn(serverId, userId, itemsGone + seriesGone, nowMs)
        betweenTombstonesAndRevives?.invoke()
        reviveItemsIn(serverId, userId, itemsReturning, nowMs)
        reviveSeriesIn(serverId, userId, seriesReturning, nowMs)
    }

    /**
     * Purges the account's history older than [retentionMs] at [nowMs], in one transaction after the account is
     * checked: the versions superseded, and the tombstones set, more than that long ago, for items and for series. The
     * current version of a live item or series is never touched, and a purged tombstone takes its genres, tags and
     * versions with it (the foreign keys). The versions go first, so the count is of the ones superseded long ago and
     * not of the ones a tombstone takes with it. A tombstone also needs `seenFromMs < cutoff` (Vimes L-1 on #268).
     * Every delete is plain SQL and nothing is passed in but numbers, so no callback runs inside the transaction.
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
        // Versions go before tombstones in each table, so the counts are of the versions superseded long ago and not of
        // the ones a tombstone takes with it. The current row of a live item or series is never touched.
        val versions = history.deleteVersionsSupersededBefore(serverId, userId, cutoffMs)
        val seriesVersions = series.deleteVersionsSupersededBefore(serverId, userId, cutoffMs)
        val tombstones = library.deleteTombstonesBefore(serverId, userId, cutoffMs)
        val seriesTombstones = series.deleteTombstonesBefore(serverId, userId, cutoffMs)
        return PurgeOutcome.Purged(tombstones, versions, seriesTombstones, seriesVersions)
    }

    /**
     * Writes one collection and its complete member list in one transaction after the account check (spec 10, Vimes
     * H1, H2, M4). A new collection is inserted only with its first members, at one stamp. A collection that exists is
     * diffed by member (see [CollectionDao.planWrite]): any change takes one stamp, which ends the removed members, begins
     * the added ones, moves the collection row and keeps the lifecycle it ends as a `collection_version`. A tombstoned
     * collection that is written is revived at that same stamp. A read that changes nothing writes nothing but a new
     * name. [members] are the whole list, already filtered to the kinds the cache stores. [betweenCollectionAndMembers]
     * runs after the collection row and its version, before the members (a test seam; null in the app).
     */
    @Transaction
    @Suppress("LongParameterList") // One cohesive call: the collection, its list, the time and the test seam.
    open suspend fun writeCollection(
        serverId: String,
        userId: String,
        collectionId: String,
        name: String,
        members: List<CollectionMemberEntity>,
        nowMs: Long,
        betweenCollectionAndMembers: (() -> Unit)?,
    ): CollectionWrite {
        library.requireActive(serverId, userId)
        val existing = collections.byId(serverId, userId, collectionId)
        val current = if (existing == null) emptyList() else collections.currentMembers(serverId, userId, collectionId)
        val plan = collections.planWrite(serverId, userId, collectionId, name, members, existing, current, nowMs)
        plan.insert?.let { collections.insert(it) }
        plan.version?.let { collections.insertVersion(it) }
        plan.update?.let { collections.update(it) }
        val stamp = plan.stamp
        if (stamp != null) {
            for (chunk in plan.removed.chunked(LibraryDao.MAX_VARIABLES)) {
                collections.endMembers(serverId, userId, collectionId, chunk, stamp)
            }
            betweenCollectionAndMembers?.invoke()
            collections.insertMembers(plan.add)
        }
        return CollectionWrite(stamp, plan.insert != null, plan.revived, plan.add.size, plan.removed.size)
    }

    /** Marks the collections [ids] deleted at [atMs] (an existing tombstone keeps its time). No version is added. */
    @Transaction
    open suspend fun tombstoneCollections(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    ) {
        library.requireActive(serverId, userId)
        for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES)) collections.tombstone(serverId, userId, chunk, atMs)
    }

    /** The collections the account held before [cutoffMs], with their members: [CollectionDao.heldAsOf]. */
    @Transaction
    open suspend fun heldCollections(
        serverId: String,
        userId: String,
        cutoffMs: Long,
    ): List<HeldCollection> = collections.heldAsOf(serverId, userId, cutoffMs, COLLECTION_CHUNK)

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

    private companion object {
        /** Collections the as-of read takes at a time: each has a member list, so a chunk is small. */
        const val COLLECTION_CHUNK = 100
    }
}
