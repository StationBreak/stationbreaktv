// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.details

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.ItemLogo
import tv.stationbreak.core.model.Session
import java.security.MessageDigest
import java.util.Locale

/**
 * Item logos (images) for the guide's details panel, kept on disk by their tag and fetched on demand with the
 * session's own token, through [server]. This is our own small loader on the adapter's HTTP client, not an image
 * library: that client is the one place that decides who may be sent the token and which certificates are trusted, and
 * a second HTTP stack would have to be given the same rules.
 *
 * Entries are kept per account (server, address, user) and each call reads the signed-in session, so another account
 * never sees them; when the account changes or signs out, what is held for any other is deleted and its requests are
 * cancelled. One request per logo runs at a time, in [scope]; when the last caller waiting on it goes, it is cancelled,
 * so nothing runs for a highlight that has moved on. A caller that stays but stops caring (it was too late to show the
 * logo) still lets the request finish, which keeps the logo for next time. At most [MAX_FETCHES] are on the wire at
 * once. A failure is not kept: the next call asks again, and the answer is "no logo". The bytes are the server's and
 * untrusted; they are only stored and handed back, never opened here. Nothing is logged.
 *
 * Every read, write and delete of the [store] runs on one serial dispatcher made from [io], so a delete for an account
 * that left cannot interleave with a write for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogoCache(
    private val server: MediaServer,
    private val sessions: SessionRepository,
    private val store: LogoStore,
    private val scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val maxHeight: Int = LOGO_HEIGHT_PX,
) {
    init {
        require(maxHeight >= 1) { "A logo is at least a pixel tall" }
    }

    private data class Key(
        val account: String,
        val logo: ItemLogo,
        val height: Int,
    ) {
        // The height is part of the name: the same image at another size is another file.
        val name: String get() = sha256("${logo.owner.value}|${logo.tag}|h$height")
    }

    /** One shared request and how many callers are waiting on it. */
    private class Flight {
        lateinit var job: Deferred<ByteArray?>
        var waiters = 0
    }

    private val lock = Any()
    private val permits = Semaphore(MAX_FETCHES)
    private val inFlight = HashMap<Key, Flight>()
    private val disk = io.limitedParallelism(1)

    init {
        scope.launch {
            sessions.current
                .map { session -> session?.let(::accountOf) }
                .distinctUntilChanged()
                .collect { retainOnly(it) }
        }
    }

    /**
     * The bytes of [logo] for the signed-in account: from disk when held, else from the server (and kept). Null when
     * none are signed in, the server has no such image or could not say. Cancelling the caller cancels the request
     * only when nobody else waits on it.
     */
    suspend fun logo(logo: ItemLogo): ByteArray? {
        val session = sessions.current.value ?: return null
        val key = Key(accountOf(session), logo, maxHeight)
        return withContext(disk) { store.get(key.account, key.name) } ?: fetchShared(session, key)
    }

    // The one request for [key], started by the first caller and joined by the rest.
    private suspend fun fetchShared(
        session: Session,
        key: Key,
    ): ByteArray? {
        val flight = join(session, key) ?: return null
        flight.job.start()
        return await(flight, key)
    }

    // Counts the caller among the waiters of [key]'s request, starting one if there is none. Null when the account
    // has gone since the caller read the session: a sign-out or switch means its entries are gone.
    private fun join(
        session: Session,
        key: Key,
    ): Flight? =
        synchronized(lock) {
            if (sessions.current.value?.let(::accountOf) != key.account) {
                null
            } else {
                (inFlight[key] ?: newFlight(session, key)).also { it.waiters++ }
            }
        }

    /**
     * An opaque name for the signed-in account (a hash: never the server, the address or the user), or null when none
     * is signed in. It changes with the account, so whoever holds logos of its own can tell they are another's.
     */
    fun account(): String? = sessions.current.value?.let(::accountOf)

    /** Deletes every logo held and cancels every request in flight; their callers get null. */
    fun clear() = retainOnly(null)

    // Called with the lock held. The job is lazy, so nothing runs until the caller starts it outside the lock.
    private fun newFlight(
        session: Session,
        key: Key,
    ): Flight {
        val flight = Flight()
        flight.job = scope.async(start = CoroutineStart.LAZY) { fetch(session, key) }
        inFlight[key] = flight
        // However the job ends, even cancelled before it ever started, its record goes.
        flight.job.invokeOnCompletion { synchronized(lock) { if (inFlight[key] === flight) inFlight.remove(key) } }
        return flight
    }

    private suspend fun fetch(
        session: Session,
        key: Key,
    ): ByteArray? {
        val answer = permits.withPermit { server.logo(session, key.logo, maxHeight) }
        val bytes = (answer as? ServerResult.Success)?.value?.takeIf { it.isNotEmpty() } ?: return null
        // Kept only while the account that asked is still signed in: on the same serial dispatcher as the delete that
        // an account change makes, so a late write can never put back what that delete took.
        withContext(disk) {
            if (sessions.current.value?.let(::accountOf) == key.account) store.put(key.account, key.name, bytes)
        }
        return bytes
    }

    private suspend fun await(
        flight: Flight,
        key: Key,
    ): ByteArray? =
        try {
            flight.job.await()
        } catch (_: CancellationException) {
            // The request was cancelled (nobody was left waiting, or the account changed): "no logo". If it is this
            // caller that was cancelled, that is thrown instead.
            currentCoroutineContext().ensureActive()
            null
        } finally {
            synchronized(lock) {
                flight.waiters--
                if (flight.waiters == 0 && flight.job.isActive) {
                    // Gone from the table at once, so a caller that comes next starts a request of its own.
                    if (inFlight[key] === flight) inFlight.remove(key)
                    flight.job.cancel()
                }
            }
        }

    private fun retainOnly(keep: String?) {
        val gone =
            synchronized(lock) {
                val doomed = inFlight.filterKeys { it.account != keep }
                inFlight.keys.removeAll(doomed.keys)
                doomed.values.toList()
            }
        gone.forEach { it.job.cancel() }
        scope.launch(disk) { store.retainOnly(keep) }
    }

    private fun accountOf(session: Session): String =
        sha256("${session.server.id.value}|${session.address.baseUrl}|${session.userId.value}")

    companion object {
        /** The most logos on the wire at once. */
        const val MAX_FETCHES = 2

        /** How tall, in pixels, the logo asked for is: a panel's 46 dp at the largest screen density a TV has. */
        const val LOGO_HEIGHT_PX = 192

        private fun sha256(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray())
                .joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
