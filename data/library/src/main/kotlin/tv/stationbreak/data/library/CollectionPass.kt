// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.map
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionInfo
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Session
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** What a collection pass did. Counts only: no ID and no name, so it is safe to log. */
internal data class CollectionSummary(
    val read: Int = 0,
    val inserted: Int = 0,
    val revived: Int = 0,
    val changed: Int = 0,
    val unchanged: Int = 0,
    val tombstoned: Int = 0,
    val skippedNested: Int = 0,
    val skippedOther: Int = 0,
    val skippedIds: Int = 0,
    val relisted: Boolean = false,
)

/** How a collection pass ended and what it did. */
internal data class CollectionResult(
    val outcome: SyncOutcome,
    val summary: CollectionSummary,
)

/** One page of a listing the pass reads: the total (first page only) and the items. */
private class ListPage<T>(
    val total: Int?,
    val items: List<T>,
)

/** How reading one whole listing ended. */
private sealed interface Read<out T> {
    class Complete<T>(
        val items: List<T>,
    ) : Read<T>

    /** Stops the pass as [outcome]: a failed page, or a read that did not add up to the first page's total. */
    class Stopped(
        val outcome: SyncOutcome,
    ) : Read<Nothing>
}

/**
 * The collection pass (spec 10, Unit 2): lists the BoxSets, re-reads the member list of each one that is new, changed
 * or back, lists every BoxSet ID and tombstones the ones that are gone. It follows the item reconcile's guards (Vimes
 * H3): the sort is stable, a read counts only when every page was read and the distinct IDs read equal the first
 * page's total, an empty listing while collections are stored is suspect, and before a collection is tombstoned or a
 * non-empty member list emptied the list is read a second time and only what both reads lack is removed.
 *
 * A failed pass writes nothing more and is not recorded in the account's sync state: it is returned for the caller to
 * keep, so a collection problem never shows as a failed library. Collection names are never logged here, and nothing
 * here logs at all.
 */
