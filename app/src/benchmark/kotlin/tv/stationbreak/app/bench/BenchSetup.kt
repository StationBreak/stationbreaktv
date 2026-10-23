// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.LibraryDefaults
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PersistOutcome
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.RestoreOutcome
import tv.stationbreak.core.domain.RevokeOutcome
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
    private val emit: (String) -> Unit,
    private val waitLimit: Duration = 6.minutes,
) {
    /**
     * Signs in to the server [input] names and stores the default libraries, or says why not. A stored session is never
     * replaced. A failure after the session was issued signs it out again, so the device is left as it was found.
     */
    suspend fun setup(input: String) {
        val reason = trySetup(input)
        emit(if (reason == null) "SETUP OK" else "SETUP ERROR $reason")
    }

    /** Signs out the stored session; the server revokes its token. */
    suspend fun signOut() {
        val address = settings.load()?.address
        if (address == null) {
            emit("SIGNOUT ERROR no-session")
            return
        }
        when (val restored = sessions.restore(address)) {
            is RestoreOutcome.Restored -> {
                val revoked = sessions.signOut(restored.session) == RevokeOutcome.Revoked
                emit("SIGNOUT OK " + if (revoked) "revoked" else "not-revoked")
            }

            // The server refused it (and restore cleared it): the token is dead already.
            is RestoreOutcome.Rejected -> {
                emit("SIGNOUT OK not-revoked")
            }

            RestoreOutcome.SignedOut -> {
                emit("SIGNOUT ERROR no-session")
            }

            // Nothing was cleared, and there is no session to revoke with until the server answers.
            is RestoreOutcome.Unavailable -> {
                emit("SIGNOUT ERROR ${SetupReason.of(restored.error)}")
            }
        }
    }

    @Suppress("ReturnCount") // Each early return is the step that failed.
    private suspend fun trySetup(input: String): String? {
        if (hasSession()) return SetupReason.ALREADY_SIGNED_IN
        val resolved =
            when (val found = server.discover(input)) {
                is ServerResult.Success -> found.value
                is ServerResult.Failure -> return SetupReason.of(found.error)
            }
        // The operator typed this address on purpose, so the cleartext question the screen asks is already answered.
        val address = resolved.address
        val acknowledgement = if (address.cleartextNeedsConfirmation) CleartextAcknowledgement(address) else null
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

    /** True when a session is stored (or one is stored but the server can't be asked): setup never overwrites it. */
    private suspend fun hasSession(): Boolean {
        val address = settings.load()?.address ?: return false
        return when (sessions.verify(address)) {
            is RestoreOutcome.Restored, is RestoreOutcome.Unavailable -> true
            RestoreOutcome.SignedOut, is RestoreOutcome.Rejected -> false
        }
    }

    private suspend fun store(session: Session): String? {
        val failure = storeAll(session)
        // A half-done setup is undone, so the device is signed out and a retry starts clean.
        if (failure != null) sessions.signOut(session)
        return failure
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
}

/** The short codes a setup or sign-out can fail with. A pure mapping, so it is unit-tested without Android. */
object SetupReason {
    const val ALREADY_SIGNED_IN = "already-signed-in"
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
                "cleartext"
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
