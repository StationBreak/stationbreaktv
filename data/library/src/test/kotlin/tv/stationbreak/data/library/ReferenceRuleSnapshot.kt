// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import java.util.TreeSet
import kotlin.time.Instant

// FROZEN at be035da2 (main, 2026-11-09): a copy of how `RoomLibraryRepository.ruleSnapshot` assembles a snapshot and
// of the reads `CacheTransactionDao.ruleSnapshot` makes for it, for #684's one-walk PR to be checked against. DO NOT
// EDIT. The one-walk PR changes the production read; this copy stays as it was, and
// `RuleSnapshotReferenceEqualityTest` is the gate that the new read gives the same snapshots (and the same
// folded-name caches, which `RuleSnapshot ==` ignores).
//
// It calls the live DAO reads (`ItemHistoryDao.heldAsOf`, `SeriesDao.heldAsOf`, `CollectionDao.heldAsOf`),
// `ScheduleInputs`, `RuleItemMapper`, `RuleInputs` and `idSpellings`, so the one-walk PR must not change those; it may
// add to them.
//
// Differences from the original, and only these: the item chunk is a parameter (the original reads in chunks of
// 500), and the reads are not in one transaction (the generated libraries do not change while they are read, so one
// state is given; the one-transaction isolation is the later PR's own test, with the `faultDuringRuleSnapshot` seam).

/** The reference snapshot read. See the note above. */
internal object ReferenceRuleSnapshot {
    /** Collections the as-of read takes at a time (the DAO's own constant, copied). */
    private const val COLLECTION_CHUNK = 100

    /** The kind column of an episode. */
    private const val EPISODE_KIND = "Episode"

    /** The rule snapshot of [account] held before [cutoff], of [libraries], items read [itemChunk] at a time. */
    suspend fun read(
        db: LibraryDatabase,
        account: LibraryAccount,
        libraries: Set<ItemId>,
        cutoff: Instant,
        itemChunk: Int,
    ): RuleSnapshot {
        val cutoffMs = ceilingMillis(cutoff)
        val wanted = libraries.mapTo(HashSet()) { it.value }
        val rows = rowsOf(db, account, cutoffMs, itemChunk, wanted)
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

    // The body of `CacheTransactionDao.ruleSnapshot`, without the transaction.
    private suspend fun rowsOf(
        db: LibraryDatabase,
        account: LibraryAccount,
        cutoffMs: Long,
        itemChunk: Int,
        libraries: Set<String>,
    ): RuleRows {
        val serverId = account.server.value
        val userId = account.user.value
        val strings = Interner()
        val items =
            if (libraries.isEmpty()) {
                emptyList()
            } else {
                db.itemHistoryDao().heldAsOf(serverId, userId, cutoffMs, itemChunk, libraries, strings)
            }
        val seriesIds = TreeSet<String>()
        for (row in items) {
            val seriesId = row.seriesId
            if (row.kind == EPISODE_KIND && seriesId != null) seriesIds.addAll(idSpellings(seriesId))
        }
        val heldSeries = db.seriesDao().heldAsOf(serverId, userId, cutoffMs, seriesIds.toList(), strings)
        val heldCollections = db.collectionDao().heldAsOf(serverId, userId, cutoffMs, COLLECTION_CHUNK)
        val synced = db.collectionDao().syncRow(serverId, userId)?.lastRelistMs != null
        return RuleRows(items, heldSeries, heldCollections, synced)
    }

    // The smallest whole millisecond that is not before [instant] (the repository's own helper, copied).
    private fun ceilingMillis(instant: Instant): Long {
        val floor = instant.toEpochMilliseconds()
        return if (Instant.fromEpochMilliseconds(floor) < instant) floor + 1 else floor
    }
}
