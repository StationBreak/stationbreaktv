// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import androidx.room.Dao
import androidx.room.Transaction
import tv.stationbreak.core.model.CanonicalId
import java.util.TreeSet

/**
 * How a guarded page write ended (spec 26, schema 11): [Written], with what it wrote, or refused with nothing written,
 * because the library row did not take a write under that stamp ([StampMismatch]) or a rewalk's write met a running
 * first sync ([FirstSyncRunning]).
 */
internal sealed interface PageResult {
    /** The page was written: the [items] and the [series] it held, each counted as [PageWrite]. */
    data class Written(
        val items: PageWrite,
        val series: PageWrite,
    ) : PageResult

    /**
     * Nothing was written: the library has no row, is held, or has another stamp than the write's (a stamped write
     * needs that stamp; an unstamped one needs none).
     */
    data object StampMismatch : PageResult

    /** Nothing was written: a rewalk's write found the account running a first sync, which takes the library. */
    data object FirstSyncRunning : PageResult
}

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
     *
     * The content generation moves only for a change to a field the scheduler or the rules read (spec 26): for an
     * item, only in a library that is not being added ([itemsMoveTheDay]), so the walk of an add moves nothing; for a
     * series, in any library, as episodes of other libraries read a series row by its id. A provider-id-only change
     * never moves it.
     *
     * The write is guarded by [libraryId]'s row, read inside this transaction (spec 26, schema 11): it is accepted only
     * when the row exists, has no hold, and has the add stamp [stamp] (a stamped write, the add's walk) or none (an
     * unstamped one). Otherwise nothing is written and it returns [PageResult.StampMismatch]: so a write can never land
     * in a library that was dropped, held or re-stamped since its walk began, nor reach a foreign-key throw for a row
     * that is gone. A [rewalk] write also refuses, with [PageResult.FirstSyncRunning], while the account runs a first
     * sync. Neither refusal moves the generation.
     *
     * Who owns an item two libraries list ([routePage], spec 26, schema 11): the first writer. A chosen writer takes an
     * item from an owner being added, and any writer takes a tombstone; each is a versioned move. Otherwise the owner
     * stays and the writer gets an `item_also_listed` row, while the fields are still the writer's, versioned under
     * the owner. The rows change in this transaction, after the guard; a change to them alone moves nothing. An add's
     * series write (a [stamp]) moves the generation only when an episode outside the adds names the series (D8).
     */
    @Transaction
    @Suppress("LongParameterList") // One cohesive call: the library and its guard, the page, the time and the seams.
    open suspend fun writePage(
        server: String,
        user: String,
        libraryId: String,
        stamp: Long?,
        rewalk: Boolean,
        checkpoint: CheckpointEntity?,
        items: Map<String, ItemRows>,
        seriesRows: Map<String, SeriesEntity>,
        nowMs: Long,
        betweenItemsAndHistory: (() -> Unit)?,
        betweenItemsAndCheckpoint: (() -> Unit)?,
    ): PageResult {
        library.requireActive(server, user)
        refusal(server, user, libraryId, stamp, rewalk)?.let { return it }
        val existing =
            items.keys
                .chunked(LibraryDao.MAX_VARIABLES)
                .flatMap { library.itemsByIds(server, user, it) }
                .associateBy { it.itemId }
        // Read once, inside this transaction: the owners' classes and the adds (spec 26, schema 11).
        val libs = library.libraries(server, user)
        val stamped = libs.filter { it.addStamp != null }.mapTo(HashSet()) { it.libraryId }
        val addingOwners = addingLibraries(libs)
        val routed = routePage(server, user, libraryId, stamp, items, existing, addingOwners)
        // The old genres, tags and studios are read here, inside the transaction and before they are deleted below.
        val old = library.sideNames(server, user, existing.keys)
        val plan = history.planPage(routed.items, existing, old, nowMs)
        library.insertItems(plan.toInsert)
        library.updateItems(plan.toUpdate)
        writeListed(server, user, libraryId, routed)
        // Each side table is rewritten only for the items whose list differs from the one held (a new item has none),
        // so a page that changes provider ids alone, as the backfill's does, leaves every side row as it is.
        val genreIds = ArrayList<String>()
        val tagIds = ArrayList<String>()
        val studioIds = ArrayList<String>()
        for (row in plan.toInsert + plan.toUpdate) {
            val id = row.itemId
            val incoming = routed.items.getValue(id)
            if (incoming.genres.map { it.name } != old.genres[id].orEmpty()) genreIds += id
            if (incoming.tags.map { it.name } != old.tags[id].orEmpty()) tagIds += id
            if (incoming.studios.map { it.name } != old.studios[id].orEmpty()) studioIds += id
        }
        for (chunk in genreIds.chunked(LibraryDao.MAX_VARIABLES)) library.deleteGenres(server, user, chunk)
        for (chunk in tagIds.chunked(LibraryDao.MAX_VARIABLES)) library.deleteTags(server, user, chunk)
        for (chunk in studioIds.chunked(LibraryDao.MAX_VARIABLES)) library.deleteStudios(server, user, chunk)
        library.insertGenres(genreIds.flatMap { items.getValue(it).genres })
        library.insertTags(tagIds.flatMap { items.getValue(it).tags })
        library.insertStudios(studioIds.flatMap { items.getValue(it).studios })
        betweenItemsAndHistory?.invoke()
        history.insertVersions(plan.versions)
        // The adds were read inside this transaction, so a selection change cannot slip between the read and the bump.
        val seriesWrite = writeSeries(server, user, seriesRows, nowMs, stamp, addingOwners)
        // Inside this transaction, so a write that rolls back leaves the generation as it was.
        val moves = itemsMoveTheDay(plan, routed.items, stamped) || seriesWrite.movesTheDay
        if (moves) library.bumpGeneration(server, user)
        betweenItemsAndCheckpoint?.invoke()
        checkpoint?.let { library.saveCheckpoint(it) }
        return PageResult.Written(PageWrite(plan.toInsert.size, plan.toUpdate.size, plan.unchanged), seriesWrite.write)
    }

    /**
     * Why the library row refuses a write to [libraryId] under [stamp] (see [writePage]), read inside the write's
     * transaction, or null when it takes it. Nothing has been written when this is asked.
     */
    private suspend fun refusal(
        server: String,
        user: String,
        libraryId: String,
        stamp: Long?,
        rewalk: Boolean,
    ): PageResult? {
        if (rewalk && library.state(server, user)?.state == AccountStateEntity.STATE_FIRST_RUNNING) {
            return PageResult.FirstSyncRunning
        }
        val row = library.libraryRow(server, user, libraryId)
        // A stamped write needs that stamp, an unstamped one none; neither goes to a held row, or to one that is gone.
        val accepted = row != null && row.heldUntilMs == null && row.addStamp == stamp
        return if (accepted) null else PageResult.StampMismatch
    }

    // The page's also-listed changes: the writer's row goes for what it now owns, and the new rows go in.
    private suspend fun writeListed(
        server: String,
        user: String,
        libraryId: String,
        routed: Routed,
    ) {
        for (chunk in routed.nowOwned.chunked(LibraryDao.MAX_VARIABLES)) {
            library.deleteAlsoListedIn(server, user, libraryId, chunk)
        }
        library.insertAlsoListed(routed.listed)
    }

    /**
     * True when the page changed a field the scheduler or the rules read (a new row, or one that took a version: an
     * edit to a versioned field or a revival) in a library that is not being added, before or after the write (spec
     * 26). A change to an unversioned field alone (the parent, the provider ids) is stored and moves nothing.
     */
    private fun itemsMoveTheDay(
        plan: PagePlan,
        items: Map<String, ItemRows>,
        adding: Set<String>,
    ): Boolean {
        var moves = false
        for (row in plan.toInsert) if (row.libraryId !in adding) moves = true
        for (version in plan.versions) {
            if (version.libraryId !in adding || items.getValue(version.itemId).item.libraryId !in adding) moves = true
        }
        return moves
    }

    private class SeriesWrite(
        val write: PageWrite,
        val movesTheDay: Boolean,
    )

    @Suppress("LongParameterList") // The account, the rows, the time, and the stamp with the adds for D8.
    private suspend fun writeSeries(
        server: String,
        user: String,
        rows: Map<String, SeriesEntity>,
        nowMs: Long,
        stamp: Long?,
        adding: Set<String>,
    ): SeriesWrite {
        val existing =
            rows.keys
                .chunked(LibraryDao.MAX_VARIABLES)
                .flatMap { series.byIds(server, user, it) }
                .associateBy { it.seriesId }
        val plan = series.planPage(rows, existing, nowMs)
        series.insert(plan.toInsert)
        series.update(plan.toUpdate)
        series.insertVersions(plan.versions)
        // A new or versioned series moves the day, whatever library holds its row: an episode of any library reads it
        // by its id, so an add is not exempt here (whole-part review, data MED-1). A library-only or provider-id-only
        // change takes no version and moves nothing. An add's write (D8) moves it only when an episode outside the
        // adds names the series: no day reads a series that only an add's episodes name.
        val changed = plan.toInsert.map { it.seriesId } + plan.versions.map { it.seriesId }
        val moves =
            when {
                changed.isEmpty() -> false
                stamp == null -> true
                else -> namedOutside(server, user, changed, adding)
            }
        return SeriesWrite(PageWrite(plan.toInsert.size, plan.toUpdate.size, plan.unchanged), moves)
    }

    // D8: whether an episode of a library outside [adding] (held ones included) names one of [seriesIds], under any
    // of the spellings it may be stored as (idSpellings), bound in chunks that keep the statement under the limit.
    private suspend fun namedOutside(
        server: String,
        user: String,
        seriesIds: List<String>,
        adding: Set<String>,
    ): Boolean {
        val spellings = seriesIds.flatMap { idSpellings(it) }.distinct()
        val outside = adding.toList()
        val size = (LibraryDao.MAX_VARIABLES - outside.size).coerceAtLeast(MAX_SPELLINGS)
        for (chunk in spellings.chunked(size)) if (library.seriesNamedOutside(server, user, chunk, outside)) return true
        return false
    }

    /**
     * The one writer of the account's library set (spec 26): [LibraryDao.applySelectionRows] keeps, holds, stamps and
     * inserts the library rows (see there for every rule, the content generation's included). Then, before the
     * libraries that go are deleted, each item one of them owns that a staying library lists moves to that library
     * (spec 26, schema 11), live or tombstoned: a chosen one first, then one being added, then a held one, then the
     * smallest id. Each move is versioned, deletes the target's also-listed row and lowers the target's coverage to
     * the deleted library's. Only then are the shared series re-homed, so a moved episode keeps its series, and the
     * libraries deleted, with what is left under them. One transaction.
     */
    @Transaction
    @Suppress("LongParameterList") // The selection, its stamps and holds, the clock and its guard.
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
        val change =
            library.applySelectionRows(serverId, userId, keep, addStamps, holds, nowMs, keepAdding, holdClockGuardMs)
                ?: return
        if (change.deleted.isEmpty()) return
        moveBeforeDelete(serverId, userId, change, nowMs)
        library.deleteLeavers(serverId, userId, change.deleted, change.staying)
    }

    // The delete-time moves (spec 26, schema 11): each item a deleted library owns goes to its best staying lister.
    private suspend fun moveBeforeDelete(
        serverId: String,
        userId: String,
        change: SelectionChange,
        nowMs: Long,
    ) {
        val deleted = change.deleted.toHashSet()
        val listers = ArrayList<AlsoLister>()
        for (chunk in change.deleted.chunked(LibraryDao.MAX_VARIABLES)) {
            for (row in library.listersOfOwned(serverId, userId, chunk)) if (row.libraryId !in deleted) listers += row
        }
        val moved = moveItems(library, history, serverId, userId, bestTargets(listers), nowMs, ownedBy = null)
        // Each target is covered from the earliest of its own coverage and that of every library it took items from.
        val lowered = HashSet<Pair<String, String>>()
        for ((from, to) in moved) {
            val fromMs = change.coveredFrom[from] ?: continue
            if (lowered.add(from to to)) library.lowerCoveredFrom(serverId, userId, to, fromMs)
        }
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
        if (reviveItemsIn(serverId, userId, ids, nowMs, ownedBy = null) > 0) library.bumpGeneration(serverId, userId)
    }

    // The tombstones among [ids] revived at [nowMs]; with [ownedBy], only those that library owns (a reconcile revives
    // its own tombstones: one another library took over meanwhile is that library's, and stays as it is).
    private suspend fun reviveItemsIn(
        serverId: String,
        userId: String,
        ids: List<String>,
        nowMs: Long,
        ownedBy: String?,
    ): Int {
        var revived = 0
        for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES)) {
            val rows =
                library.itemsByIds(serverId, userId, chunk).filter {
                    it.deletedAtMs != null && (ownedBy == null || it.libraryId == ownedBy)
                }
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
     * One reconcile's [decisions] for [libraryId], written together after the account check (spec 10, Vimes M3; spec
     * 26, schema 11), only when the library's row takes them: it exists, has no hold and no stamp, and is not marked
     * for a rewalk (a marked library is never reconciled). Otherwise nothing is written and it is
     * [ReconcileApplied.Refused]. In order:
     * 1. the removal half: the library's rows for the items it lists no more go;
     * 2. each item it owns that its listing dropped moves to the best library that lists it (versioned, that row goes,
     *    and its collection rows stay), or, with none, is tombstoned at [nowMs], only while the library still owns it;
     *    the series that are gone are tombstoned, and the member rows of the items left as its tombstones, and of those
     *    series, end (ADR-0015);
     * 3. [betweenTombstonesAndRevives] (a test seam; null in the app), then its own tombstones that came back (only
     *    while it still owns them) and the series are revived, each with its version;
     * 4. a listed tombstone another library owns is revived into this one (versioned), and its row here goes;
     * 5. the add half: a row for each listed item another library owns, the owner checked in the write.
     * The content generation moves once when an item or series row, or a member row, changed; a change to the
     * also-listed rows alone moves nothing.
     */
    @Transaction
    @Suppress("LongParameterList") // One cohesive call: the library, its decisions, the time and the test seam.
    open suspend fun reconcileWrite(
        serverId: String,
        userId: String,
        libraryId: String,
        decisions: ReconcileDecisions,
        nowMs: Long,
        betweenTombstonesAndRevives: (() -> Unit)?,
    ): ReconcileApplied {
        library.requireActive(serverId, userId)
        if (!reconcilable(library.libraryRow(serverId, userId, libraryId))) return ReconcileApplied.Refused
        for (chunk in decisions.alsoDrop.chunked(LibraryDao.MAX_VARIABLES)) {
            library.deleteAlsoListedIn(serverId, userId, libraryId, chunk)
        }
        val dropped = dropOwned(library, history, serverId, userId, libraryId, decisions.itemsGone, nowMs)
        val seriesGone = decisions.seriesGone
        var changed = dropped.tombstoned + dropped.moved + tombstoneSeriesIn(serverId, userId, seriesGone, nowMs)
        // Before the seam, so a fault there rolls back the tombstones and the cascade together.
        changed += supersedeMembersIn(serverId, userId, dropped.ended + seriesGone, nowMs)
        betweenTombstonesAndRevives?.invoke()
        val revived = reviveItemsIn(serverId, userId, decisions.itemsReturning, nowMs, ownedBy = libraryId)
        changed += revived + reviveSeriesIn(serverId, userId, decisions.seriesReturning, nowMs)
        val takenOver = takeOverIn(library, history, serverId, userId, libraryId, decisions.foreignListed, nowMs)
        for (chunk in decisions.alsoAdd.chunked(LibraryDao.MAX_VARIABLES)) {
            library.addAlsoListed(serverId, userId, libraryId, chunk)
        }
        // Only rows that changed count: a decision already in place (another pass got there first) moves nothing.
        if (changed + takenOver > 0) library.bumpGeneration(serverId, userId)
        return ReconcileApplied.Written(dropped.tombstoned, dropped.moved, revived, takenOver)
    }

    /**
     * The rows of chosen libraries the server no longer has (#495), written in one transaction after the account
     * check: the items each library of [items] still owns are tombstoned at [nowMs], and the [series], each with the
     * end of its member rows (ADR-0015). Every library must take the write as a reconcile does (chosen, unmarked);
     * otherwise nothing is written and it is [ReconcileApplied.Refused]. The generation moves once, on a change.
     */
    @Transaction
    open suspend fun removedWrite(
        serverId: String,
        userId: String,
        items: Map<String, List<String>>,
        series: List<String>,
        nowMs: Long,
    ): ReconcileApplied {
        library.requireActive(serverId, userId)
        for (libraryId in items.keys) {
            if (!reconcilable(library.libraryRow(serverId, userId, libraryId))) return ReconcileApplied.Refused
        }
        var changed = 0
        var tombstoned = 0
        val ended = ArrayList<String>()
        for ((libraryId, ids) in items) {
            for (chunk in ids.chunked(LibraryDao.MAX_VARIABLES - 2)) {
                val rows = library.itemsByIds(serverId, userId, chunk)
                for (row in rows) if (row.libraryId == libraryId) ended += row.itemId
                tombstoned += library.tombstoneOwned(serverId, userId, libraryId, chunk, nowMs)
            }
        }
        changed += tombstoned + tombstoneSeriesIn(serverId, userId, series, nowMs)
        changed += supersedeMembersIn(serverId, userId, ended + series, nowMs)
        if (changed > 0) library.bumpGeneration(serverId, userId)
        return ReconcileApplied.Written(tombstoned, 0, 0, 0)
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
     * tombstoned collection that is written is revived at that same stamp. A new name is a change (spec 23, D8): it
     * takes a stamp and a version with the old name. A read that changes nothing writes nothing. [members] are the
     * whole list, already filtered to the kinds the cache stores.
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
            if (row.kind == EPISODE_KIND && seriesId != null) seriesIds.addAll(idSpellings(seriesId))
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

    /**
     * [reIdRows] in one read transaction (spec 26): the tombstones, the series and the live candidates are read from
     * one state. [sinceMs] is the oldest tombstone that counts. It returns the rows only: the caller runs the match
     * ([ReIdRows.match]) after the transaction, so the match never holds up a sync write or the repairer.
     */
    @Transaction
    @Suppress("LongParameterList") // The account, what to find, the retention cut and the test seam.
    open suspend fun successors(
        server: String,
        user: String,
        libraries: Set<String>,
        gone: Set<CanonicalId>,
        sinceMs: Long,
        trace: ((String) -> Unit)? = null,
    ): ReIdRows = reIdRows(library, series, server, user, libraries, gone, sinceMs, trace)

    private companion object {
        /** Collections the as-of read takes at a time: each has a member list, so a chunk is small. */
        const val COLLECTION_CHUNK = 100

        /** The kind column of an episode, as `ScheduleItemKind` names it. */
        const val EPISODE_KIND = "Episode"
    }
}
