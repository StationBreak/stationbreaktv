// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.details

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId

/**
 * Item details fetched on demand and kept in memory, for the live info overlay and the guide's details panel. Entries
 * are keyed by server, address, user and item, and [details] reads the signed-in session on every call, so another
 * account never sees them. At most [capacity] entries are kept, the least recently used going first. When the
 * signed-in account changes or signs out, everything held for any other account is dropped and its requests are
 * cancelled ([clear] does the same on demand).
 *
 * One request per key runs at a time, in [scope] (the app's own supervised scope), so a caller that is cancelled, a
 * screen closing say, never cancels the request the others are waiting on. When the last caller waiting on a request
 * goes, the request is cancelled, so nothing keeps running for a screen nobody is looking at. At most [MAX_FETCHES]
 * requests are on the wire at once. A failure is not kept: the next call asks again. Nothing here persists.
 */
class ProgramDetailsCache(
    private val server: MediaServer,
    private val sessions: SessionRepository,
    private val scope: CoroutineScope,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity >= 1) { "A cache holds at least one entry" }
    }

    private data class Account(
        val server: ServerId,
        val address: ServerAddress,
        val user: UserId,
    )

    private data class Key(
        val account: Account,
        val item: CanonicalId,
    )

    /** One shared request and how many callers are waiting on it. */
    private class Flight {
        lateinit var job: Deferred<ItemDetails?>
        var waiters = 0
    }

    private val lock = Any()
    private val permits = Semaphore(MAX_FETCHES)

    // Access-ordered, so a read moves an entry to the back and the eldest is the least recently used.
    private val entries =
        object : LinkedHashMap<Key, ItemDetails>(INITIAL_SIZE, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ItemDetails>): Boolean = size > capacity
        }
    private val inFlight = HashMap<Key, Flight>()

    init {
        // Last, so everything it touches exists even if the scope runs it at once.
        scope.launch {
            sessions.current
                .map { session -> session?.let { Account(it.server.id, it.address, it.userId) } }
                .distinctUntilChanged()
                .collect { account -> retainOnly(account) }
        }
    }

    /** The details of [id] for the signed-in account, or null when none are signed in or the server couldn't say. */
    suspend fun details(id: CanonicalId): ItemDetails? {
        val session = sessions.current.value ?: return null
        val key = key(session, id)
        var hit: ItemDetails? = null
        var started: Flight? = null
        val flight =
            synchronized(lock) {
                hit = entries[key]
                if (hit != null) {
                    null
                } else {
                    val shared = inFlight[key] ?: newFlight(session, key).also { started = it }
                    shared.also { it.waiters++ }
                }
            }
        started?.job?.start()
        return if (flight == null) hit else await(flight, key)
    }

    /** What is held for [id] and the signed-in account, without asking the server; null if nothing or nobody. */
    fun cached(id: CanonicalId): ItemDetails? {
        val session = sessions.current.value ?: return null
        return synchronized(lock) { entries[key(session, id)] }
    }

    /** Empties the cache and cancels every request in flight; their callers get null. */
    fun clear() = retainOnly(null)

    private fun key(
        session: Session,
        id: CanonicalId,
    ) = Key(Account(session.server.id, session.address, session.userId), id)

    // Called with the lock held. The job is lazy, so nothing runs until the caller starts it outside the lock.
    private fun newFlight(
        session: Session,
        key: Key,
    ): Flight {
        val flight = Flight()
        flight.job = scope.async(start = CoroutineStart.LAZY) { fetch(session, key, flight) }
        inFlight[key] = flight
        // However the job ends, even cancelled before it ever started, its record goes.
        flight.job.invokeOnCompletion {
            synchronized(lock) { if (inFlight[key] === flight) inFlight.remove(key) }
        }
        return flight
    }

    private suspend fun await(
        flight: Flight,
        key: Key,
    ): ItemDetails? =
        try {
            flight.job.await()
        } catch (_: CancellationException) {
            // Our own cancellation goes on; the request being cancelled (sign-out) just means no details.
            currentCoroutineContext().ensureActive()
            null
        } finally {
            leave(flight, key)
        }

    private fun leave(
        flight: Flight,
        key: Key,
    ) {
        val abandoned =
            synchronized(lock) {
                flight.waiters--
                val last = flight.waiters == 0 && !flight.job.isCompleted
                if (last && inFlight[key] === flight) inFlight.remove(key)
                last
            }
        if (abandoned) flight.job.cancel()
    }

    private fun retainOnly(account: Account?) {
        val dropped =
            synchronized(lock) {
                entries.keys.removeAll { it.account != account }
                val gone = inFlight.filterKeys { it.account != account }
                gone.keys.forEach { inFlight.remove(it) }
                gone.values
            }
        dropped.forEach { it.job.cancel() }
    }

    private suspend fun fetch(
        session: Session,
        key: Key,
        flight: Flight,
    ): ItemDetails? {
        var found: ItemDetails? = null
        try {
            found = permits.withPermit { server.itemDetails(session, ItemId(key.item.value)) }.valueOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The port doesn't throw for server problems; anything else is still just "no details".
        }
        // Kept only while this request is still the registered one: a sign-out in between dropped it.
        synchronized(lock) { if (found != null && inFlight[key] === flight) entries[key] = found }
        return found
    }

    private fun ServerResult<ItemDetails>.valueOrNull(): ItemDetails? = (this as? ServerResult.Success)?.value

    companion object {
        const val DEFAULT_CAPACITY = 200

        /** How many requests may be on the wire at once, so a fast scroll can't flood the server. */
        const val MAX_FETCHES = 2
        private const val INITIAL_SIZE = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
