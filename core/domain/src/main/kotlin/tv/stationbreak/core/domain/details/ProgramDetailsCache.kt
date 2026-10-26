// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.details

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.UserId

/**
 * Item details fetched on demand and kept in memory, for the live info overlay and the guide's details panel. Entries
 * are keyed by server, user and item, and [details] reads the signed-in session on every call, so another account never
 * sees them. At most [capacity] entries are kept, the least recently used going first.
 *
 * One request per key runs at a time, in [scope] (the app's own supervised scope), so a caller that is cancelled, a
 * screen closing say, never cancels the request the others are waiting on. A failure is not kept: the next call asks
 * again. Nothing here persists; a restart starts empty.
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

    private data class Key(
        val server: ServerId,
        val user: UserId,
        val item: CanonicalId,
    )

    private val lock = Any()

    // Access-ordered, so a read moves an entry to the back and the eldest is the least recently used.
    private val entries =
        object : LinkedHashMap<Key, ItemDetails>(INITIAL_SIZE, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ItemDetails>): Boolean = size > capacity
        }
    private val inFlight = HashMap<Key, Deferred<ItemDetails?>>()

    /** The details of [id] for the signed-in account, or null when none are signed in or the server couldn't say. */
    suspend fun details(id: CanonicalId): ItemDetails? {
        val session = sessions.current.value ?: return null
        val key = key(session, id)
        var started: Deferred<ItemDetails?>? = null
        val pending =
            synchronized(lock) {
                entries[key]?.let { CompletableDeferred(it) } ?: inFlight.getOrPut(key) {
                    // Lazy, and started below outside the lock: an eager start on an immediate dispatcher would run
                    // the cleanup before the map has the entry.
                    scope.async(start = CoroutineStart.LAZY) { fetch(session, key) }.also { started = it }
                }
            }
        started?.start()
        return pending.await()
    }

    /** What is held for [id] and the signed-in account, without asking the server; null if nothing or nobody. */
    fun cached(id: CanonicalId): ItemDetails? {
        val session = sessions.current.value ?: return null
        return synchronized(lock) { entries[key(session, id)] }
    }

    private fun key(
        session: Session,
        id: CanonicalId,
    ) = Key(session.server.id, session.userId, id)

    private suspend fun fetch(
        session: Session,
        key: Key,
    ): ItemDetails? {
        var found: ItemDetails? = null
        try {
            found = (server.itemDetails(session, ItemId(key.item.value)) as? ServerResult.Success)?.value
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The port doesn't throw for server problems; anything else is still just "no details".
        } finally {
            // One step, so a caller never sees neither the entry nor the request and starts a second one.
            synchronized(lock) {
                found?.let { entries[key] = it }
                inFlight.remove(key)
            }
        }
        return found
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
        private const val INITIAL_SIZE = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
