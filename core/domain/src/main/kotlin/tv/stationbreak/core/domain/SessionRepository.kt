// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import tv.stationbreak.core.model.AccessToken
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.SecretAccess
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.StoredSession
import tv.stationbreak.core.model.UserId

/** Whether a [SessionRepository.save] reached the [SecretStore]. */
enum class PersistOutcome { Saved, NotSaved }

/** Whether the server forgot the token on sign-out. After [NotRevoked] the user may need to revoke the device. */
enum class RevokeOutcome { Revoked, NotRevoked }

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

    /**
     * Stores [session]. Its account becomes the active one in the saved library, whether or not the token reached
     * storage: the user is signed in for this run, so every other account's saved library goes now (spec 06 task 2.12).
     */
    suspend fun save(session: Session): PersistOutcome {
        val outcome = write(session.toStored())
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
        val raw = (store.load(SESSION_KEY) as? SecretStoreResult.Success)?.value ?: return RestoreOutcome.SignedOut
        val stored = decode(raw)
        return when {
            stored == null -> {
                clear()
                RestoreOutcome.SignedOut
            }

            !stored.address.bindsTo(configured) && !configured.bindsTo(stored.address) -> {
                clear()
                RestoreOutcome.Rejected(MediaServerError.ServerMismatch())
            }

            else -> {
                resume(stored)
            }
        }
    }

    private suspend fun resume(stored: StoredSession): RestoreOutcome =
        when (val result = server.resume(stored)) {
            is ServerResult.Success -> {
                if (result.value.address != stored.address) write(result.value.toStored())
                library.activate(result.value.libraryAccount())
                RestoreOutcome.Restored(result.value)
            }

            is ServerResult.Failure -> {
                if (onServerError(result.error)) {
                    RestoreOutcome.Rejected(result.error)
                } else {
                    RestoreOutcome.Unavailable(result.error)
                }
            }
        }

    /** Clears the stored session if [error] says it can't be used (`SessionExpired`, `ServerMismatch`). */
    suspend fun onServerError(error: MediaServerError): Boolean {
        val fatal = error is MediaServerError.SessionExpired || error is MediaServerError.ServerMismatch
        if (fatal) clear()
        return fatal
    }

    suspend fun clear() {
        store.clear(SESSION_KEY)
    }

    /**
     * Clears the stored session first, then asks the server to revoke the token, all in [NonCancellable] so a
     * cancelled caller still finishes (R12). The device ID is kept (R13).
     */
    suspend fun signOut(session: Session): RevokeOutcome =
        withContext(NonCancellable) {
            clear()
            library.forget(session.libraryAccount())
            when (server.revoke(session)) {
                is ServerResult.Success -> RevokeOutcome.Revoked
                is ServerResult.Failure -> RevokeOutcome.NotRevoked
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
        const val FORMAT_VERSION = 1
        private val JSON = Json { encodeDefaults = true }
    }
}

private fun Session.libraryAccount() = LibraryAccount(this.server.id, this.userId)

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
