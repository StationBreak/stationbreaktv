// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

// Who owns an item that more than one library lists, and the moves between owners (spec 26, schema 11). The rules are
// plain functions here; the transactions of CacheTransactionDao call them, so every read and write stays inside one.
//
// For each item row I with owner O = item.libraryId: no (I, O) row exists in item_also_listed; every row (I, L) has
// its item and its library (the foreign keys) and L != O; and for any other library L, (I, L) exists when L's latest
// observation of I was "listed" (an accepted write of I by L, or a complete ID listing of L that held it). The owner
// changes only through a tombstone takeover, a chosen writer taking from an owner being added, a delete-time move or a
// reconcile's owner-drop move, each versioned. A library marked for a rewalk is chosen for all of these.

/**
 * One library's reconcile decisions (spec 26, schema 11): the items it owns that its listing dropped ([itemsGone]),
 * its tombstones it lists again ([itemsReturning]), the same for its series, the items another library owns that it
 * lists ([alsoAdd], the add half) and the rows of items it lists no more ([alsoDrop], the removal half). [foreignListed]
 * are the listed items another library holds, a tombstone of which it takes over.
 */
@Suppress("LongParameterList") // One list per kind of decision.
internal class ReconcileDecisions(
    val itemsGone: List<String> = emptyList(),
    val itemsReturning: List<String> = emptyList(),
    val seriesGone: List<String> = emptyList(),
    val seriesReturning: List<String> = emptyList(),
    val alsoAdd: List<String> = emptyList(),
    val alsoDrop: List<String> = emptyList(),
    val foreignListed: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() =
            itemsGone.isEmpty() && itemsReturning.isEmpty() && seriesGone.isEmpty() && seriesReturning.isEmpty() &&
                alsoAdd.isEmpty() && alsoDrop.isEmpty() && foreignListed.isEmpty()
}

/** How a reconcile write ended (spec 26, schema 11). */
internal sealed interface ReconcileApplied {
    /**
     * Written: [tombstoned] items tombstoned, [moved] to a library that lists them, [revived] (its own tombstones)
     * and [takenOver] (another library's tombstones it lists), counted as the rows that changed.
     */
    data class Written(
        val tombstoned: Int,
        val moved: Int,
        val revived: Int,
        val takenOver: Int,
    ) : ReconcileApplied

    /** Nothing was written: the library has no row, is held, is being added or is marked for a rewalk. */
    data object Refused : ReconcileApplied
}

/**
 * One also-listed row with its library's class (spec 26, schema 11): chosen (no stamp, no hold), being added
 * ([addStamp], no hold) or held ([heldUntilMs]).
 */
internal data class AlsoLister(
    val itemId: String,
    val libraryId: String,
    val addStamp: Long?,
    val heldUntilMs: Long?,
) {
    /** Where this lister ranks as a target of a move: chosen, then being added, then held. Lower is first. */
    val rank: Int
        get() =
            when {
                heldUntilMs != null -> 2
                addStamp != null -> 1
                else -> 0
            }
}

/**
 * The rows of a page as the ownership rules write them (spec 26, schema 11): [items] with each one's owner, the
 * [listed] rows to insert (`INSERT OR IGNORE`) and the items [nowOwned] by the writer, whose row for it goes.
 */
internal class Routed(
    val items: Map<String, ItemRows>,
    val listed: List<ItemAlsoListedEntity>,
    val nowOwned: List<String>,
)

/**
 * Who owns each item of a page that [libraryId] writes under [stamp] (spec 26, schema 11), given the rows held
 * ([existing]) and the libraries being added and not held ([addingOwners]). A new item, or one the writer owns, is
 * the writer's. A tombstone goes to whoever writes it (a revival, versioned by the plan), and never gives its old
 * owner a row: that owner's listing dropped it. A chosen writer (no stamp) takes an item from an owner that is
 * being added, which keeps a row. Otherwise the owner stays, the row is written under it, and the writer gets a
 * row. The fields are always the writer's: the server's item is the same whichever library lists it.
 */
@Suppress("LongParameterList") // The account, the writer and its stamp, the page, and what the transaction read.
internal fun routePage(
    server: String,
    user: String,
    libraryId: String,
    stamp: Long?,
    items: Map<String, ItemRows>,
    existing: Map<String, ItemEntity>,
    addingOwners: Set<String>,
): Routed {
    val routed = LinkedHashMap<String, ItemRows>(items.size)
    val listed = ArrayList<ItemAlsoListedEntity>()
    val nowOwned = ArrayList<String>()
    for ((id, rows) in items) {
        val held = existing[id]
        val owner = held?.libraryId
        when {
            held == null || owner == null || owner == libraryId -> {
                routed[id] = rows
            }

            held.deletedAtMs != null -> {
                routed[id] = rows
                nowOwned += id
            }

            owner in addingOwners && stamp == null -> {
                routed[id] = rows
                nowOwned += id
                listed += ItemAlsoListedEntity(server, user, id, owner)
            }

            else -> {
                routed[id] = ItemRows(rows.item.copy(libraryId = owner), rows.genres, rows.tags, rows.studios)
                listed += ItemAlsoListedEntity(server, user, id, libraryId)
            }
        }
    }
    return Routed(routed, listed, nowOwned)
}

