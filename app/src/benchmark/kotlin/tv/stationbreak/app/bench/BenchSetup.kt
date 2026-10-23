// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.LibraryDefaults
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PersistOutcome
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.RestoreOutcome
import tv.stationbreak.core.domain.RevokeOutcome
import tv.stationbreak.core.domain.SecretStore
import tv.stationbreak.core.domain.SecretStoreResult
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.core.model.Session
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The setup and sign-out the playback matrix runs without driving the onboarding screens (spec 13). It does what the
 * screens do, through the same domain calls: `discover` for the address, `quickConnect` for the sign-in,
 * [SessionRepository.save], the server record, and [LibraryDefaults] for the library choice the picker's Done stores
 * when its defaults are accepted. Every line it emits is one of `QC <code>`, `SETUP OK`, `SETUP ERROR <reason>`,
 * `SIGNOUT OK revoked|not-revoked` or `SIGNOUT ERROR <reason>`. Reasons are short fixed codes: never an address, a
 * token, a user, a library name or a message that could hold one.
 */
class BenchSetup(
    private val server: MediaServer,
    private val sessions: SessionRepository,
    private val settings: ServerSettings,
    private val secrets: SecretStore,
    private val emit: (String) -> Unit,
    private val waitLimit: Duration = 6.minutes,
) {
    /**
     * Signs in to the server [input] names and stores the default libraries, or says why not. A stored session is never
     * replaced. Once the session is issued, the steps that store it cannot be cancelled, and unless they all finish
     * (and the caller is still there) it is signed out again, so the device is left as it was found. Only one setup or
     * sign-out runs at a time in the process; another gets `busy`.
     */
    suspend fun setup(input: String) {
        if (!lock.tryLock()) {
            emit("SETUP ERROR ${SetupReason.BUSY}")
            return
        }
        var reason: String? = SetupReason.RUN_FAILED
        try {
            reason = trySetup(input)
        } catch (e: CancellationException) {
            reason = SetupReason.CANCELLED
            throw e
        } finally {
            emit(if (reason == null) "SETUP OK" else "SETUP ERROR $reason")
            lock.unlock()
        }
    }

    /**
     * Signs out the stored session; the server revokes its token. With [force], a server that cannot be asked no longer
     * stops it: the stored session is cleared on this device and the token is reported as not revoked.
     */
    suspend fun signOut(force: Boolean = false) {
        if (!lock.tryLock()) {
            emit("SIGNOUT ERROR ${SetupReason.BUSY}")
            return
        }
        try {
            emit(trySignOut(force))
        } finally {
            lock.unlock()
        }
    }

    private suspend fun trySignOut(force: Boolean): String {
        val address = settings.load()?.address ?: return signOutWithoutRecord(force)
        return when (val restored = sessions.restore(address)) {
            is RestoreOutcome.Restored -> {
                val revoked = sessions.signOut(restored.session) == RevokeOutcome.Revoked
                "SIGNOUT OK " + if (revoked) "revoked" else "not-revoked"
            }

            // The server refused it (and restore cleared it): the token is dead already.
            is RestoreOutcome.Rejected -> {
                "SIGNOUT OK not-revoked"
            }

            RestoreOutcome.SignedOut -> {
                "SIGNOUT ERROR no-session"
            }

            // Nothing was cleared, and there is no session to revoke with until the server answers.
            is RestoreOutcome.Unavailable -> {
                if (force) {
                    sessions.clear()
                    "SIGNOUT OK not-revoked"
                } else {
                    "SIGNOUT ERROR ${SetupReason.of(restored.error)}"
                }
            }
        }
    }

    // A session without a server record cannot be asked about; only force may drop it.
    private suspend fun signOutWithoutRecord(force: Boolean): String {
        val stored = (secrets.load(SessionRepository.SESSION_KEY) as? SecretStoreResult.Success)?.value
        if (!force || stored == null) return "SIGNOUT ERROR no-session"
        sessions.clear()
        return "SIGNOUT OK not-revoked"
    }

    @Suppress("ReturnCount") // Each early return is the step that failed.
    private suspend fun trySetup(input: String): String? {
        checkNoSession()?.let { return it }
        val resolved =
            when (val found = server.discover(input)) {
                is ServerResult.Success -> found.value
                is ServerResult.Failure -> return SetupReason.of(found.error)
            }
        // The operator typed an explicit http:// address on purpose, so the cleartext question the screen asks is
        // answered. An http address reached only by falling back from https is not: that is the downgrade it
        // asks about.
        val address = resolved.address
        val acknowledgement =
            when {
                !address.cleartextNeedsConfirmation -> null
                address.viaFallback -> return SetupReason.CLEARTEXT
                else -> CleartextAcknowledgement(address)
            }
        val terminal =
            withTimeoutOrNull(waitLimit) {
                server
                    .quickConnect(address, acknowledgement)
                    .onEach { if (it is QuickConnectState.CodeIssued) emit("QC ${it.code}") }
                    .firstOrNull { it !is QuickConnectState.CodeIssued && it !is QuickConnectState.Waiting }
            }
        return when (terminal) {
            is QuickConnectState.SignedIn -> store(terminal.session)
            QuickConnectState.Denied -> SetupReason.QC_DENIED
            is QuickConnectState.Failed -> SetupReason.of(terminal.error)
            else -> SetupReason.QC_EXPIRED
        }
    }

    /**
     * Null when no session is stored. A stored session blob counts even with no server record, and a store that cannot
     * be read right now is "can't tell": setup refuses both, and never overwrites.
     */
    private suspend fun checkNoSession(): String? =
        when (val stored = secrets.load(SessionRepository.SESSION_KEY)) {
            is SecretStoreResult.Failure -> SetupReason.STORE_UNREADABLE
            is SecretStoreResult.Success -> if (stored.value != null) SetupReason.ALREADY_SIGNED_IN else null
        }

    /** Runs the store steps to their end whatever the caller does, and undoes them unless all finished in time. */
    private suspend fun store(session: Session): String? {
        val caller = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            var complete = false
            try {
                val failure = storeAll(session)
                // A caller that went away while this ran was told "cancelled": leave nothing behind for it.
                complete = failure == null && caller?.isActive != false
                if (failure == null && !complete) SetupReason.CANCELLED else failure
            } finally {
                if (!complete) sessions.signOut(session)
            }
        }
    }

    @Suppress("ReturnCount") // Each early return is the step that failed.
    private suspend fun storeAll(session: Session): String? {
        if (settings.load()?.address != session.address && !settings.save(ServerRecord(session.address))) {
            return SetupReason.SETTINGS_NOT_SAVED
        }
        if (sessions.save(session) == PersistOutcome.NotSaved) return SetupReason.SESSION_NOT_SAVED
        // A new sign-in starts the library choice again, as the sign-in screen does.
        settings.clearLibraries()
        val libraries =
            when (val listed = server.libraries(session)) {
                is ServerResult.Success -> listed.value
                is ServerResult.Failure -> return SetupReason.of(listed.error)
            }
        val ticked = LibraryDefaults.ticked(libraries)
        if (ticked.isEmpty()) return SetupReason.NO_LIBRARIES
        return if (settings.saveLibraries(ticked)) null else SetupReason.LIBRARIES_NOT_SAVED
    }

    private companion object {
        // One setup or sign-out per process: two at once would race on the one stored session.
        val lock = Mutex()
    }
}

