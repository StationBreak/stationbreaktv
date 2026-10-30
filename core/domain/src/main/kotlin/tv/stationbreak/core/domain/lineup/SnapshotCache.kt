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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The library snapshots the day builds read, kept across lineup revisions (#392). A snapshot depends on the account,
 * the chosen libraries, the cut-off and what the library holds, not on the lineup, so a refresh that changes the lineup
 * need not read it again.
 *
 * What the library holds is told by one number the key carries: the account's `contentGeneration`. It moves exactly
 * when a committed write changes an item, series or collection row, or the chosen libraries, and when the first full
 * collection pass turns `collectionsSynced` true, so it covers everything `ruleSnapshot` returns (the contract written
 * on `LibraryRepository.ruleSnapshot`). The start of the last completed collection pass is not in the key: every sync
 * runs a pass, and one that changed nothing must not cost the next build a read (#487). A sync therefore never finds
 * a stale snapshot: its key is simply not asked for again. The generation is read *before* the snapshot, so a write
 * that lands in between leaves the snapshot filed under the older key, which no later build asks for, and never the
 * other way round.
 *
 * Callers that miss together share one read, which runs in [scope] and not in the caller: a build that gives up (its
 * lineup revision is no longer current) leaves the read running for the build that replaces it.
 *
 * A read of a newer state of the same snapshot (a sync moved the generation) never cancels an older read that still
 * has waiters: the older read stops taking new waiters, its current waiters finish with its result (a slightly older
 * snapshot is fine for the build that asked, and the next build reads the new one), and so two builds cannot cancel
 * each other's reads however often a sync bumps the generation. A read is cancelled only when it has no waiter left,
 * which happens when its last waiter is cancelled (a caller outside the source's build scope; the source's own builds
 * are never cancelled by their callers) or when it had been replaced and its waiters have all gone. Cancelling stops
 * the coroutine waiting on the library; a query already running on the library's own thread finishes there and its
 * result is dropped. At most [capacity] snapshots are kept, least recently used out first, and only softly, so a
 * short heap may drop them.
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
    ) {
        /** The same snapshot of the library at another time of its life. */
        fun sameSnapshotAs(o: Key) = copy(generation = o.generation) == o

        /** True when this is a later state of the library than [o]: a higher generation. */
        fun newerThan(o: Key) = generation > o.generation
    }

    /** How [get] found the snapshot. [Cleared] is a kept snapshot the garbage collector had dropped. */
    enum class Found(
        val label: String,
    ) {
        Hit("hit"),
        Miss("miss"),
        Cleared("cleared"),
    }

    class Got(
        val snapshot: RuleSnapshot,
        val found: Found,
    )

    private class Flight {
        lateinit var deferred: Deferred<RuleSnapshot>
        var waiters = 0
        var superseded = false
        var stopped = false
    }

    private val lock = Any()
    private val kept = LinkedHashMap<Key, SoftReference<RuleSnapshot>>(capacity * 2, 0.75f, true)
    private val inFlight = HashMap<Key, Flight>()

    private val started = AtomicInteger()
    private val cancelled = AtomicInteger()

    /** For tests: how many reads [get] has started, and how many it has cancelled. */
    internal val reads: Int get() = started.get()
    internal val cancelledReads: Int get() = cancelled.get()

    /** True when every one of [keys] is kept and not dropped by the garbage collector. Reads nothing. */
    fun holdsAll(keys: List<Key>): Boolean = synchronized(lock) { keys.all { kept[it]?.get() != null } }

    /** What [claim] found: a kept snapshot, or the read to wait for. */
    private class Claim(
        val hit: Got?,
        val flight: Flight?,
        val fresh: Boolean,
        val cleared: Boolean,
    )

    suspend fun get(
        key: Key,
        read: suspend () -> RuleSnapshot,
    ): Got {
        val claim = claim(key, read)
        claim.hit?.let { return it }
        val flight = checkNotNull(claim.flight)
        if (claim.fresh) {
            flight.deferred.invokeOnCompletion { synchronized(lock) { inFlight.remove(key, flight) } }
            flight.deferred.start()
        }
        try {
            return Got(flight.deferred.await(), if (claim.cleared) Found.Cleared else Found.Miss)
        } finally {
            leave(flight)
        }
    }

    private fun claim(
        key: Key,
        read: suspend () -> RuleSnapshot,
    ): Claim =
        synchronized(lock) {
            var cleared = false
            val ref = kept[key]
            if (ref != null) {
                val held = ref.get()
                if (held != null) return Claim(Got(held, Found.Hit), null, false, false)
                kept.remove(key)
                cleared = true
            }
            val joined = inFlight[key]?.takeUnless { it.stopped || it.superseded }
            val flight =
                joined ?: Flight().also { created ->
                    started.incrementAndGet()
                    for ((other, f) in inFlight) {
                        if (other != key && key.sameSnapshotAs(other) && key.newerThan(other)) {
                            // It takes no new waiters; its current ones finish with it.
                            f.superseded = true
                        }
                    }
                    created.deferred = scope.async(start = CoroutineStart.LAZY) { fetch(key, read) }
                    inFlight[key] = created
                }
            flight.waiters++
            Claim(null, flight, joined == null, cleared)
        }

    // The last waiter to go takes an unfinished read with it (see the class comment for when that can happen).
    private fun leave(flight: Flight) {
        val stop =
            synchronized(lock) {
                flight.waiters--
                val last = flight.waiters == 0 && !flight.deferred.isCompleted
                if (last) flight.stopped = true
                last
            }
        if (stop) {
            cancelled.incrementAndGet()
            flight.deferred.cancel()
        }
    }

    private suspend fun fetch(
        key: Key,
        read: suspend () -> RuleSnapshot,
    ): RuleSnapshot {
        val snapshot = read()
        synchronized(lock) {
            // A read of an older state finishing late must not push out a newer one.
            if (kept.keys.none { it.sameSnapshotAs(key) && it.newerThan(key) }) {
                kept.keys.removeAll { it != key && it.sameSnapshotAs(key) }
                kept[key] = SoftReference(snapshot)
                while (kept.size > capacity) kept.remove(kept.keys.first())
            }
        }
        return snapshot
    }

    internal companion object {
        /** Today's and tomorrow's cut-offs of a lineup on one day start, and a custom day start's pair. */
        const val CAPACITY = 4
    }
}
