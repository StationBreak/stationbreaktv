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
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.LibraryStatus
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncState
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds
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
) {
    companion object {
        val NONE = RowCounts(0, 0, 0, 0, 0, 0)
    }
}

/**
 * The saved library in Room (spec 06, Unit 2): the [LibraryRepository] port for the scheduler and the app, and the
 * internal calls the sync (3.0 and 4.0) uses to fill and tidy it. Every row is keyed by server and user, a page and its
 * checkpoint are written in one transaction, a deleted item is a tombstone for 30 days, and nothing here ever holds a
 * token. The database is a cache: it can be deleted and fetched again.
 */
@Suppress("TooManyFunctions") // The port plus the sync's side: each is one call into a DAO transaction.
class RoomLibraryRepository internal constructor(
    private val db: LibraryDatabase,
    private val clock: Clock,
    private val sync: SyncControl = SyncControl.None,
) : LibraryRepository {
    internal val dao = db.dao()

    /**
     * Test seam: runs inside [writePage]'s transaction after the items are written and before the checkpoint is, so a
     * test can make the write die in between and prove neither is kept. Null in the app.
     */
    internal var faultBetweenItemsAndCheckpoint: (() -> Unit)? = null

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
        // Unticked libraries go, with their items, genres, tags and checkpoint (foreign keys cascade).
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
        dao.activate(server, user)
    }

    override suspend fun forget(account: LibraryAccount) {
        val (server, user) = account.keys()
        sync.cancelFirstSync()
        dao.forget(server, user)
    }

    // The sync's side.

    /**
     * Saves one [page][items] of [library] and the checkpoint that goes with it, in one transaction: after a crash both
     * are there or neither is. Writing the same page again changes nothing (an item that is unchanged is not
     * rewritten; a tombstoned one that came back is revived), so a resume can replay the last page.
     * [library] must have been chosen with [select] first.
     */
    @Suppress("LongParameterList") // One cohesive call: the page, and the checkpoint that goes with it.
    internal suspend fun writePage(
        account: LibraryAccount,
        library: ItemId,
        items: List<MediaItem>,
        nextStartIndex: Int,
        totalCount: Int?,
        run: Long,
    ): PageWrite {
        val (server, user) = account.keys()
        val checkpoint = CheckpointEntity(server, user, library.value, nextStartIndex, totalCount, run)
        val rows = rowsOf(items, server, user, library)
        return dao.writePage(server, user, checkpoint, rows, faultBetweenItemsAndCheckpoint)
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
        return dao.writePage(server, user, null, rowsOf(items, server, user, library), faultBetweenItemsAndCheckpoint)
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

    /** Marks [ids] deleted as of now. An id that is already a tombstone keeps its first time. */
    internal suspend fun tombstone(
        account: LibraryAccount,
        ids: Collection<ItemId>,
    ) {
        val (server, user) = account.keys()
        dao.tombstoneAll(server, user, ids.map { it.value }, clock.now().toEpochMilliseconds())
    }

    /** Clears the tombstone of [ids]: they are back in snapshots. */
    internal suspend fun revive(
        account: LibraryAccount,
        ids: Collection<ItemId>,
    ) {
        val (server, user) = account.keys()
        dao.reviveAll(server, user, ids.map { it.value })
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

    /** Deletes every tombstone more than [TOMBSTONE_LIFETIME_DAYS] days old, for every account. Returns how many. */
    internal suspend fun purgeTombstones(): Int {
        val cutoff = (clock.now() - TOMBSTONE_LIFETIME_DAYS.days).toEpochMilliseconds()
        return dao.purgeTombstonesBefore(cutoff)
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
            libraries = dao.countLibraries(),
            checkpoints = dao.countCheckpoints(),
            states = dao.countStates(),
        )

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

        /** Tombstones older than this are purged (T11). */
        const val TOMBSTONE_LIFETIME_DAYS = 30

        /** The saved library in the app's database file, over the platform SQLite. */
        fun create(
            context: Context,
            clock: Clock = Clock.System,
        ): RoomLibraryRepository {
            val builder = LibraryDatabases.builder(context.applicationContext, DATABASE_NAME)
            // Sign-in and sign-out cancel the unique first-sync work through WorkManager, asked for only when they do.
            val sync = WorkManagerSyncControl(WorkManagerEnqueuer(context))
            return RoomLibraryRepository(builder.build(), clock, sync)
        }

        /** For tests: a database from a builder the test configured (a JVM driver, a file or memory). */
        internal fun testing(
            builder: RoomDatabase.Builder<LibraryDatabase>,
            clock: Clock,
            sync: SyncControl = SyncControl.None,
        ): RoomLibraryRepository = RoomLibraryRepository(builder.build(), clock, sync)
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
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            runtimeNanos = runtime?.inWholeNanoseconds,
            createdAtMs = dateCreated?.toEpochMilliseconds(),
            productionYear = productionYear,
            officialRating = officialRating,
            deletedAtMs = null,
        ),
    genres = genres.mapIndexed { index, value -> GenreEntity(server, user, id.value, index, value) },
    tags = tags.mapIndexed { index, value -> TagEntity(server, user, id.value, index, value) },
)

private fun ItemRows.toItem() =
    MediaItem(
        id = ItemId(item.itemId),
        name = item.name,
        kind = ItemKind.entries.firstOrNull { it.name == item.kind } ?: ItemKind.Other,
        runtime = item.runtimeNanos?.nanoseconds,
        parentId = item.parentId?.let(::ItemId),
        dateCreated = item.createdAtMs?.let(Instant::fromEpochMilliseconds),
        productionYear = item.productionYear,
        officialRating = item.officialRating,
        genres = genres.map { it.name },
        tags = tags.map { it.name },
        seriesId = item.seriesId?.let(::ItemId),
        seasonNumber = item.seasonNumber,
        episodeNumber = item.episodeNumber,
    )
