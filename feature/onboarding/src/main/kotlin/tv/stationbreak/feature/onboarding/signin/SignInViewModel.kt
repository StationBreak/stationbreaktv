// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PersistOutcome
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.Session
import tv.stationbreak.core.model.SignInRequest
import tv.stationbreak.feature.onboarding.server.ConnectedServer
import tv.stationbreak.feature.onboarding.server.ErrorCopy
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.server.ServerHandOff
import javax.inject.Inject

/**
 * What sign-in hands to the next destination (the library picker, spec 05 Unit 4): the signed-in [session], with the
 * rights it was issued ([Session.isAdministrator] is recorded, never acted on: decision 9), and whether it reached the
 * secret store. When [persisted] is false the session lives in memory only and a restart signs out (F0.4).
 */
data class SignInResult(
    val session: Session,
    val persisted: Boolean,
    val username: String? = null,
) {
    // The user's name is theirs, and the session holds the token: neither belongs in a log line or a crash report.
    override fun toString(): String = "SignInResult(persisted=$persisted, session=<redacted>, username=<redacted>)"
}

/** Why a Quick Connect attempt ended without a session. The form is back, with a line saying which. */
internal enum class QuickConnectEnd { Expired, Denied }

/** Where the sign-in screen is. The password is never in a phase. */
internal sealed interface SignInPhase {
    data object Idle : SignInPhase

    /** A password sign-in is running. The password has already been cleared from the state. */
    data object SigningIn : SignInPhase

    /** The username is blank. No request was sent. */
    data class InvalidInput(
        val copy: ErrorCopy,
    ) : SignInPhase

    /**
     * A problem the panel explains. [retypePassword] is true for a rejected username or password (401, 403): the next
     * step is to type the password again, so there is no button. Focus goes to the password field for those and for a
     * problem that may pass on its own (see [focusesPassword]); for a problem with the address it goes to Edit address.
     */
    data class Failed(
        val error: MediaServerError,
        val copy: ErrorCopy,
        val retypePassword: Boolean,
    ) : SignInPhase {
        /**
         * A timeout, an unreachable network, a server error or an unknown one is likely to pass: trying again means
         * pressing Sign in, so focus stays on the password and Edit address waits below. Anything about the address
         * itself (DNS, refused, not Jellyfin, not found, redirected, invalid, TLS, cleartext) puts focus on Edit
         * address.
         */
        val focusesPassword: Boolean
            get() =
                retypePassword ||
                    error is MediaServerError.Timeout ||
                    error is MediaServerError.NetworkUnreachable ||
                    error is MediaServerError.ServerError ||
                    error is MediaServerError.Unknown
    }

    /** Quick Connect is running. [code] is null until the server issues one. */
    data class QuickConnect(
        val code: String?,
    ) : SignInPhase

    data object QuickConnectDisabled : SignInPhase

    data class QuickConnectEnded(
        val reason: QuickConnectEnd,
    ) : SignInPhase

    /** Signed in and saved. The library picker takes over from here; until it does, the screen shows the status. */
    data object SignedIn : SignInPhase

    /**
     * Launch routing found the stored session refused (expired, or another server's). One line says why; typing
     * clears it, like any message about an attempt.
     */
    data class SessionEnded(
        val copy: ErrorCopy,
    ) : SignInPhase
}

/**
 * What the sign-in screen shows. Names and flags only: the address and the cleartext acknowledgement stay in the
 * in-memory [ServerHandOff], and the password is here only between keystrokes and Sign in.
 */
internal data class SignInUiState(
    val serverName: String = "",
    val cleartext: Boolean = false,
    val username: String = "",
    val password: String = "",
    val showPassword: Boolean = false,
    val phase: SignInPhase = SignInPhase.Idle,
) {
    // The password is a credential: it must not appear in a log line or a crash report built from the state.
    override fun toString(): String = "SignInUiState(phase=${phase::class.simpleName}, password=***)"
}

/**
 * Sign-in and Quick Connect (S0.5.1). There is one sign-in: the account that signs in is the app's user, admin or not
 * (decision 9), so nothing here looks at [Session.isAdministrator].
 *
 * Security (Angua): there is no `SavedStateHandle` in this class, so no field of it can reach the saved-instance
 * Bundle. The password lives in [state] only until Sign in, when it is cleared before the request starts, and again on
 * [onLeave]. Nothing is logged.
 */
