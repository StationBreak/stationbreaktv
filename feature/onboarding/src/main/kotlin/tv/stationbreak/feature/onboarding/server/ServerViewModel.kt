// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.CleartextAcknowledgement
import tv.stationbreak.feature.onboarding.signin.SignedInHandOff
import tv.stationbreak.feature.onboarding.trust.CertificateTrust
import tv.stationbreak.feature.onboarding.trust.TrustOutcome
import tv.stationbreak.feature.onboarding.trust.TrustPrompt
import tv.stationbreak.feature.onboarding.trust.toTrustPrompt
import javax.inject.Inject

/**
 * A server that answered a Test, and the acknowledgement the user gave for cleartext to it, if one was needed.
 * [signedOutBecause] is set only by launch routing, when a stored session was refused (`SessionExpired`,
 * `ServerMismatch`): sign-in says so in one line.
 */
data class ConnectedServer(
    val server: ResolvedServer,
    val acknowledgement: CleartextAcknowledgement?,
    val signedOutBecause: MediaServerError? = null,
)

/** Where the server screen is. There is never a password or token here: Unit 1 sends none. */
internal sealed interface ServerPhase {
    data object Idle : ServerPhase

    /**
     * Idle after the user refused a certificate (Don't trust, or Back): the screen says it isn't connected and why. It
     * ends with the next Test or the next edit of the address.
     */
    data object NotTrusted : ServerPhase

    /** `discover` is running. The screen shows this at once and offers Cancel. */
    data object Testing : ServerPhase

    /** The address can't be used. Shown under the field; for a blank field no request was sent. */
    data class InvalidInput(
        val copy: ErrorCopy,
    ) : ServerPhase

    /** A problem the panel explains, with its next step. */
    data class Failed(
        val error: MediaServerError,
        val copy: ErrorCopy,
    ) : ServerPhase

    /** `http` to a public host, or `http` reached by fallback: ask before going on (ADR-0014). */
    data class ConfirmCleartext(
        val server: ResolvedServer,
    ) : ServerPhase

    data class Connected(
        val server: ResolvedServer,
        val acknowledgement: CleartextAcknowledgement?,
    ) : ServerPhase

    /**
     * A self-signed certificate (or a changed one) the user may trust or refuse (spec 05, Unit 3). [input] is the text
     * the failed Test used, so the pin is made for exactly that address. The state holds [prompt], whose `toString`
     * shows nothing of the certificate.
     */
    data class TrustRequired(
        val input: String,
        val prompt: TrustPrompt,
    ) : ServerPhase
}

internal data class ServerUiState(
    val input: String = "",
    val phase: ServerPhase = ServerPhase.Idle,
)

/**
 * The server screen (S0.5.1): tests the typed address with [MediaServer.discover] and explains what came back. A
 * self-signed certificate becomes a question for the user (S0.5.2): [onTrustCertificate] pins the SPKI the failed
 * handshake carried and probes again before anything goes on; [onDontTrust] leaves things as they were.
 */