// The library each item of [listers] moves to: a chosen lister first, then one being added, then a held one, then
// the smallest id.
internal fun bestTargets(listers: List<AlsoLister>): Map<String, String> {
    val best = HashMap<String, AlsoLister>()
    for (row in listers) {
        val current = best[row.itemId]
        val better =
            current == null ||
                row.rank < current.rank ||
                (row.rank == current.rank && row.libraryId < current.libraryId)
        if (better) best[row.itemId] = row
    }
    return best.mapValues { it.value.libraryId }
}

// The libraries being added and not held, of [libs]: an owner a chosen writer takes from, and D8's adds.
internal fun addingLibraries(libs: List<LibraryEntity>): Set<String> =
    libs.filter { it.addStamp != null && it.heldUntilMs == null }.mapTo(HashSet()) { it.libraryId }

/**
 * Moves each item of [targets] to the library it names at [nowMs] (spec 26, schema 11), versioned with its side
 * names ([ItemHistoryDao.planMove]), and deletes the target's also-listed row for it. With [ownedBy], an item not
 * owned by that library (any more) is left alone. Returns (old owner, new owner) for each item moved.
 */
@Suppress("LongParameterList") // The two DAOs, the account, the moves, the time and the owner check.
internal suspend fun moveItems(
    library: LibraryDao,
    history: ItemHistoryDao,
    serverId: String,
    userId: String,
    targets: Map<String, String>,
    nowMs: Long,
    ownedBy: String?,
): List<Pair<String, String>> {
    val moved = ArrayList<Pair<String, String>>()
    for (chunk in targets.keys.toList().chunked(LibraryDao.MAX_VARIABLES)) {
        val rows = library.itemsByIds(serverId, userId, chunk).filter { ownedBy == null || it.libraryId == ownedBy }
        if (rows.isEmpty()) continue
        val names = library.sideNames(serverId, userId, rows.map { it.itemId })
        val plan = history.planMove(rows, targets, names, nowMs)
        history.insertVersions(plan.versions)
        library.updateItems(plan.moved)
        library.deleteAlsoListed(plan.moved.map { ItemAlsoListedEntity(serverId, userId, it.itemId, it.libraryId) })
        for (row in rows) moved += row.libraryId to targets.getValue(row.itemId)
    }
    return moved
}

// A library row takes a reconcile (or a removed-library write) when it is chosen: it exists, has no hold and no
// stamp, and is not marked for a rewalk.
internal fun reconcilable(row: LibraryEntity?): Boolean =
    row != null && row.heldUntilMs == null && row.addStamp == null && !row.rewalkPending

/**
 * What the owner drop did: [moved] items to a library that lists them, [tombstoned] the rest, and [ended] the
 * items whose member rows end (the dropped items the library still owns as tombstones).
 */
internal class OwnerDrop(
    val moved: Int,
    val tombstoned: Int,
    val ended: List<String>,
)

// The owner drop of [libraryId]'s reconcile (spec 26, MED-2, LOW-16): each of [gone] it still owns, live, moves to
// its best lister, or is tombstoned; only those it still owns end their member rows, and a moved one keeps them.
@Suppress("LongParameterList") // The two DAOs, the account, the library, what is gone and the time.
internal suspend fun dropOwned(
    library: LibraryDao,
    history: ItemHistoryDao,
    serverId: String,
    userId: String,
    libraryId: String,
    gone: List<String>,
    nowMs: Long,
): OwnerDrop {
    val owned = ArrayList<ItemEntity>()
    for (chunk in gone.chunked(LibraryDao.MAX_VARIABLES)) {
        for (row in library.itemsByIds(serverId, userId, chunk)) if (row.libraryId == libraryId) owned += row
    }
    val live = owned.filter { it.deletedAtMs == null }.map { it.itemId }
    val listers = ArrayList<AlsoLister>()
    for (chunk in live.chunked(LibraryDao.MAX_VARIABLES)) {
        for (row in library.listersOf(serverId, userId, chunk)) if (row.libraryId != libraryId) listers += row
    }
    val targets = bestTargets(listers)
    val moved = moveItems(library, history, serverId, userId, targets, nowMs, ownedBy = libraryId).size
    val toTombstone = live.filter { it !in targets }
    var tombstoned = 0
    for (chunk in toTombstone.chunked(LibraryDao.MAX_VARIABLES - 2)) {
        tombstoned += library.tombstoneOwned(serverId, userId, libraryId, chunk, nowMs)
    }
    val ended = owned.map { it.itemId }.filter { it !in targets }
    return OwnerDrop(moved, tombstoned, ended)
}

// The revival takeover (spec 26, MED-3): each of [listed] that another library owns as a tombstone is revived into
// [libraryId], versioned, and [libraryId]'s row for it goes. How many.
@Suppress("LongParameterList") // The two DAOs, the account, the library, what it lists and the time.
internal suspend fun takeOverIn(
    library: LibraryDao,
    history: ItemHistoryDao,
    serverId: String,
    userId: String,
    libraryId: String,
    listed: List<String>,
    nowMs: Long,
): Int {
    var taken = 0
    for (chunk in listed.chunked(LibraryDao.MAX_VARIABLES - 2)) {
        val rows = library.foreignTombstones(serverId, userId, libraryId, chunk)
        if (rows.isEmpty()) continue
        val names = library.sideNames(serverId, userId, rows.map { it.itemId })
        val plan = history.planRevival(rows, names, nowMs)
        history.insertVersions(plan.versions)
        library.updateItems(plan.revived.map { it.copy(libraryId = libraryId) })
        library.deleteAlsoListedIn(serverId, userId, libraryId, rows.map { it.itemId })
        taken += rows.size
    }
    return taken
}
