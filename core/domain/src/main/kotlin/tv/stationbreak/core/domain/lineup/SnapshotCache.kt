// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import java.lang.ref.SoftReference
import kotlin.time.Instant

/**
 * The library snapshots the day builds read, kept across lineup revisions (#392). A snapshot depends on the account,
 * the chosen libraries, the cut-off and what the library holds, not on the lineup, so a refresh that changes the lineup
 * need not read it again.
 *
 * What the library holds is told by two numbers the key carries: the account's `contentGeneration` (it moves exactly
 * when a committed write changes an item, series or collection row, or the chosen libraries) and the start of its last
 * completed collection pass (the snapshot's `collectionsSynced` can turn true without any row changing). A sync
 * therefore never finds a stale snapshot: its key is simply not asked for again. Both numbers are read *before* the
 * snapshot, so a write that lands in between leaves the snapshot filed under the older key, which no later build asks
 * for, and never the other way round.
 *
 * Callers that miss together share one read, which runs in [scope] and not in the caller: a build that gives up (its
 * lineup revision is no longer current) leaves the read running for the build that replaces it. At most [capacity]
 * snapshots are kept, least recently used out first, and only softly, so a short heap may drop them.
 */
internal class SnapshotCache(
    private val scope: CoroutineScope,
    private val capacity: Int = CAPACITY,
) {
    data class Key(
        val account: LibraryAccount,
        val libraries: Set<ItemId>,
        val cutoffMs: Long,
        val generation: Long,
        val collectionPass: Instant?,
    ) {
        /** The same snapshot of the library at another time of its life: a newer one makes this one obsolete. */
        fun sameSnapshotAs(o: Key) = copy(generation = o.generation, collectionPass = o.collectionPass) == o
    }

    private val lock = Any()
    private val kept = LinkedHashMap<Key, SoftReference<RuleSnapshot>>(capacity * 2, 0.75f, true)
    private val inFlight = HashMap<Key, Deferred<RuleSnapshot>>()

    /** For tests: how many reads [get] has started. */
    @Volatile internal var reads = 0
        private set

    suspend fun get(
        key: Key,
        read: suspend () -> RuleSnapshot,
    ): RuleSnapshot {
        var started = false
        val flight: Deferred<RuleSnapshot>
        synchronized(lock) {
            kept[key]?.get()?.let { return it }
            flight =
                inFlight.getOrPut(key) {
                    started = true
                    reads++
                    lateinit var self: Deferred<RuleSnapshot>
                    self = scope.async(start = CoroutineStart.LAZY) { fetch(key, self, read) }
                    self
                }
        }
        // Started outside the lock, once the entry it removes itself from is in place.
        if (started) flight.start()
        return flight.await()
    }

    private suspend fun fetch(
        key: Key,
        self: Deferred<RuleSnapshot>,
        read: suspend () -> RuleSnapshot,
    ): RuleSnapshot {
        try {
            val snapshot = read()
            synchronized(lock) {
                kept.keys.removeAll { it != key && it.sameSnapshotAs(key) }
                kept[key] = SoftReference(snapshot)
                while (kept.size > capacity) kept.remove(kept.keys.first())
            }
            return snapshot
        } finally {
            synchronized(lock) { inFlight.remove(key, self) }
        }
    }

    internal companion object {
        /** Today's and tomorrow's cut-offs of a lineup on one day start, and a custom day start's pair. */
        const val CAPACITY = 4
    }
}
