// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.SeriesInfo
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Where the sync of one library stands (see [CheckpointEntity]). */
internal data class Checkpoint(
    val library: ItemId,
    val nextStartIndex: Int,
    val totalCount: Int?,
    val run: Long,
)

/** A stored item as it is on disk, tombstones and unusable runtimes included. */
internal data class StoredItem(
    val item: MediaItem,
    val library: ItemId,
    val deletedAt: Instant?,
)

/** How many rows each table holds, across every account. */
internal data class RowCounts(
    val items: Int,
    val genres: Int,
    val tags: Int,
    val libraries: Int,
    val checkpoints: Int,
    val states: Int,
    val versions: Int = 0,
    val series: Int = 0,
    val seriesVersions: Int = 0,
    val collections: Int = 0,
    val collectionVersions: Int = 0,
    val collectionMembers: Int = 0,
    val collectionSyncs: Int = 0,
    val studios: Int = 0,
) {
    companion object {
        val NONE = RowCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * The saved library in Room (spec 06, Unit 2): the [LibraryRepository] port for the scheduler and the app, and the
 * internal calls the sync (3.0 and 4.0) uses to fill and tidy it. Every row is keyed by server and user, a page and its
 * checkpoint are written in one transaction, a deleted item is a tombstone until the history purge, and nothing here
 * ever holds a
 * token. The database is a cache: it can be deleted and fetched again.
 */
@Suppress("TooManyFunctions", "LargeClass") // The port plus the sync's side: each is one call into a DAO transaction.
class RoomLibraryRepository internal constructor(
    private val db: LibraryDatabase,
    private val clock: Clock,
    private val sync: SyncControl = SyncControl.None,
    private val retention: HistoryRetention = HistoryRetention(),
    internal val collectionRelist: CollectionRelist = CollectionRelist(),
) : LibraryRepository {
    internal val dao =
        db.dao().also { dao ->
            dao.generationSeed = { clock.now().toEpochMilliseconds() shl LibraryDao.GENERATION_SHIFT }
        }

    /**
     * Held for the length of a collection pass, so the first sync's pass and a stay-current run's never overlap: one
     * would otherwise see the other's pass, still marked running, as a pass that died.
     */
    internal val collectionPassLock = Mutex()

    internal val history = db.itemHistoryDao()
    private val seriesDao = db.seriesDao()
    private val collectionDao = db.collectionDao()
    private val transactions = db.cacheTransactionDao()

    /**
     * Test seam: runs inside [writePage]'s transaction after the items are written and before the checkpoint is, so a
     * test can make the write die in between and prove neither is kept. Null in the app.
     */
    internal var faultBetweenItemsAndCheckpoint: (() -> Unit)? = null

    /**
     * Test seam: runs inside [writePage]'s transaction after the item rows are written through `LibraryDao` and before
     * the versions are written through `ItemHistoryDao`, to prove a transaction across DAOs rolls back whole. Null in
     * the app.
     */
    internal var faultBetweenItemsAndHistory: (() -> Unit)? = null

    /**
     * Test seam: runs inside [applyReconcile]'s transaction after the tombstones and before the revives, to prove the
     * items' and the series' changes are kept together or not at all. Null in the app.
     */
    internal var faultBetweenReconcileTombstonesAndRevives: (() -> Unit)? = null

    /**
     * Test seam: runs inside [writeCollection]'s transaction after the collection row and before its members, to prove
     * a collection is never stored without its first complete member list. Null in the app.
     */
    internal var faultBetweenCollectionAndMembers: (() -> Unit)? = null

    /**
     * Test seam: runs inside [ruleSnapshot]'s transaction after the items are read and before the series and the
     * collections are, so a test can write there and prove the snapshot does not see it (one transaction, one state).
     * Null in the app.
     */
    internal var faultDuringRuleSnapshot: (() -> Unit)? = null

    // The port.

    override fun status(account: LibraryAccount): Flow<LibraryStatus> =
        dao
            .observeStatus(account.server.value, account.user.value)
            .map { row -> row?.toStatus() ?: LibraryStatus(SyncState.NeverSynced, null) }
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun activeStatus(): Flow<LibraryStatus?> =
        dao
            .observeActive()
            .distinctUntilChanged()
            .flatMapLatest { active ->
                if (active == null) {
                    flowOf(null)
                } else {
                    status(LibraryAccount(ServerId(active.serverId), UserId(active.userId)))
                }
            }

    override fun activeAccount(): Flow<LibraryAccount?> =
        dao
            .observeActive()
            .map { active -> active?.let { LibraryAccount(ServerId(it.serverId), UserId(it.userId)) } }
            .distinctUntilChanged()

    override suspend fun collectionNames(account: LibraryAccount): CollectionNames {
        val (server, user) = account.keys()
        val names = HashMap<CanonicalId, String>()
        for (row in collectionDao.liveNames(server, user)) {
            // First wins when two stored spellings normalise to one ID, as for the collections of a RuleSnapshot.
            CanonicalId.parseOrNull(row.collectionId)?.let { names.putIfAbsent(it, row.name) }
        }
        return CollectionNames(names)
    }

    override suspend fun programInfo(
        account: LibraryAccount,
        ids: Set<CanonicalId>,
    ): Map<CanonicalId, ProgramInfo> = account.keys().let { (server, user) -> dao.programInfo(server, user, ids) }

    override suspend fun currentIds(
        account: LibraryAccount,
        ids: Set<CanonicalId>,
    ): Set<CanonicalId> = account.keys().let { (server, user) -> dao.currentIds(server, user, ids) }

    // The collection_sync row is written only after a whole pass succeeded (recordCollectionPass), and the items'
    // finishSync, which runs before the pass, never touches it. The flow also gives null when the account's row is
    // deleted (forget, or another account becoming active) and re-emits on writes to other accounts' rows.
    override fun collectionPassCompleted(account: LibraryAccount): Flow<Instant?> =
        collectionDao
            .observeSyncSince(account.server.value, account.user.value)
            .map { ms -> ms?.let(Instant::fromEpochMilliseconds) }

    // Part of the key of cached rule snapshots with collectionPassCompleted (see LibraryRepository.ruleSnapshot): every
    // change to what ruleSnapshot returns moves this or the pass signal, pinned by RuleSnapshotKeyContractTest.
    // A suspend read, not a Flow: the one reader decides at a trigger or at start, and nothing needs pushing to it.
    override suspend fun contentGeneration(account: LibraryAccount): Long {
        val (server, user) = account.keys()
        return dao.generation(server, user) ?: 0L
    }

    /** Not for the scheduler: it reads the current rows and hides tombstones. The scheduler reads [scheduleItems]. */
    @Suppress("OVERRIDE_DEPRECATION")
    override suspend fun snapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        createdBefore: Instant,
    ): List<MediaItem> {
        if (libraries.isEmpty()) return emptyList()
        val (server, user) = account.keys()
        return dao
            .snapshotRows(server, user, libraries.map { it.value }, createdBefore.ceilingMillis())
            .map { it.toItem() }
    }

    override suspend fun scheduleItems(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): List<ScheduleItem> = scheduleInputs(account, libraries, cutoff).items

    /**
     * The rule engine's input as held before [cutoff] (spec 10, Unit 3), read in one transaction
     * ([CacheTransactionDao.ruleSnapshot]) so the items, the series and the collections are one state. The items are
     * made from the same rows as [scheduleItems], by [ScheduleInputs.read], so `items.map { it.item }` is what
     * [scheduleItems] returns. An item whose library is not a canonical ID has no place in the snapshot, and nor has a
     * series or collection whose own ID is not one. Everything is in canonical-ID order, with no duplicates.
     */
    override suspend fun ruleSnapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): RuleSnapshot {
        val (server, user) = account.keys()
        val cutoffMs = cutoff.ceilingMillis()
        val wanted = libraries.mapTo(HashSet()) { it.value }
        val rows = transactions.ruleSnapshot(server, user, cutoffMs, AS_OF_CHUNK, wanted, faultDuringRuleSnapshot)
        val read = ScheduleInputs.read(rows.items)
        val mapper = RuleItemMapper()
        val items = ArrayList<RuleItem>(read.accepted.size)
        for (accepted in read.accepted) items += mapper.of(accepted)
        return RuleSnapshot(
            cutoffMs = cutoffMs,
            libraries = libraries.mapNotNullTo(HashSet()) { CanonicalId.parseOrNull(it.value) },
            items = items,
            series = RuleInputs.series(items, rows.series, mapper.folds),
            collections = RuleInputs.collections(rows.collections),
            collectionsSynced = rows.collectionsSynced,
        )
    }

    // The one as-of item read: the version of every item held before the cut-off, in ID chunks, as rows. Selection runs
    // over every item first, the libraries filter and the deletion test after (Vimes H4). [scheduleInputs] calls it,
    // and [CacheTransactionDao.ruleSnapshot] calls the same [ItemHistoryDao.heldAsOf] inside its transaction.
    private suspend fun heldRows(
        server: String,
        user: String,
        cutoffMs: Long,
        libraries: Set<ItemId>,
    ): List<HeldRow> {
        if (libraries.isEmpty()) return emptyList()
        return history.heldAsOf(server, user, cutoffMs, AS_OF_CHUNK, libraries.mapTo(HashSet()) { it.value })
    }

    /**
     * [scheduleItems] with the count of what it left out, by reason (spec 09: "count what it dropped, by reason, for
     * diagnostics"). Internal on purpose (Granny L3): the port returns the items alone, because the scheduler needs
     * nothing else and the port has to stay plain Kotlin. It widens when diagnostics or F1.2 need the counts; until
     * then they are for tests to read.
     *
     * Selection comes first and runs over every item of the account, whatever its library (Vimes H4). The libraries
     * filter, the deletion test and the validity checks then apply to the **selected** version only.
     */
    internal suspend fun scheduleInputs(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
    ): ScheduleRead {
        val (server, user) = account.keys()
        val read = ScheduleInputs.read(heldRows(server, user, cutoff.ceilingMillis(), libraries))
        return ScheduleRead(read.accepted.map { it.item }, read.dropped)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override suspend fun excludedCount(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ): Int {
        if (libraries.isEmpty()) return 0
        val (server, user) = account.keys()
        return dao.excludedCount(server, user, libraries.map { it.value })
    }

    override suspend fun select(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ) {
        val (server, user) = account.keys()
        // Unticked libraries go, with their items, genres, tags, studios and checkpoint (foreign keys cascade).
        dao.select(server, user, libraries.map { it.value })
    }

    override suspend fun pendingLibraries(account: LibraryAccount): Set<ItemId> {
        val (server, user) = account.keys()
        return dao.pendingLibraries(server, user).map(::ItemId).toSet()
    }

    override suspend fun useAccount(account: LibraryAccount) {
        val (server, user) = account.keys()
        // The unique sync work goes first, unless this is the account already syncing (the worker restores the
        // session itself, which names it again). Cancelling is asynchronous: the DAO checks the account regardless.
        if (dao.activeAccounts() != listOf(ActiveAccountEntity(server, user))) sync.cancelFirstSync()
        transactions.activate(server, user)
    }

    override suspend fun forget(account: LibraryAccount) {
        val (server, user) = account.keys()
        sync.cancelFirstSync()
        transactions.forget(server, user)
    }

    // The sync's side.

    /**
     * Saves one [page][items] of [library] and the checkpoint that goes with it, in one transaction: after a crash both
     * are there or neither is. Writing the same page again changes nothing (an item that is unchanged is not
     * rewritten; a tombstoned one that came back is revived), so a resume can replay the last page.
     * [library] must have been chosen with [select] first. [series], if any, are written in the same transaction (the
     * transactions across DAOs are one method of `CacheTransactionDao`). That parameter is for tests: the sync writes
     * series apart, with [upsertSeries].
     */
    @Suppress("LongParameterList") // One cohesive call: the page, and the checkpoint that goes with it.
    internal suspend fun writePage(
        account: LibraryAccount,
        library: ItemId,
        items: List<MediaItem>,
        nextStartIndex: Int,
        totalCount: Int?,
        run: Long,
        series: List<SeriesInfo> = emptyList(),
    ): PageWrite {
        val (server, user) = account.keys()
        val checkpoint = CheckpointEntity(server, user, library.value, nextStartIndex, totalCount, run)
        val rows = rowsOf(items, server, user, library)
        val seriesRows = LinkedHashMap<String, SeriesEntity>()
        series.forEach { seriesRows[it.id.value] = it.toEntity(server, user, library.value) }
        val now = clock.now().toEpochMilliseconds()
        return write(server, user, checkpoint, rows, seriesRows, now).items
    }

    /**
     * Saves [items] of [library] with no checkpoint: a page of a changed-since pass, which moves no position. The same
     * idempotent upsert as [writePage], and the same check that the account is still signed in.
     */
    internal suspend fun upsertItems(
        account: LibraryAccount,
        library: ItemId,
        items: List<MediaItem>,
    ): PageWrite {
        val (server, user) = account.keys()
        val rows = rowsOf(items, server, user, library)
        val now = clock.now().toEpochMilliseconds()
        return write(server, user, null, rows, emptyMap(), now).items
    }

    @Suppress("LongParameterList") // The page, its checkpoint, the time: what the transaction takes.
    private suspend fun write(
        server: String,
        user: String,
        checkpoint: CheckpointEntity?,
        rows: Map<String, ItemRows>,
        seriesRows: Map<String, SeriesEntity>,
        now: Long,
    ): PageResult =
        transactions.writePage(
            server,
            user,
            checkpoint,
            rows,
            seriesRows,
            now,
            faultBetweenItemsAndHistory,
            faultBetweenItemsAndCheckpoint,
        )

    /**
     * Saves [series] of [library], with no checkpoint: a series page of the first sync or of a changed-since pass. The
     * same idempotent upsert as for items, in one transaction after the check that the account is still signed in.
     * The counts are of series.
     */
    internal suspend fun upsertSeries(
        account: LibraryAccount,
        library: ItemId,
        series: List<SeriesInfo>,
    ): PageWrite {
        val (server, user) = account.keys()
        val rows = LinkedHashMap<String, SeriesEntity>()
        series.forEach { rows[it.id.value] = it.toEntity(server, user, library.value) }
        return write(server, user, null, emptyMap(), rows, clock.now().toEpochMilliseconds()).series
    }

    /**
     * Marks the series [ids] deleted as of now. A series that is already a tombstone keeps its first time. For tests:
     * the reconcile writes through [applyReconcile].
     */
    internal suspend fun tombstoneSeries(
        account: LibraryAccount,
        ids: Collection<ItemId>,
    ) {
        val (server, user) = account.keys()
        transactions.tombstoneSeries(server, user, ids.map { it.value }, clock.now().toEpochMilliseconds())
    }

    /** Clears the tombstone of the series [ids]: each is kept as a version first. For tests, as [tombstoneSeries]. */
    internal suspend fun reviveSeries(
        account: LibraryAccount,
        ids: Collection<ItemId>,
    ) {
        val (server, user) = account.keys()
        transactions.reviveSeries(server, user, ids.map { it.value }, clock.now().toEpochMilliseconds())
    }

    /**
     * Writes one library's reconcile decisions in one transaction: the items and series that are gone are tombstoned,
     * and those that returned are revived, each with its version. Nothing is written when there is nothing to write.
     */
    internal suspend fun applyReconcile(
        account: LibraryAccount,
        itemsGone: Collection<ItemId>,
        itemsReturning: Collection<ItemId>,
        seriesGone: Collection<ItemId>,
        seriesReturning: Collection<ItemId>,
    ) {
        if (listOf(itemsGone, itemsReturning, seriesGone, seriesReturning).all { it.isEmpty() }) return
        val (server, user) = account.keys()
        transactions.reconcileWrite(
            server,
            user,
            itemsGone.map { it.value },
            itemsReturning.map { it.value },
            seriesGone.map { it.value },
            seriesReturning.map { it.value },
            clock.now().toEpochMilliseconds(),
            faultBetweenReconcileTombstonesAndRevives,
        )
    }

    /** The IDs of [library]'s live series, in order: what the series reconcile compares the server's list with. */
    internal suspend fun liveSeriesIds(
        account: LibraryAccount,
        library: ItemId,
    ): List<ItemId> {
        val (server, user) = account.keys()
        return seriesDao.liveIds(server, user, library.value).map(::ItemId)
    }

    /** The IDs of [library]'s tombstoned series: deleted on the server, not yet purged. */
    internal suspend fun tombstonedSeriesIds(
        account: LibraryAccount,
        library: ItemId,
    ): List<ItemId> {
        val (server, user) = account.keys()
        return seriesDao.tombstonedIds(server, user, library.value).map(::ItemId)
    }

    /** The IDs of every series the account has, live or tombstoned, in any library. */
    internal suspend fun allSeriesIds(account: LibraryAccount): List<ItemId> {
        val (server, user) = account.keys()
        return seriesDao.allIds(server, user).map(::ItemId)
    }

    private fun rowsOf(
        items: List<MediaItem>,
        server: String,
        user: String,
        library: ItemId,
    ): Map<String, ItemRows> {
        val rows = LinkedHashMap<String, ItemRows>()
        items.forEach { rows[it.id.value] = it.toRows(server, user, library.value) }
        return rows
    }

    internal suspend fun checkpoint(
        account: LibraryAccount,
        library: ItemId,
    ): Checkpoint? {
        val (server, user) = account.keys()
        return dao.checkpoint(server, user, library.value)?.let {
            Checkpoint(ItemId(it.libraryId), it.nextStartIndex, it.totalCount, it.syncRun)
        }
    }

    /** Marks [library] as fully fetched: it is no longer in [pendingLibraries]. */
    internal suspend fun markFetched(
        account: LibraryAccount,
        library: ItemId,
    ) {
        val (server, user) = account.keys()
        dao.markFetched(server, user, library.value)
    }

    /** The account's first sync starts (or starts again): the state is running with no total yet. */
    internal suspend fun beginFirstSync(account: LibraryAccount) {
        val (server, user) = account.keys()
        dao.beginFirstSync(server, user)
    }

    /** Page one said the whole library holds [total] items. */
    internal suspend fun setTotal(
        account: LibraryAccount,
        total: Int,
    ) {
        val (server, user) = account.keys()
        dao.setTotal(server, user, total)
    }

    /**
     * A sync ended well: the state is idle and [recorded] is kept with the saved library as the time of the success. It
     * is the moment the pass **began** (the next incremental pass counts changes from it), never when it ended. With no
     * moment given it is now.
     */
    internal suspend fun finishSync(
        account: LibraryAccount,
        recorded: Instant? = null,
    ) {
        val (server, user) = account.keys()
        dao.finishSync(server, user, (recorded ?: clock.now()).toEpochMilliseconds())
    }

    /**
     * An incremental pass ended well (see [LibraryDao.finishIncremental]): like [finishSync], but it does nothing while
     * the account runs a first sync, and clears the backfill flag when [backfillDone].
     */
    internal suspend fun finishIncremental(
        account: LibraryAccount,
        recorded: Instant,
        backfillDone: Boolean,
    ) {
        val (server, user) = account.keys()
        dao.finishIncremental(server, user, recorded.toEpochMilliseconds(), backfillDone)
    }

    /** True while the account's cache still lacks studios and the next incremental pass must read everything again. */
    internal suspend fun backfillPending(account: LibraryAccount): Boolean {
        val (server, user) = account.keys()
        return dao.metaRow(server, user)?.backfillPending ?: false
    }

    /** The recorded moment of the last successful pass, or null before the first sync ended. */
    internal suspend fun lastSuccess(account: LibraryAccount): Instant? {
        val (server, user) = account.keys()
        return dao.state(server, user)?.lastSuccessMs?.let(Instant::fromEpochMilliseconds)
    }

    /** True while the account's first sync is running (a stay-current pass leaves it alone). */
    internal suspend fun isFirstSyncRunning(account: LibraryAccount): Boolean {
        val (server, user) = account.keys()
        return dao.state(server, user)?.state == AccountStateEntity.STATE_FIRST_RUNNING
    }

    /**
     * A pass that does not move the recorded moment (an ID reconciliation) ended well: a failure left by an earlier
     * pass is cleared, and the time of the last success stays as it was.
     */
    internal suspend fun clearFailure(account: LibraryAccount) {
        val (server, user) = account.keys()
        dao.clearFailure(server, user)
    }

    /** A sync stopped for [failure]. Everything saved so far, and the time of the last success, stay. */
    internal suspend fun failSync(
        account: LibraryAccount,
        failure: SyncFailure,
    ) {
        val (server, user) = account.keys()
        dao.failSync(server, user, failure.ordinal)
    }

    /**
     * Marks [ids] deleted as of now. An id that is already a tombstone keeps its first time. For tests: the reconcile
     * writes through [applyReconcile].
     */
    internal suspend fun tombstone(
        account: LibraryAccount,
        ids: Collection<ItemId>,
    ) {
        val (server, user) = account.keys()
        transactions.tombstoneItems(server, user, ids.map { it.value }, clock.now().toEpochMilliseconds())
    }

    /** Clears the tombstone of [ids]: they are back in snapshots. For tests: the reconcile uses [applyReconcile]. */
    internal suspend fun revive(
        account: LibraryAccount,
        ids: Collection<ItemId>,
    ) {
        val (server, user) = account.keys()
        transactions.reviveAll(server, user, ids.map { it.value }, clock.now().toEpochMilliseconds())
    }

    /** The IDs of [library]'s live items, in order: what a reconciliation compares the server's list with. */
    internal suspend fun liveItemIds(
        account: LibraryAccount,
        library: ItemId,
    ): List<ItemId> {
        val (server, user) = account.keys()
        return dao.liveItemIds(server, user, library.value).map(::ItemId)
    }

    /** The IDs of [library]'s tombstoned items: deleted on the server, not yet purged. */
    internal suspend fun tombstonedItemIds(
        account: LibraryAccount,
        library: ItemId,
    ): List<ItemId> {
        val (server, user) = account.keys()
        return dao.tombstonedItemIds(server, user, library.value).map(::ItemId)
    }

    /** The IDs of every row the account has, live or tombstoned, in any library. */
    internal suspend fun allItemIds(account: LibraryAccount): List<ItemId> {
        val (server, user) = account.keys()
        return dao.allItemIds(server, user).map(::ItemId)
    }

    /**
     * Purges [account]'s history older than the retention (30 days by default): its tombstones and its superseded
     * versions, and nothing else (spec 09, Unit 3). Throws [AccountNotActiveException] if the account is signed out.
     *
     * [reference] is the clock guard's anchor: the last successful sync as it stood before the run that calls this
     * recorded a new one. Without it, the one stored now is used, which is only right for a caller that has not just
     * synced.
     */
    internal suspend fun purgeHistory(account: LibraryAccount): PurgeOutcome {
        val stored = lastSuccess(account)
        return purgeHistory(account, stored)
    }

    /** As [purgeHistory] for [account], with the guard's [reference] given (null: nothing to compare with). */
    internal suspend fun purgeHistory(
        account: LibraryAccount,
        reference: Instant?,
    ): PurgeOutcome {
        val (server, user) = account.keys()
        val referenceMs = reference?.toEpochMilliseconds()
        return transactions.purgeHistory(server, user, clock.now().toEpochMilliseconds(), retention.millis, referenceMs)
    }

    // Reads for the tests and diagnostics.

    internal suspend fun stored(account: LibraryAccount): List<StoredItem> {
        val (server, user) = account.keys()
        return dao.allRows(server, user).map {
            StoredItem(it.toItem(), ItemId(it.item.libraryId), it.item.deletedAtMs?.let(Instant::fromEpochMilliseconds))
        }
    }

    /** The `secure_delete` setting of the open database: 1 when freed pages are overwritten. */
    internal suspend fun pragmaSecureDelete(): Int =
        db.useReaderConnection { connection ->
            connection.usePrepared("PRAGMA secure_delete") { statement ->
                check(statement.step()) { "PRAGMA secure_delete returned no row" }
                statement.getLong(0).toInt()
            }
        }

    internal suspend fun rowCounts() =
        RowCounts(
            items = dao.countItems(),
            genres = dao.countGenres(),
            tags = dao.countTags(),
            studios = dao.countStudios(),
            libraries = dao.countLibraries(),
            checkpoints = dao.countCheckpoints(),
            states = dao.countStates(),
            versions = history.countVersions(),
            series = seriesDao.count(),
            seriesVersions = seriesDao.countVersions(),
            collections = collectionDao.count(),
            collectionVersions = collectionDao.countVersions(),
            collectionMembers = collectionDao.countMembers(),
            collectionSyncs = collectionDao.countSyncRows(),
        )

    /** The account's item rows as they are on disk (the current versions), by item ID. */
    internal suspend fun currentRows(account: LibraryAccount): List<ItemEntity> {
        val (server, user) = account.keys()
        return dao.allItems(server, user)
    }

    /** The account's kept earlier versions, by item ID and then time. */
    internal suspend fun versions(account: LibraryAccount): List<ItemVersionEntity> {
        val (server, user) = account.keys()
        return history.allVersions(server, user)
    }

    /**
     * Writes one collection and its complete member list in one transaction (see
     * [CacheTransactionDao.writeCollection]). [members] hold only the kinds the cache stores (movie, episode, series,
     * season): the sync skips nested collections and other types before it gets here.
     */
    internal suspend fun writeCollection(
        account: LibraryAccount,
        id: ItemId,
        name: String,
        members: List<CollectionMember>,
    ): CollectionWrite {
        val (server, user) = account.keys()
        val rows = members.map { it.toEntity(server, user, id.value) }
        return transactions.writeCollection(
            server,
            user,
            id.value,
            name,
            rows,
            clock.now().toEpochMilliseconds(),
            faultBetweenCollectionAndMembers,
        )
    }

    /** Marks the collections [ids] deleted as of now; an existing tombstone keeps its time, and no version is added. */
    internal suspend fun tombstoneCollections(
        account: LibraryAccount,
        ids: Collection<ItemId>,
    ) {
        val (server, user) = account.keys()
        transactions.tombstoneCollections(server, user, ids.map { it.value }, clock.now().toEpochMilliseconds())
    }

    /** The collections this TV held before [cutoff], with their members, in ID order (see [CollectionDao]). */
    internal suspend fun heldCollections(
        account: LibraryAccount,
        cutoff: Instant,
    ): List<HeldCollection> {
        val (server, user) = account.keys()
        return transactions.heldCollections(server, user, cutoff.toEpochMilliseconds())
    }

    /** The IDs of the account's live collections, in order: what the collection pass compares the server's list to. */
    internal suspend fun liveCollectionIds(account: LibraryAccount): List<ItemId> {
        val (server, user) = account.keys()
        return collectionDao.liveIds(server, user).map(::ItemId)
    }

    /** The IDs of the account's tombstoned collections: deleted on the server, not yet purged. */
    internal suspend fun tombstonedCollectionIds(account: LibraryAccount): List<ItemId> {
        val (server, user) = account.keys()
        return collectionDao.tombstonedIds(server, user).map(::ItemId)
    }

    /** How many members of [collection] are current. */
    internal suspend fun currentMemberCount(
        account: LibraryAccount,
        collection: ItemId,
    ): Int {
        val (server, user) = account.keys()
        return collectionDao.currentMemberCount(server, user, collection.value)
    }

    /** Where the account's collection pass stands, or null if none has completed. */
    internal suspend fun collectionSync(account: LibraryAccount): CollectionSyncEntity? {
        val (server, user) = account.keys()
        return collectionDao.syncRow(server, user)
    }

    /** True once a full collection pass (a re-list) has succeeded for the account since the cache was built. */
    internal suspend fun collectionsSynced(account: LibraryAccount): Boolean {
        val row = collectionSync(account)
        return row?.lastRelistMs != null
    }

    // The failed-pass count (see [SyncMetaEntity]). Neither call throws for an account that is no longer the signed-in
    // one: the pass's own writes will say so, and a count is not worth failing a pass over.
    internal suspend fun beginCollectionPass(account: LibraryAccount) {
        val (server, user) = account.keys()
        try {
            transactions.beginCollectionPass(server, user)
        } catch (_: AccountNotActiveException) {
            // Not signed in any more; nothing to count for.
        }
    }

    internal suspend fun abandonCollectionPass(account: LibraryAccount) {
        val (server, user) = account.keys()
        try {
            transactions.abandonCollectionPass(server, user)
        } catch (_: AccountNotActiveException) {
            // Not signed in any more; nothing to count for.
        }
    }

    override suspend fun failedCollectionPasses(account: LibraryAccount): Int {
        val (server, user) = account.keys()
        return dao.metaRow(server, user)?.failedPasses ?: 0
    }

    /** Records a collection pass that began at [started] and completed (see [CacheTransactionDao]). */
    internal suspend fun recordCollectionPass(
        account: LibraryAccount,
        started: Instant,
        relisted: Boolean,
    ) {
        val (server, user) = account.keys()
        transactions.recordCollectionPass(server, user, started.toEpochMilliseconds(), relisted)
    }

    /** The account's collection rows as they are on disk (the current lifecycle), by collection ID. */
    internal suspend fun collections(account: LibraryAccount): List<CollectionEntity> {
        val (server, user) = account.keys()
        return collectionDao.all(server, user)
    }

    /** The account's kept earlier collection lifecycles, by collection ID and then time. */
    internal suspend fun collectionVersions(account: LibraryAccount): List<CollectionVersionEntity> {
        val (server, user) = account.keys()
        return collectionDao.allVersions(server, user)
    }

    /** The account's member rows, current and superseded, by collection, member and then time. */
    internal suspend fun collectionMembers(account: LibraryAccount): List<CollectionMemberEntity> {
        val (server, user) = account.keys()
        return collectionDao.allMembers(server, user)
    }

    /** The account's series rows as they are on disk (the current versions), by series ID. */
    internal suspend fun currentSeries(account: LibraryAccount): List<SeriesEntity> {
        val (server, user) = account.keys()
        return seriesDao.all(server, user)
    }

    /** The account's kept earlier series versions, by series ID and then time. */
    internal suspend fun seriesVersions(account: LibraryAccount): List<SeriesVersionEntity> {
        val (server, user) = account.keys()
        return seriesDao.allVersions(server, user)
    }

    /** Closes the database. The app keeps it open for its lifetime; tests close theirs. */
    fun close() = db.close()

    private fun LibraryAccount.keys() = server.value to user.value

    private fun StatusRow.toStatus(): LibraryStatus {
        val success = lastSuccessMs?.let(Instant::fromEpochMilliseconds)
        val state =
            when (state) {
                AccountStateEntity.STATE_FIRST_RUNNING -> {
                    SyncState.FirstSyncRunning(copied, total)
                }

                AccountStateEntity.STATE_FAILED -> {
                    SyncState.LastSyncFailed(SyncFailure.entries.getOrElse(failure ?: -1) { SyncFailure.Server })
                }

                else -> {
                    SyncState.Idle
                }
            }
        return LibraryStatus(state, success)
    }

    companion object {
        /** The database file's name, with no path: Android puts it in the app's `databases` directory. */
        const val DATABASE_NAME = "library.db"

        /** The as-of read walks the account's items this many at a time (spec 09, Vimes M2). */
        private const val AS_OF_CHUNK = 500

        /** The saved library in the app's database file, over the platform SQLite. */
        fun create(
            context: Context,
            clock: Clock = Clock.System,
            retention: HistoryRetention = HistoryRetention(),
            collectionRelist: CollectionRelist = CollectionRelist(),
        ): RoomLibraryRepository {
            val builder = LibraryDatabases.builder(context.applicationContext, DATABASE_NAME, clock)
            // Sign-in and sign-out cancel the unique sync work through WorkManager, asked for only when they do.
            val sync = AccountChangeSyncControl(WorkManagerEnqueuer(context))
            return RoomLibraryRepository(builder.build(), clock, sync, retention, collectionRelist)
        }

        /** For tests: a database from a builder the test configured (a JVM driver, a file or memory). */
        internal fun testing(
            builder: RoomDatabase.Builder<LibraryDatabase>,
            clock: Clock,
            sync: SyncControl = SyncControl.None,
            retention: HistoryRetention = HistoryRetention(),
        ): RoomLibraryRepository = RoomLibraryRepository(builder.build(), clock, sync, retention)
    }
}

// The smallest whole millisecond that is not before this instant, so "created before this" is exact on ms columns.
private fun Instant.ceilingMillis(): Long {
    val floor = toEpochMilliseconds()
    return if (Instant.fromEpochMilliseconds(floor) < this) floor + 1 else floor
}

private fun MediaItem.toRows(
    server: String,
    user: String,
    library: String,
) = ItemRows(
    item =
        ItemEntity(
            serverId = server,
            userId = user,
            itemId = id.value,
            libraryId = library,
            kind = kind.name,
            name = name,
            parentId = parentId?.value,
            seriesId = seriesId?.value,
            seasonId = seasonId?.value,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            runtimeMs = runtime?.inWholeMilliseconds,
            createdAtMs = dateCreated?.toEpochMilliseconds(),
            premiereDate = premiereDate,
            productionYear = productionYear,
            officialRating = officialRating,
            deletedAtMs = null,
            // The DAO stamps both when it writes the row (spec 09, Vimes H1).
            seenFromMs = 0,
            firstSeenMs = 0,
        ),
    genres = genres.mapIndexed { index, value -> GenreEntity(server, user, id.value, index, value) },
    tags = tags.mapIndexed { index, value -> TagEntity(server, user, id.value, index, value) },
    studios = studios.mapIndexed { index, value -> StudioEntity(server, user, id.value, index, value) },
)

private fun CollectionMember.toEntity(
    server: String,
    user: String,
    collection: String,
) = CollectionMemberEntity(
    serverId = server,
    userId = user,
    collectionId = collection,
    memberId = id.value,
    memberKind = requireNotNull(storedMemberKind(kind)) { "A $kind member is not stored" },
    // The DAO stamps both when it writes the row.
    seenFromMs = 0,
    supersededAtMs = null,
)

private fun SeriesInfo.toEntity(
    server: String,
    user: String,
    library: String,
) = SeriesEntity(
    serverId = server,
    userId = user,
    seriesId = id.value,
    libraryId = library,
    name = name,
    officialRating = officialRating,
    genres = VersionCodec.encode(genres),
    tags = VersionCodec.encode(tags),
    studios = VersionCodec.encode(studios),
    deletedAtMs = null,
    // The DAO stamps both when it writes the row.
    seenFromMs = 0,
    firstSeenMs = 0,
)

private fun ItemRows.toItem() =
    MediaItem(
        id = ItemId(item.itemId),
        name = item.name,
        kind = ItemKind.entries.firstOrNull { it.name == item.kind } ?: ItemKind.Other,
        runtime = item.runtimeMs?.milliseconds,
        parentId = item.parentId?.let(::ItemId),
        dateCreated = item.createdAtMs?.let(Instant::fromEpochMilliseconds),
        premiereDate = item.premiereDate,
        productionYear = item.productionYear,
        officialRating = item.officialRating,
        genres = genres.map { it.name },
        tags = tags.map { it.name },
        studios = studios.map { it.name },
        seriesId = item.seriesId?.let(::ItemId),
        seasonId = item.seasonId?.let(::ItemId),
        seasonNumber = item.seasonNumber,
        episodeNumber = item.episodeNumber,
    )
