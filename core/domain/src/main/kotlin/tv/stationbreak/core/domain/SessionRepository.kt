// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.StoredSession
import tv.stationbreak.core.model.UserId

/** Whether a [SessionRepository.save] reached the [SecretStore]. */
enum class PersistOutcome { Saved, NotSaved }

/**
 * How a sign-out ended. [Revoked]: the server forgot the token. [NotRevoked]: it could not be told, and the user may
 * need to remove the device on the server. [StillStored]: neither worked: the server was not told, and this TV's store
 * could neither clear the token nor overwrite it, so the next start could sign back in with it (#209).
 */
enum class RevokeOutcome { Revoked, NotRevoked, StillStored }

/** What [SessionRepository.restore] found. */
sealed interface RestoreOutcome {
    /** A stored session the server confirmed, bound to the server that issued it. */
    data class Restored(
        val session: Session,
    ) : RestoreOutcome

    /** No usable session: none stored, the store can't answer now, or the stored one was unreadable (and cleared). */
    data object SignedOut : RestoreOutcome

    /** The server refused the stored session (`ServerMismatch` or `SessionExpired`); it was cleared. */
    data class Rejected(
        val error: MediaServerError,
    ) : RestoreOutcome

    /** The server couldn't be asked (offline, timeout...). The stored session is untouched. */
    data class Unavailable(
        val error: MediaServerError,
    ) : RestoreOutcome

    /**
     * The user signed out, or someone signed in, while the server was being asked: what it confirmed is not the
     * session any more, so nothing was published, written or cleared (#333).
     */
    data object Superseded : RestoreOutcome
}

/**
 * Persists the one [StoredSession] as a single versioned JSON string under [SESSION_KEY] (R1, R3). The token is only
 * ever inside that string. The format is a private surrogate, so no secret type is itself `@Serializable` (D11).
 *
 * The stored session is cleared **only** on: an unreadable blob or an unknown format version, `ServerMismatch`,
 * `SessionExpired`, and sign-out. A store that fails to load never clears (transient faults must not wipe a good
 * token). There is no fallback store: a failed save is reported and the session lives in memory only.
 */
