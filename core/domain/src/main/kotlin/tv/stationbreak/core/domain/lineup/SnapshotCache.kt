// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

/**
 * The library snapshots the day builds read, kept across lineup revisions (#392). A snapshot depends on the account,
 * the chosen libraries, the cut-off and what the library holds, not on the lineup, so a refresh that changes the lineup
 * need not read it again.
 *
 * What the library holds is told by two numbers the key carries: the account's `contentGeneration` (it moves exactly
 * when a committed write changes an item, series or collection row, or the chosen libraries) and the start of its last
 * completed collection pass (the snapshot's `collectionsSynced` can turn true without any row changing). That these
 * two cover everything `ruleSnapshot` returns is the contract written on `LibraryRepository.ruleSnapshot`. A sync
 * therefore never finds a stale snapshot: its key is simply not asked for again. Both numbers are read *before* the
 * snapshot, so a write that lands in between leaves the snapshot filed under the older key, which no later build asks
 * for, and never the other way round.
 *
 * Callers that miss together share one read, which runs in [scope] and not in the caller: a build that gives up (its
 * lineup revision is no longer current) leaves the read running for the build that replaces it. A read is cancelled
 * when its last waiter leaves, and when a read of a newer state of the same snapshot starts (a sync moved the
 * generation): its waiters get [Superseded] and ask again. Cancelling stops the coroutine waiting on the library; a
 * query already running on the library's own thread finishes there and its result is dropped. At most [capacity]
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
        /** The same snapshot of the library at another time of its life. */
        fun sameSnapshotAs(o: Key) = copy(generation = o.generation, collectionPass = o.collectionPass) == o

        /** True when this is a later state of the library than [o]: a higher generation, or a later pass. */
        fun newerThan(o: Key): Boolean {
            val pass = collectionPass
            val other = o.collectionPass
            val later = pass != null && (other == null || pass > other)
            return generation > o.generation || (generation == o.generation && later)
        }
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

    /** The read a caller was waiting for was replaced by a read of a newer state of the library. */
    class Superseded : RuntimeException("The library changed while its snapshot was being read")

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

    /** What [claim] found: a kept snapshot, or the read to wait for (and the older reads it replaces). */
    private class Claim(
        val hit: Got?,
        val flight: Flight?,
        val fresh: Boolean,
        val cleared: Boolean,
        val victims: List<Flight>,
    )

    suspend fun get(
        key: Key,
        read: suspend () -> RuleSnapshot,
    ): Got {
        val claim = claim(key, read)
        claim.hit?.let { return it }
        val flight = checkNotNull(claim.flight)
        // Outside the lock: cancelling runs completion handlers, which take it.
        for (victim in claim.victims) {
            cancelled.incrementAndGet()
            victim.deferred.cancel()
        }
        if (claim.fresh) {
            flight.deferred.invokeOnCompletion { synchronized(lock) { inFlight.remove(key, flight) } }
            flight.deferred.start()
        }
        try {
            return Got(flight.deferred.await(), if (claim.cleared) Found.Cleared else Found.Miss)
        } catch (e: CancellationException) {
            if (flight.superseded && currentCoroutineContext().isActive) throw Superseded()
            throw e
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
                if (held != null) return Claim(Got(held, Found.Hit), null, false, false, emptyList())
                kept.remove(key)
                cleared = true
            }
            val joined = inFlight[key]?.takeUnless { it.stopped || it.superseded }
            val victims = ArrayList<Flight>()
            val flight =
                joined ?: Flight().also { created ->
                    started.incrementAndGet()
                    for ((other, f) in inFlight) {
                        if (other != key && key.sameSnapshotAs(other) && key.newerThan(other)) {
                            f.superseded = true
                            victims += f
                        }
                    }
                    created.deferred = scope.async(start = CoroutineStart.LAZY) { fetch(key, read) }
                    inFlight[key] = created
                }
            flight.waiters++
            Claim(null, flight, joined == null, cleared, victims)
        }

    // The last waiter to go takes an unfinished read with it.
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