@Suppress("TooManyFunctions") // The screen's actions, one function each, like ServerViewModel.
@HiltViewModel
internal class SignInViewModel
    @Inject
    constructor(
        private val mediaServer: MediaServer,
        private val sessions: SessionRepository,
        private val handOff: ServerHandOff,
        private val signedInHandOff: SignedInHandOff,
        private val settings: ServerSettings,
    ) : ViewModel() {
        private val connected: ConnectedServer? = handOff.peek()

        /** True when the server screen's hand-off is gone (process death): the screen goes back to it. */
        val missingServer: Boolean get() = connected == null

        private val mutableState =
            MutableStateFlow(
                SignInUiState(
                    serverName =
                        connected
                            ?.server
                            ?.server
                            ?.name
                            .orEmpty(),
                    cleartext = connected?.acknowledgement != null,
                    phase =
                        connected?.signedOutBecause?.let { SignInPhase.SessionEnded(ErrorCopyTable.copyFor(it)) }
                            ?: SignInPhase.Idle,
                ),
            )
        val state: StateFlow<SignInUiState> = mutableState.asStateFlow()

        private val results = Channel<SignInResult>(Channel.BUFFERED)

        /** Fires once per successful sign-in, after the session has been saved. */
        val signedIn: Flow<SignInResult> = results.receiveAsFlow()

        private var signInJob: Job? = null
        private var quickConnectJob: Job? = null

        /**
         * Names the attempt the user is waiting for. Every Sign in and Quick Connect takes the next number, and Cancel
         * and leaving move it on, so an answer that arrives later sees its own number is no longer current and is
         * dropped (or, if it carries a session, revoked) instead of changing a screen it no longer belongs to.
         */
        private var attempt = 0

        fun onUsernameChange(text: String) = mutableState.update { it.copy(username = text, phase = settled(it.phase)) }

        fun onPasswordChange(text: String) = mutableState.update { it.copy(password = text, phase = settled(it.phase)) }

        fun onToggleShowPassword() = mutableState.update { it.copy(showPassword = !it.showPassword) }

        fun onSignIn() {
            val server = connected
            val current = mutableState.value
            if (server == null || current.phase.isBusy) return
            val username = current.username.trim()
            if (username.isEmpty()) {
                mutableState.update { it.copy(phase = SignInPhase.InvalidInput(SignInCopy.blankUsername)) }
                return
            }
            // The password leaves the state before the request starts, and the array is wiped when the call ends.
            val password = current.password.toCharArray()
            val id = ++attempt
            mutableState.update { it.copy(password = "", phase = SignInPhase.SigningIn) }
            signInJob =
                viewModelScope.launch {
                    try {
                        val request = SignInRequest(server.server.address, username, password, server.acknowledgement)
                        when (val result = mediaServer.signIn(request)) {
                            is ServerResult.Success -> complete(result.value, id, username)
                            is ServerResult.Failure -> fail(result.error, id)
                        }
                    } finally {
                        password.fill('\u0000')
                    }
                }
        }

        fun onQuickConnect() {
            val server = connected ?: return
            if (mutableState.value.phase.isBusy) return
            quickConnectJob?.cancel()
            val id = ++attempt
            mutableState.update { it.copy(phase = SignInPhase.QuickConnect(code = null)) }
            quickConnectJob =
                viewModelScope.launch {
                    mediaServer
                        .quickConnect(server.server.address, server.acknowledgement)
                        .collect { applyQuickConnect(it, id) }
                }
        }

        /** Cancel on the Quick Connect panel (and Back): the flow stops and the form is back. */
        fun onCancelQuickConnect() {
            if (mutableState.value.phase !is SignInPhase.QuickConnect) return
            attempt++
            quickConnectJob?.cancel()
            mutableState.update { it.copy(phase = SignInPhase.Idle) }
        }

        /**
         * The screen is being left: nothing keeps running, no password is kept, and an answer still on its way is no
         * longer wanted (a session in it is revoked, not saved).
         */
        fun onLeave() {
            attempt++
            quickConnectJob?.cancel()
            mutableState.update {
                val waiting = it.phase == SignInPhase.SigningIn || it.phase is SignInPhase.QuickConnect
                it.copy(password = "", phase = if (waiting) SignInPhase.Idle else it.phase)
            }
        }

        override fun onCleared() {
            onLeave()
            // Only what this screen was given: a newer screen's server stays.
            connected?.let(handOff::clear)
        }

        private suspend fun applyQuickConnect(
            update: QuickConnectState,
            id: Int,
        ) {
            // A session is acted on whatever the screen is doing: complete() saves it or revokes it. Anything else
            // from an attempt that is no longer the current one is dropped.
            if (update is QuickConnectState.SignedIn) {
                complete(update.session, id, username = null)
                return
            }
            if (id != attempt) return
            when (update) {
                is QuickConnectState.CodeIssued -> {
                    mutableState.update { it.copy(phase = SignInPhase.QuickConnect(update.code)) }
                }

                QuickConnectState.Waiting -> {
                    Unit
                }

                is QuickConnectState.SignedIn -> {
                    Unit
                }

                QuickConnectState.Expired -> {
                    ended(QuickConnectEnd.Expired)
                }

                QuickConnectState.Denied -> {
                    ended(QuickConnectEnd.Denied)
                }

                is QuickConnectState.Failed -> {
                    if (update.error is MediaServerError.QuickConnectDisabled) {
                        mutableState.update { it.copy(phase = SignInPhase.QuickConnectDisabled) }
                    } else {
                        fail(update.error, id)
                    }
                }
            }
        }

        private fun ended(reason: QuickConnectEnd) {
            mutableState.update { it.copy(phase = SignInPhase.QuickConnectEnded(reason)) }
        }

        /**
         * Saves the session only if the user is still waiting for it, then hands it on. If they cancelled, left or the
         * screen is gone (checked before and again after the save, which cannot be interrupted: the token is already
         * issued), the session is signed out instead: the store is cleared and the server revokes the token. Nothing
         * is handed over and the screen is not moved to [SignInPhase.SignedIn].
         */
        private suspend fun complete(
            session: Session,
            id: Int,
            username: String?,
        ) {
            val outcome =
                withContext(NonCancellable) {
                    // Leaving, cancelling and the view model being cleared (onCleared leaves) all move [attempt] on.
                    fun waiting() = id == attempt
                    if (!waiting()) {
                        sessions.signOut(session)
                        return@withContext null
                    }
                    val saved = sessions.save(session)
                    if (!waiting()) {
                        sessions.signOut(session)
                        return@withContext null
                    }
                    // A session that couldn't be saved must not leave an older one behind to be restored later.
                    if (saved == PersistOutcome.NotSaved) sessions.clear()
                    // A new sign-in starts the library choice again: another user's choice is not this one's, and a
                    // restart must ask for it instead of finding it done.
                    settings.clearLibraries()
                    saved
                } ?: return
            connected?.let(handOff::clear)
            val result = SignInResult(session, persisted = outcome == PersistOutcome.Saved, username = username)
            // In memory, for the library picker and for "Signed in as" (see SignedInHandOff).
            signedInHandOff.put(result)
            mutableState.update { it.copy(password = "", phase = SignInPhase.SignedIn) }
            results.send(result)
        }

        private fun fail(
            error: MediaServerError,
            id: Int,
        ) {
            val retype = error is MediaServerError.HttpUnauthorized || error is MediaServerError.HttpForbidden
            val failed = SignInPhase.Failed(error, ErrorCopyTable.copyFor(error), retypePassword = retype)
            // Drop a late answer if the user has cancelled, left or started something else since.
            if (id == attempt) mutableState.update { it.copy(phase = failed) }
        }

        /** Typing is the user's own move: it clears a message about the last attempt. */
        private fun settled(phase: SignInPhase): SignInPhase =
            when (phase) {
                is SignInPhase.InvalidInput,
                is SignInPhase.Failed,
                is SignInPhase.QuickConnectEnded,
                is SignInPhase.SessionEnded,
                SignInPhase.QuickConnectDisabled,
                -> SignInPhase.Idle

                else -> phase
            }

        private val SignInPhase.isBusy: Boolean
            get() = this == SignInPhase.SigningIn || this is SignInPhase.QuickConnect || this == SignInPhase.SignedIn
    }