/** The short codes a setup or sign-out can fail with. A pure mapping, so it is unit-tested without Android. */
object SetupReason {
    const val ALREADY_SIGNED_IN = "already-signed-in"
    const val STORE_UNREADABLE = "store-unreadable"
    const val BUSY = "busy"
    const val CANCELLED = "cancelled"
    const val RUN_FAILED = "run-failed"
    const val CLEARTEXT = "cleartext"
    const val QC_EXPIRED = "qc-expired"
    const val QC_DENIED = "qc-denied"
    const val SETTINGS_NOT_SAVED = "settings-not-saved"
    const val SESSION_NOT_SAVED = "session-not-saved"
    const val NO_LIBRARIES = "no-libraries"
    const val LIBRARIES_NOT_SAVED = "libraries-not-saved"

    @Suppress("CyclomaticComplexMethod") // One branch per family of errors.
    fun of(error: MediaServerError): String =
        when (error) {
            is MediaServerError.InvalidAddress -> {
                "bad-server"
            }

            is MediaServerError.DnsFailure,
            is MediaServerError.ConnectionRefused,
            is MediaServerError.NetworkUnreachable,
            is MediaServerError.Timeout,
            -> {
                "unreachable"
            }

            is MediaServerError.TlsUntrusted,
            is MediaServerError.TlsHostnameMismatch,
            is MediaServerError.TlsCertificateExpired,
            is MediaServerError.TlsCertificateNotYetValid,
            is MediaServerError.TlsCertificateChanged,
            is MediaServerError.TlsHandshakeFailed,
            is MediaServerError.NotTls,
            -> {
                "tls"
            }

            is MediaServerError.NotJellyfin,
            is MediaServerError.UnsupportedVersion,
            is MediaServerError.ServerNotSetUp,
            -> {
                "unsupported-server"
            }

            is MediaServerError.CleartextNotConfirmed -> {
                CLEARTEXT
            }

            is MediaServerError.QuickConnectDisabled -> {
                "qc-disabled"
            }

            is MediaServerError.HttpUnauthorized,
            is MediaServerError.HttpForbidden,
            is MediaServerError.SessionExpired,
            -> {
                "refused"
            }

            else -> {
                "server-error"
            }
        }
}