@Suppress("LongParameterList", "TooManyFunctions") // The same knobs as the engines; one method per step of the pass.
internal class CollectionPass(
    private val repository: RoomLibraryRepository,
    private val server: MediaServer,
    private val clock: Clock,
    private val pageSize: Int = MediaServer.MAX_PAGE_SIZE,
    private val margin: Duration = FirstSyncEngine.DEFAULT_MARGIN,
    private val relistInterval: Duration = CollectionRelist.DEFAULT_INTERVAL,
) {
    init {
        require(pageSize in 1..MediaServer.MAX_PAGE_SIZE) { "pageSize must be 1 to ${MediaServer.MAX_PAGE_SIZE}" }
    }

    /** Runs one pass for [account]. An account that stops being the signed-in one ends it by exception, as for items. */
    suspend fun run(
        session: Session,
        account: LibraryAccount,
    ): CollectionResult = Run(session, account).run()

    @Suppress("ReturnCount") // Early returns carry a stopped read out of the steps.
    private inner class Run(
        private val session: Session,
        private val account: LibraryAccount,
    ) {
        private var summary = CollectionSummary()

        suspend fun run(): CollectionResult {
            val outcome = pass()
            return CollectionResult(outcome, summary)
        }

        private suspend fun pass(): SyncOutcome {
            val started = clock.now()
            val state = repository.collectionSync(account)
            val relist = needsRelist(state?.lastRelistMs, started)
            // A cursor ahead of now means the clock went back: count from the older of the two.
            val since =
                if (relist) {
                    null
                } else {
                    minOf(Instant.fromEpochMilliseconds(requireNotNull(state?.collectionsSinceMs)), started) -
                        margin
                }
            summary = summary.copy(relisted = relist)
            val listing =
                when (val read = readAll({ it.id.value }) { start -> collectionsPage(start, since) }) {
                    is Read.Stopped -> return read.outcome
                    is Read.Complete -> read.items
                }
            val ids =
                when (val read = readAll({ it.value }) { start -> idsPage(start) }) {
                    is Read.Stopped -> return read.outcome
                    is Read.Complete -> read.items.toSet()
                }
            // The cache first, then the server's answer is compared with it.
            val live = repository.liveCollectionIds(account).toSet()
            val tombstoned = repository.tombstonedCollectionIds(account).toSet()
            // Nothing at all, while collections are stored, is more likely a server fault than every BoxSet deleted.
            if (ids.isEmpty() && live.isNotEmpty()) return SyncOutcome.Failed(SyncFailure.Suspect)
            val gone =
                when (val decided = confirmGone(live - ids)) {
                    is Gone.Stopped -> return decided.outcome
                    is Gone.Ids -> decided.ids
                }
            val targets = listing.filter { it.id in ids }.toMutableList()
            if (since != null) {
                // A BoxSet that is back, or that the cache lacks, may not be among the changed ones (it was saved before
                // the cursor): the ID listing finds it, and a full listing gives its name.
                val listed = targets.map { it.id }.toSet()
                val wanted = ((ids intersect tombstoned) + (ids - live - tombstoned)) - listed
                if (wanted.isNotEmpty()) {
                    when (val read = readAll({ it.id.value }) { start -> collectionsPage(start, null) }) {
                        is Read.Stopped -> return read.outcome
                        is Read.Complete -> targets += read.items.filter { it.id in wanted }
                    }
                }
            }
            for (target in targets) {
                currentCoroutineContext().ensureActive()
                writeOne(target)?.let { return it }
            }
            if (gone.isNotEmpty()) repository.tombstoneCollections(account, gone)
            summary = summary.copy(tombstoned = gone.size)
            currentCoroutineContext().ensureActive()
            // Only now: a pass that stopped anywhere above leaves the cursor, and the re-list, as they were.
            repository.recordCollectionPass(account, started, relisted = relist)
            return SyncOutcome.Completed
        }

        // A full re-list runs when none has completed, when the last one is ahead of now (the clock went back), or when
        // it is at least the interval old (Vimes M2, M5).
        private fun needsRelist(
            lastRelistMs: Long?,
            now: Instant,
        ): Boolean {
            if (lastRelistMs == null) return true
            val last = Instant.fromEpochMilliseconds(lastRelistMs)
            return last > now || now - last >= relistInterval
        }

        // What a second read of the ID listing still lacks: a shift that leaves the size right looks sound once.
        private suspend fun confirmGone(first: Set<ItemId>): Gone {
            if (first.isEmpty()) return Gone.Ids(emptySet())
            val again =
                when (val read = readAll({ it.value }) { start -> idsPage(start) }) {
                    is Read.Stopped -> return Gone.Stopped(read.outcome)
                    is Read.Complete -> read.items.toSet()
                }
            if (again.isEmpty()) return Gone.Stopped(SyncOutcome.Failed(SyncFailure.Suspect))
            return Gone.Ids(first - again)
        }

        // Reads one BoxSet's members and writes it with them. Null when it was written, else why the pass stops.
        private suspend fun writeOne(target: CollectionInfo): SyncOutcome? {
            val members =
                when (val read = members(target.id)) {
                    is Read.Stopped -> return read.outcome
                    is Read.Complete -> read.items
                }
            val write = repository.writeCollection(account, target.id, target.name, members)
            summary =
                summary.copy(
                    read = summary.read + 1,
                    inserted = summary.inserted + if (write.inserted) 1 else 0,
                    revived = summary.revived + if (write.revived) 1 else 0,
                    changed = summary.changed + if (write.stamp != null && !write.inserted && !write.revived) 1 else 0,
                    unchanged = summary.unchanged + if (write.stamp == null) 1 else 0,
                )
            return null
        }

        // The stored members of [id]: what the server lists, less what the cache does not hold. A list that reads empty
        // for a collection that has members is read again, and only what both reads lack is removed.
        private suspend fun members(id: ItemId): Read<CollectionMember> {
            val first =
                when (val read = readMembers(id)) {
                    is Read.Stopped -> return read
                    is Read.Complete -> read.items
                }
            if (first.isNotEmpty() || repository.currentMemberCount(account, id) == 0) return Read.Complete(first)
            return readMembers(id)
        }

        // All pages of one BoxSet's members, filtered to the kinds and IDs the cache stores; the skips are counted.
        private suspend fun readMembers(id: ItemId): Read<CollectionMember> {
            val all =
                when (val read = readAll({ it.id.value }) { start -> membersPage(id, start) }) {
                    is Read.Stopped -> return read
                    is Read.Complete -> read.items
                }
            val stored = ArrayList<CollectionMember>(all.size)
            for (member in all) {
                val canonical = CanonicalId.parseOrNull(member.id.value)
                when {
                    member.kind == CollectionMemberKind.Collection -> summary = summary.copy(skippedNested = summary.skippedNested + 1)
                    storedMemberKind(member.kind) == null -> summary = summary.copy(skippedOther = summary.skippedOther + 1)
                    canonical == null -> summary = summary.copy(skippedIds = summary.skippedIds + 1)
                    else -> stored += CollectionMember(ItemId(canonical.value), member.kind)
                }
            }
            return Read.Complete(stored)
        }

        private suspend fun collectionsPage(
            start: Int,
            since: Instant?,
        ): ServerResult<ListPage<CollectionInfo>> =
            server.collections(session, start, pageSize, since).map { ListPage(it.totalCount, it.collections) }

        private suspend fun idsPage(start: Int): ServerResult<ListPage<ItemId>> =
            server.collectionIds(session, start, pageSize).map { ListPage(it.totalCount, it.ids) }

        private suspend fun membersPage(
            id: ItemId,
            start: Int,
        ): ServerResult<ListPage<CollectionMember>> =
            server.collectionMembers(session, id, start, pageSize).map { ListPage(it.totalCount, it.members) }

        // Inline, so the three listings' lambdas are not three classes behind one call in a loop (ART 7.1, #264).
        // Reads every page of a listing, or stops: a failed page, a page that adds nothing, or a read whose distinct
        // IDs do not equal the first page's total (a short count, or a page that shifted) counts as nothing.
        private suspend inline fun <T> readAll(
            key: (T) -> String,
            page: (start: Int) -> ServerResult<ListPage<T>>,
        ): Read<T> {
            val items = ArrayList<T>()
            val seen = HashSet<String>()
            var total: Int? = null
            while (true) {
                when (val result = page(items.size)) {
                    is ServerResult.Failure -> {
                        return Read.Stopped(failed(result.error.toSyncFailure()))
                    }

                    is ServerResult.Success -> {
                        val chunk = result.value
                        if (items.isEmpty()) total = chunk.total
                        for (item in chunk.items) {
                            items += item
                            seen += key(item)
                        }
                        val expected = total
                        val done = chunk.items.isEmpty() || (expected != null && items.size >= expected)
                        if (done) {
                            val sound = expected != null && items.size == expected && seen.size == expected
                            return if (sound) Read.Complete(items) else Read.Stopped(failed(SyncFailure.Server))
                        }
                    }
                }
            }
        }

        private fun failed(failure: SyncFailure) = SyncOutcome.Failed(failure)
    }

    private sealed interface Gone {
        class Ids(
            val ids: Set<ItemId>,
        ) : Gone

        class Stopped(
            val outcome: SyncOutcome,
        ) : Gone
    }
}