@HiltViewModel
@Suppress("TooManyFunctions") // The screen's actions, one function each, like SignInViewModel.
internal class ServerViewModel
    @Inject
    constructor(
        private val mediaServer: MediaServer,
        private val handOff: ServerHandOff,
        private val signedIn: SignedInHandOff,
        private val settings: ServerSettings,
        private val certificateTrust: CertificateTrust,
        private val sessions: SessionRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(ServerUiState())
        val state: StateFlow<ServerUiState> = mutableState.asStateFlow()

        private var testJob: Job? = null

        // Typing wins over the stored address: it is only put in a field the user has not touched.
        private var edited = false

        init {
            // The configured server is shown for changing, never a blank form: Change server on the launch screen and
            // Back from a sign-in that launch routing opened both land here. First launch has nothing stored.
            viewModelScope.launch {
                val stored = settings.load()?.address ?: return@launch
                mutableState.update {
                    if (edited || it.input.isNotEmpty()) it else it.copy(input = stored.baseUrl)
                }
            }
        }

        // The last "clear the pin" that was started. It runs to its end even if this view model is cleared (a pin that
        // outlives the screen that should have dropped it is exactly what it is for), and a new Test waits for it.
        private var pendingClear: Job? = null

        fun onInputChange(text: String) {
            edited = true
            val running = testJob
            running?.cancel()
            // Another address is another server: a pin is for one exact address and goes with the edit.
            if (text != mutableState.value.input) clearPin(after = running)
            mutableState.value = ServerUiState(input = text)
        }

        fun onTest() {
            if (mutableState.value.phase == ServerPhase.Testing) return
            val typed = mutableState.value.input.trim()
            if (typed.isEmpty()) {
                mutableState.update { it.copy(phase = ServerPhase.InvalidInput(ErrorCopyTable.blankAddress)) }
                return
            }
            mutableState.update { it.copy(phase = ServerPhase.Testing) }
            testJob =
                viewModelScope.launch {
                    // A new Test starts without a pin: the certificate is asked about again, never assumed.
                    pendingClear?.join()
                    settings.clearPin()
                    finish(phaseFor(typed, mediaServer.discover(typed)))
                }
        }

        // A cancelled attempt may still return a result if the callee ignores cancellation. Drop it, and drop it too if
        // the screen has moved on from Testing (a newer Test, typing, Edit address).
        private suspend fun finish(phase: ServerPhase) {
            currentCoroutineContext().ensureActive()
            mutableState.update { if (it.phase == ServerPhase.Testing) it.copy(phase = phase) else it }
        }

        fun onCancel() {
            if (mutableState.value.phase != ServerPhase.Testing) return
            val running = testJob
            running?.cancel()
            // A trust attempt that is cancelled may still have pinned in its last moments: clear once it has ended.
            clearPin(after = running)
            mutableState.update { it.copy(phase = ServerPhase.Idle) }
        }

        /** Edit address: back to a quiet screen, with the typed text kept. */
        fun onEditAddress() {
            val running = testJob
            running?.cancel()
            clearPin(after = running)
            mutableState.update { it.copy(phase = ServerPhase.Idle) }
        }

        /**
         * Trust this certificate: pins the SPKI the failed handshake carried, and probes the address afresh on the
         * client that now trusts it. Nothing is stored unless that probe succeeds, and no credential is involved.
         */
        fun onTrustCertificate() {
            val asked = mutableState.value.phase as? ServerPhase.TrustRequired ?: return
            mutableState.update { it.copy(phase = ServerPhase.Testing) }
            testJob =
                viewModelScope.launch {
                    finish(phaseFor(asked.input, certificateTrust.confirm(asked.input, asked.prompt)))
                }
        }

        /**
         * Don't trust, and Back from the trust screen: nothing was pinned. The screen says it isn't connected and why,
         * with the address kept and focus on Test.
         */
        fun onDontTrust() {
            if (mutableState.value.phase !is ServerPhase.TrustRequired) return
            clearPin(after = null)
            mutableState.update { it.copy(phase = ServerPhase.NotTrusted) }
        }

        private fun clearPin(after: Job?) {
            pendingClear =
                viewModelScope.launch(NonCancellable) {
                    after?.join()
                    settings.clearPin()
                }
        }

        fun onConfirmCleartext() {
            val phase = mutableState.value.phase as? ServerPhase.ConfirmCleartext ?: return
            // The acknowledgement is built from exactly this address, so it can't be spent on another one.
            val acknowledgement = CleartextAcknowledgement(phase.server.address)
            mutableState.update { it.copy(phase = ServerPhase.Connected(phase.server, acknowledgement)) }
        }

        fun onDismissCleartext() {
            if (mutableState.value.phase is ServerPhase.ConfirmCleartext) onEditAddress()
        }

        /**
         * Continue: keeps the server for the sign-in screen, in memory only (see [ServerHandOff]), and stores it as the
         * configured server (spec 05, Unit 4) so a later launch finds it. The pin a trust screen stored for this very
         * address stays; any other address is saved without one, because the Test cleared it. A sign-in held from an
         * earlier pass is not this server's any more.
         */
        fun onContinue(server: ConnectedServer) {
            signedIn.clear()
            handOff.put(server)
            // Started before this screen can leave, so it is first in the settings' queue, ahead of anything sign-in or
            // the library picker writes; it runs to its end even if this view model is cleared at once.
            viewModelScope.launch(NonCancellable) {
                val address = server.server.address
                val old = settings.load()?.address
                if (old != address) {
                    settings.save(ServerRecord(address))
                    // The stored session belongs to the old server. It would be refused at the next restore anyway
                    // (a token is only ever sent to the server that issued it), but the user has now chosen another
                    // server, so it goes now rather than lingering.
                    if (old != null && !address.bindsTo(old) && !old.bindsTo(address)) sessions.clear()
                }
            }
        }

        private fun phaseFor(
            typed: String,
            outcome: TrustOutcome,
        ): ServerPhase =
            when (outcome) {
                is TrustOutcome.Trusted -> phaseFor(typed, ServerResult.Success(outcome.server))
                is TrustOutcome.Refused -> phaseFor(typed, ServerResult.Failure(outcome.error))
                TrustOutcome.NotSaved -> ServerPhase.Failed(MediaServerError.Unknown(), ErrorCopyTable.trustNotSaved)
            }

        private fun phaseFor(
            typed: String,
            result: ServerResult<ResolvedServer>,
        ): ServerPhase =
            when (result) {
                is ServerResult.Success -> {
                    val server = result.value
                    if (server.address.cleartextNeedsConfirmation) {
                        ServerPhase.ConfirmCleartext(server)
                    } else {
                        ServerPhase.Connected(server, acknowledgement = null)
                    }
                }

                is ServerResult.Failure -> {
                    val copy = ErrorCopyTable.copyFor(result.error)
                    val prompt = result.error.toTrustPrompt()
                    when {
                        prompt != null -> ServerPhase.TrustRequired(typed, prompt)
                        result.error is MediaServerError.InvalidAddress -> ServerPhase.InvalidInput(copy)
                        else -> ServerPhase.Failed(result.error, copy)
                    }
                }
            }
    }