class SessionRepository(
    private val store: SecretStore,
    private val server: MediaServer,
    cache: AccountCache = AccountCache.None,
) {
    private val library = TolerantCache(cache)
    private val held = MutableStateFlow<Session?>(null)

    // Moves on every sign-in ([save]) and every clear (sign-out, a rejected session), under [guard], which also covers
    // their store writes. A [verify] or [restore] notes it before reading the store and asking the server, and
    // publishes (or writes back) what the server confirmed only if it has not moved, so a check already in flight can
    // never bring back a session the user signed out of or replaced meanwhile (#333). Never held across a server call.
    private val guard = Mutex()
    private var generation = 0L

    /**
     * The signed-in session, held in memory for whoever needs it after sign-in (the player's media requests read it
     * on every call). The one holder: [save], a confirmed [restore] and a confirmed [verify] set it; [clear],
     * [signOut] (whether or not the server revoked the token) and a fatal [onServerError] null it. It holds the token,
     * so it is never printed.
     */
    val current: StateFlow<Session?> = held.asStateFlow()

    /**
     * Stores [session]. Its account becomes the active one in the saved library, whether or not the token reached
     * storage: the user is signed in for this run, so every other account's saved library goes now (spec 06 task 2.12).
     */
    suspend fun save(session: Session): PersistOutcome {
        val outcome =
            guard.withLock {
                generation++
                held.value = session
                write(session.toStored())
            }
        library.activate(session.libraryAccount())
        return outcome
    }

    private suspend fun write(stored: StoredSession): PersistOutcome =
        when (store.save(SESSION_KEY, encode(stored))) {
            is SecretStoreResult.Success -> PersistOutcome.Saved
            SecretStoreResult.Failure -> PersistOutcome.NotSaved
        }

    /**
     * Reads the stored session and has the server confirm it is still good and still the server it came from. The
     * [configured] address is the one the user has set now; a different server clears the session without a request.
     * An `http` to `https` upgrade the server adopted is written back, and the session is kept.
     */
    suspend fun restore(configured: ServerAddress): RestoreOutcome {
        val started = guard.withLock { generation }
        val raw = (store.load(SESSION_KEY) as? SecretStoreResult.Success)?.value ?: return RestoreOutcome.SignedOut
        val stored = decode(raw)
        return when {
            stored == null -> {
                // No account is known from an unreadable blob, so there is nothing to forget by name; the next
                // sign-in's useAccount deletes any other account's rows.
                clear()
                RestoreOutcome.SignedOut
            }

            !stored.address.bindsTo(configured) && !configured.bindsTo(stored.address) -> {
                clear()
                library.forget(stored.libraryAccount())
                RestoreOutcome.Rejected(MediaServerError.ServerMismatch())
            }

            else -> {
                // Over the configured https upgrade, never the stored cleartext address (#209); written back as any
                // adopted upgrade is.
                resume(stored.overTheUpgrade(configured), started, stored)
            }
        }
    }

    /**
     * Like [restore], but only looks: it never activates an account in the saved library, never writes or clears the
     * stored session, and never forgets a library. For a background worker, which must not bring back an account or a
     * session that the user signed out while it was waiting for the server (Angua M1, PR #250).
     */
    @Suppress("ReturnCount") // Each early return is a way the check ends without asking the server.
    suspend fun verify(configured: ServerAddress): RestoreOutcome {
        val started = guard.withLock { generation }
        val raw = (store.load(SESSION_KEY) as? SecretStoreResult.Success)?.value ?: return RestoreOutcome.SignedOut
        val stored = decode(raw) ?: return RestoreOutcome.SignedOut
        if (!stored.address.bindsTo(configured) && !configured.bindsTo(stored.address)) {
            return RestoreOutcome.Rejected(MediaServerError.ServerMismatch())
        }
        // Over the configured https upgrade, as restore does (#209); verify still writes nothing back.
        return when (val result = server.resume(stored.overTheUpgrade(configured))) {
            is ServerResult.Success -> {
                // Signed out (or in again) while the server was asked: what it confirmed is no longer the session.
                val current = guard.withLock { (generation == started).also { if (it) held.value = result.value } }
                if (current) RestoreOutcome.Restored(result.value) else RestoreOutcome.Superseded
            }

            is ServerResult.Failure -> {
                val error = result.error
                val fatal = error is MediaServerError.SessionExpired || error is MediaServerError.ServerMismatch
                if (fatal) RestoreOutcome.Rejected(error) else RestoreOutcome.Unavailable(error)
            }
        }
    }

    private suspend fun resume(
        stored: StoredSession,
        started: Long,
        original: StoredSession = stored,
    ): RestoreOutcome =
        when (val result = server.resume(stored)) {
            is ServerResult.Success -> {
                // Published and written back only if nobody signed out or in while the server was asked (#333).
                val current =
                    guard.withLock {
                        (generation == started).also {
                            if (it) {
                                if (result.value.address != original.address) write(result.value.toStored())
                                held.value = result.value
                            }
                        }
                    }
                if (current) {
                    library.activate(result.value.libraryAccount())
                    RestoreOutcome.Restored(result.value)
                } else {
                    RestoreOutcome.Superseded
                }
            }

            is ServerResult.Failure -> {
                // A refusal of a session that was replaced meanwhile must not clear the one that replaced it.
                if (guard.withLock { generation } != started) {
                    RestoreOutcome.Superseded
                } else if (onServerError(result.error, stored.libraryAccount())) {
                    RestoreOutcome.Rejected(result.error)
                } else {
                    RestoreOutcome.Unavailable(result.error)
                }
            }
        }

    /**
     * Clears the stored session if [error] says it can't be used (`SessionExpired`, `ServerMismatch`), and the saved
     * library of [session]'s account with it, so rows don't outlive a rejected session.
     */
    suspend fun onServerError(
        error: MediaServerError,
        session: Session? = null,
    ): Boolean = onServerError(error, session?.libraryAccount())

    private suspend fun onServerError(
        error: MediaServerError,
        account: LibraryAccount?,
    ): Boolean {
        val fatal = error is MediaServerError.SessionExpired || error is MediaServerError.ServerMismatch
        if (fatal) {
            clear()
            account?.let { library.forget(it) }
        }
        return fatal
    }

    /**
     * Drops the session from memory and the store. A store that will not clear is asked again, then has the key
     * overwritten with a value that never decodes, so the next [restore] clears it instead of signing back in (#209).
     * False only when none of that worked and a usable token may still be stored.
     */
    suspend fun clear(): Boolean =
        guard.withLock {
            generation++
            held.value = null
            store.clear(SESSION_KEY) is SecretStoreResult.Success ||
                store.clear(SESSION_KEY) is SecretStoreResult.Success ||
                store.save(SESSION_KEY, SIGNED_OUT) is SecretStoreResult.Success
        }

    /**
     * Clears the stored session first, then asks the server to revoke the token, all in [NonCancellable] so a
     * cancelled caller still finishes (R12). The device ID is kept (R13).
     */
    suspend fun signOut(session: Session): RevokeOutcome =
        withContext(NonCancellable) {
            val cleared = clear()
            library.forget(session.libraryAccount())
            val revoked = server.revoke(session) is ServerResult.Success
            when {
                // The server forgot the token: whatever this TV still holds is dead.
                revoked -> RevokeOutcome.Revoked

                // Still stored and still valid: the next start would sign back in with it.
                !cleared -> RevokeOutcome.StillStored

                else -> RevokeOutcome.NotRevoked
            }
        }

    @OptIn(SecretAccess::class)
    private fun encode(stored: StoredSession): String =
        JSON.encodeToString(
            Blob.serializer(),
            Blob(
                version = FORMAT_VERSION,
                userId = stored.userId.value,
                token = stored.token.reveal,
                serverId = stored.serverId.value,
                address = stored.address,
                acknowledgement = stored.acknowledgement,
            ),
        )

    @OptIn(SecretAccess::class)
    private fun decode(raw: String): StoredSession? =
        try {
            val blob = JSON.decodeFromString(Blob.serializer(), raw)
            if (blob.version != FORMAT_VERSION) {
                null
            } else {
                StoredSession(
                    UserId(blob.userId),
                    AccessToken(blob.token),
                    ServerId(blob.serverId),
                    blob.address,
                    blob.acknowledgement,
                )
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            // A blank ID, or an address that breaks ServerAddress's rules.
            null
        }

    // Never printed: it holds the token.
    @Serializable
    private class Blob(
        val version: Int,
        val userId: String,
        val token: String,
        val serverId: String,
        val address: ServerAddress,
        val acknowledgement: CleartextAcknowledgement? = null,
    ) {
        override fun toString(): String = "Blob(***)"
    }

    companion object {
        const val SESSION_KEY = "session"

        // Written over the session when the store will not clear it: not JSON, so it never decodes (#209).
        private const val SIGNED_OUT = "signed-out"
        const val FORMAT_VERSION = 1
        private val JSON = Json { encodeDefaults = true }
    }
}

private fun Session.libraryAccount() = LibraryAccount(this.server.id, this.userId)

private fun StoredSession.libraryAccount() = LibraryAccount(this.serverId, this.userId)

/**
 * Tells the saved library about sign-in and sign-out without ever failing the caller. A sign-in or sign-out must not
 * fail because rows could not be tidied: they are keyed by account and hold no credential, the token is already gone on
 * sign-out, and the next sign-in's [AccountCache.useAccount] deletes whatever is left.
 */
private class TolerantCache(
    private val cache: AccountCache,
) {
    suspend fun activate(account: LibraryAccount) {
        try {
            cache.useAccount(account)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // See above.
        }
    }

    suspend fun forget(account: LibraryAccount) {
        try {
            cache.forget(account)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // See above.
        }
    }
}

// The stored session aimed at [configured] when that is its https upgrade (the same host and path, http to https),
// never for any other difference such as `viaFallback`, which carries a confirmation of its own (ADR-0014, #209).
private fun StoredSession.overTheUpgrade(configured: ServerAddress): StoredSession {
    val upgrade = configured.scheme == Scheme.Https && address.scheme == Scheme.Http && configured.bindsTo(address)
    return if (upgrade) copy(address = configured) else this
}
