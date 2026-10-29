// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Transaction
import tv.stationbreak.core.model.CanonicalId
import java.util.TreeSet

/** What a page wrote: the [items] and the [series] it held, each counted as [PageWrite]. */
internal data class PageResult(
    val items: PageWrite,
    val series: PageWrite,
)

/**
 * What [CacheTransactionDao.ruleSnapshot] read in its one transaction: the [items] (the rows of the as-of read), the
 * [series] the episodes among them point to, the [collections] held with their members, and whether a full collection
 * pass has succeeded ([collectionsSynced]). Plain lists, built before the transaction ends.
 */
internal class RuleRows(
    val items: List<HeldRow>,
    val series: List<HeldSeries>,
    val collections: List<HeldCollection>,
    val collectionsSynced: Boolean,
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
// One method per transaction that spans DAOs; Room generates the implementation of an abstract @Dao.
@Suppress("TooManyFunctions", "AbstractClassCanBeConcreteClass")
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
     * genres, tags and studios replaced; a new one is inserted. A change to a field the scheduler reads (genres, tags
     * and studios included), or a row that clears a tombstone, first keeps the old row as a version (spec 09). The
     * account must still be signed in, which is checked inside this transaction. [nowMs] stamps new rows and versions.
     *
     * The item rows (with their genres, tags and studios) are written through [LibraryDao] first, then the versions
     * through [ItemHistoryDao], then the checkpoint. [betweenItemsAndHistory] runs after the first and before the
     * second, and [betweenItemsAndCheckpoint] after the second and before the checkpoint. Both are test seams, null in
     * the app: a test makes the write die at either point and proves nothing before it is kept.
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
        // The old genres, tags and studios are read here, inside the transaction and before they are deleted below.
        val old = library.sideNames(server, user, existing.keys)
        val plan = history.planPage(items, existing, old, nowMs)
        library.insertItems(plan.toInsert)
        library.updateItems(plan.toUpdate)
        val changedIds = (plan.toInsert + plan.toUpdate).map { it.itemId }
        changedIds.chunked(LibraryDao.MAX_VARIABLES).forEach {
            library.deleteGenres(server, user, it)
            library.deleteTags(server, user, it)
            library.deleteStudios(server, user, it)
        }
        library.insertGenres(changedIds.flatMap { items.getValue(it).genres })
        library.insertTags(changedIds.flatMap { items.getValue(it).tags })
        library.insertStudios(changedIds.flatMap { items.getValue(it).studios })
        betweenItemsAndHistory?.invoke()
        history.insertVersions(plan.versions)
        val seriesWrite = writeSeries(server, user, seriesRows, nowMs)
        // Inside this transaction, so a write that rolls back leaves the generation as it was.
        val changed = changedIds.isNotEmpty() || seriesWrite.inserted + seriesWrite.updated > 0
        if (changed) library.bumpGeneration(server, user)
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
        val changed = tombstoneSeriesIn(serverId, userId, ids, atMs) + supersedeMembersIn(serverId, userId, ids, atMs)
        if (changed > 0) library.bumpGeneration(serverId, userId)
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
        val changed = tombstoneItemsIn(serverId, userId, ids, atMs) + supersedeMembersIn(serverId, userId, ids, atMs)
        if (changed > 0) library.bumpGeneration(serverId, userId)
    }

    // The items of [ids] that were live, now tombstoned at [atMs]: how many.
    private suspend fun tombstoneItemsIn(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    ): Int {
        var changed = 0
        for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES)) changed += library.tombstone(serverId, userId, chunk, atMs)
        return changed
    }

    private suspend fun supersedeMembersIn(
        serverId: String,
        userId: String,
        memberIds: List<String>,
        stamp: Long,
    ): Int {
        // Items and series are stored as the server sent their IDs, members in canonical form (Vimes M1).
        val canonical = memberIds.mapNotNull { CanonicalId.parseOrNull(it)?.value }.distinct()
        var ended = 0
        for (chunk in canonical.chunked(LibraryDao.MAX_VARIABLES)) {
            ended += collections.supersedeMember(serverId, userId, chunk, stamp)
        }
        return ended
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
        if (reviveSeriesIn(serverId, userId, ids, nowMs) > 0) library.bumpGeneration(serverId, userId)
    }

    private suspend fun tombstoneSeriesIn(
        serverId: String,
        userId: String,
        ids: List<String>,
        atMs: Long,
    ): Int {
        var changed = 0
        for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES)) changed += series.tombstone(serverId, userId, chunk, atMs)
        return changed
    }

    private suspend fun reviveSeriesIn(
        serverId: String,
        userId: String,
        ids: List<String>,
        nowMs: Long,
    ): Int {
        var revived = 0
        for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES)) {
            val rows = series.byIds(serverId, userId, chunk).filter { it.deletedAtMs != null }
            if (rows.isEmpty()) continue
            val plan = series.planRevival(rows, nowMs)
            series.insertVersions(plan.versions)
            series.update(plan.revived)
            revived += plan.revived.size
        }
        return revived
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
        if (reviveItemsIn(serverId, userId, ids, nowMs) > 0) library.bumpGeneration(serverId, userId)
    }

    private suspend fun reviveItemsIn(
        serverId: String,
        userId: String,
        ids: List<String>,
        nowMs: Long,
    ): Int {
        var revived = 0
        for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES)) {
            val rows = library.itemsByIds(serverId, userId, chunk).filter { it.deletedAtMs != null }
            if (rows.isEmpty()) continue
            val rowIds = rows.map { it.itemId }
            val names = library.sideNames(serverId, userId, rowIds)
            val plan = history.planRevival(rows, names, nowMs)
            history.insertVersions(plan.versions)
            library.updateItems(plan.revived)
            revived += plan.revived.size
        }
        return revived
    }

    /**
     * One reconcile's decisions for a library, written together (spec 10, Vimes M3): the items and the series that
     * are gone are tombstoned at [nowMs], and those that came back are revived, each with its version, in one
     * transaction after the account check. Tombstones go first, with the end of their IDs' member rows in every
     * collection (ADR-0015), then the revives. [betweenTombstonesAndRevives] runs between them (a test seam; null in
     * the app).
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
        var changed = tombstoneItemsIn(serverId, userId, itemsGone, nowMs)
        changed += tombstoneSeriesIn(serverId, userId, seriesGone, nowMs)
        // Before the seam, so a fault there rolls back the tombstones and the cascade together.
        changed += supersedeMembersIn(serverId, userId, itemsGone + seriesGone, nowMs)
        betweenTombstonesAndRevives?.invoke()
        changed += reviveItemsIn(serverId, userId, itemsReturning, nowMs)
        changed += reviveSeriesIn(serverId, userId, seriesReturning, nowMs)
        // Only rows that changed count: a decision already in place (another pass got there first) moves nothing.
        if (changed > 0) library.bumpGeneration(serverId, userId)
    }

    /**
     * Purges the account's history older than [retentionMs] at [nowMs], in one transaction after the account is
     * checked: the versions superseded, and the tombstones set, more than that long ago, for items, series and
     * collections (whose member rows superseded that long ago go too). The current version of a live item or series is
     * never touched, and a purged tombstone takes its genres, tags, studios and versions with it (the foreign keys).
     * The versions go first, so the count is of the ones superseded long ago and not of the ones a tombstone takes
     * with it. A tombstone also needs `seenFromMs < cutoff` (Vimes L-1 on #268).
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
        val collectionVersions = collections.deleteVersionsSupersededBefore(serverId, userId, cutoffMs)
        val collectionMembers = collections.deleteMembersSupersededBefore(serverId, userId, cutoffMs)
        val tombstones = library.deleteTombstonesBefore(serverId, userId, cutoffMs)
        val seriesTombstones = series.deleteTombstonesBefore(serverId, userId, cutoffMs)
        val collectionTombstones = collections.deleteTombstonesBefore(serverId, userId, cutoffMs)
        return PurgeOutcome.Purged(
            tombstones,
            versions,
            seriesTombstones,
            seriesVersions,
            collectionTombstones,
            collectionVersions,
            collectionMembers,
        )
    }

    /**
     * Writes one collection and its complete member list in one transaction after the account check (spec 10, Vimes
     * H1, H2, M4). A new collection is inserted only with its first members, at one stamp. A collection that exists is
     * diffed by member (see [CollectionDao.planWrite]): any change takes one stamp, which ends the removed members,
     * begins the added ones, moves the collection row and keeps the lifecycle it ends as a `collection_version`. A
     * tombstoned collection that is written is revived at that same stamp. A read that changes nothing writes nothing
     * but a new name. [members] are the whole list, already filtered to the kinds the cache stores.
     * [betweenCollectionAndMembers] runs after the collection row and its version, before the members (a test seam;
     * null in the app).
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
        // A write that found the collection as stored (no stamp) changed no row.
        if (stamp != null || plan.insert != null || plan.revived) library.bumpGeneration(serverId, userId)
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
        var changed = 0
        for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES)) {
            changed += collections.tombstone(serverId, userId, chunk, atMs)
        }
        if (changed > 0) library.bumpGeneration(serverId, userId)
    }

    /**
     * A collection pass starts. A pass still marked running means the process died in it, so that one counts as having
     * ended without completing. Then this one is marked running, until [recordCollectionPass] or [abandonCollectionPass].
     */
    @Transaction
    open suspend fun beginCollectionPass(
        serverId: String,
        userId: String,
    ) {
        library.requireActive(serverId, userId)
        library.ensureMetaRow(serverId, userId)
        val row = library.metaRow(serverId, userId)
        val failed = (row?.failedPasses ?: 0) + if (row?.passRunning == true) 1 else 0
        library.setPassState(serverId, userId, failed, running = true)
    }

    /** A collection pass ended without completing (it failed or was cancelled): one more, and none is running. */
    @Transaction
    open suspend fun abandonCollectionPass(
        serverId: String,
        userId: String,
    ) {
        library.requireActive(serverId, userId)
        val row = library.metaRow(serverId, userId) ?: return
        if (row.passRunning) library.setPassState(serverId, userId, row.failedPasses + 1, running = false)
    }

    /**
     * Records that a collection pass that began at [startedMs] completed (Vimes H4, M2): the cursor moves to its start,
     * and when it was a full re-list so does `lastRelistMs`. Called only after the whole pass succeeded. It reads and
     * writes the `collection_sync` row only, never `account_state`.
     */
    @Transaction
    open suspend fun recordCollectionPass(
        serverId: String,
        userId: String,
        startedMs: Long,
        relisted: Boolean,
    ) {
        library.requireActive(serverId, userId)
        library.ensureMetaRow(serverId, userId)
        library.setPassState(serverId, userId, failed = 0, running = false)
        val before = collections.syncRow(serverId, userId)
        // One row: `collectionsSinceMs` (the pass signal) and `lastRelistMs` (`ruleSnapshot`'s collectionsSynced) move
        // together, which the day build's snapshot cache relies on (RuleSnapshotKeyContractTest). Never split them.
        val relistMs = if (relisted) startedMs else before?.lastRelistMs
        collections.saveSyncRow(CollectionSyncEntity(serverId, userId, startedMs, relistMs))
        // The first full re-list turns collectionsSynced true, which changes every snapshot with no row written: it is
        // a change of content, so the generation alone keys a snapshot (#480). A later pass leaves it true.
        if (before?.lastRelistMs == null && relistMs != null) library.bumpGeneration(serverId, userId)
    }

    /**
     * [serverId] and [userId] are signed in: every other account's rows go, collections and their sync rows included
     * (they belong to no library, so deleting the libraries does not reach them; Vimes M7). One transaction.
     */
    @Transaction
    open suspend fun activate(
        serverId: String,
        userId: String,
    ) {
        library.deleteOtherLibraries(serverId, userId)
        collections.deleteOtherCollections(serverId, userId)
        collections.deleteOtherSyncRows(serverId, userId)
        library.deleteOtherStates(serverId, userId)
        library.deleteOtherMeta(serverId, userId)
        library.deleteOtherActive(serverId, userId)
        library.insertActive(ActiveAccountEntity(serverId, userId))
    }

    /** The account signed out: its rows go, collections and their sync row included. One transaction. */
    @Transaction
    open suspend fun forget(
        serverId: String,
        userId: String,
    ) {
        library.deleteLibraries(serverId, userId)
        collections.deleteCollections(serverId, userId)
        collections.deleteSyncRow(serverId, userId)
        library.deleteState(serverId, userId)
        library.deleteMeta(serverId, userId)
        library.deleteActive(serverId, userId)
    }

    /**
     * The rule snapshot's reads, in one transaction so they are one state (spec 10, Unit 3, Granny M1): the items held
     * before [cutoffMs] ([ItemHistoryDao.heldAsOf], in chunks of [itemChunk], selected from [libraries]), the series
     * that an episode among them points to ([SeriesDao.heldAsOf]), the collections with their members
     * ([CollectionDao.heldAsOf], whatever the libraries are) and the collection pass's last full re-list. A write that
     * lands while this runs is not seen by a later part. Each part is read into a list; no lambda goes into a DAO loop
     * (#264). It holds the writer lock for the whole read, so the sync's writes queue behind it for up to about 3 s on
     * the slowest device, once a day; that is accepted (Vimes). [betweenItemsAndSeries] runs after the items are read
     * and before the rest (a test seam; null in the app).
     */
    @Transaction
    @Suppress("LongParameterList") // One cohesive call: the account, the cut-off, the libraries and the test seam.
    open suspend fun ruleSnapshot(
        serverId: String,
        userId: String,
        cutoffMs: Long,
        itemChunk: Int,
        libraries: Set<String>,
        betweenItemsAndSeries: (() -> Unit)?,
    ): RuleRows {
        val strings = Interner()
        val items =
            if (libraries.isEmpty()) {
                emptyList()
            } else {
                history.heldAsOf(serverId, userId, cutoffMs, itemChunk, libraries, strings)
            }
        betweenItemsAndSeries?.invoke()
        val seriesIds = TreeSet<String>()
        for (row in items) {
            val seriesId = row.seriesId
            if (row.kind == EPISODE_KIND && seriesId != null) seriesIds.addAll(spellings(seriesId))
        }
        val heldSeries = series.heldAsOf(serverId, userId, cutoffMs, seriesIds.toList(), strings)
        val heldCollections = collections.heldAsOf(serverId, userId, cutoffMs, COLLECTION_CHUNK)
        val synced = collections.syncRow(serverId, userId)?.lastRelistMs != null
        return RuleRows(items, heldSeries, heldCollections, synced)
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

    // The spellings a series may be stored under for an episode's [raw] series ID: the one it names, the canonical one
    // and the dashed one, each in lower and upper case. SQL cannot compare canonical forms, and a series is stored
    // as the server spelled it; the caller matches the rows it gets back by canonical ID (spec 10, Vimes L).
    private fun spellings(raw: String): List<String> {
        val canonical = CanonicalId.parseOrNull(raw)?.value ?: return listOf(raw)
        val dashed =
            listOf(
                canonical.substring(0, 8),
                canonical.substring(8, 12),
                canonical.substring(12, 16),
                canonical.substring(16, 20),
                canonical.substring(20),
            ).joinToString("-")
        return listOf(raw, canonical, canonical.uppercase(), dashed, dashed.uppercase())
    }

    private companion object {
        /** Collections the as-of read takes at a time: each has a member list, so a chunk is small. */
        const val COLLECTION_CHUNK = 100

        /** The kind column of an episode, as `ScheduleItemKind` names it. */
        const val EPISODE_KIND = "Episode"
    }
}
