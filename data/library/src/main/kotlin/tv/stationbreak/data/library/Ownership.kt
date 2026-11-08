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
