// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.CleartextAcknowledgement
import javax.inject.Inject

/** A server that answered a Test, and the acknowledgement the user gave for cleartext to it, if one was needed. */
data class ConnectedServer(
    val server: ResolvedServer,
    val acknowledgement: CleartextAcknowledgement?,
)

/** Where the server screen is. There is never a password or token here: Unit 1 sends none. */
internal sealed interface ServerPhase {
    data object Idle : ServerPhase

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
}

internal data class ServerUiState(
    val input: String = "",
    val phase: ServerPhase = ServerPhase.Idle,
)

/** The server screen (S0.5.1): tests the typed address with [MediaServer.discover] and explains what came back. */
@HiltViewModel
internal class ServerViewModel
    @Inject
    constructor(
        private val mediaServer: MediaServer,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(ServerUiState())
        val state: StateFlow<ServerUiState> = mutableState.asStateFlow()

        private var testJob: Job? = null

        fun onInputChange(text: String) {
            testJob?.cancel()
            mutableState.value = ServerUiState(input = text)
        }

        fun onTest() {
            if (mutableState.value.phase == ServerPhase.Testing) return
            val typed = mutableState.value.input.trim()
            if (typed.isEmpty()) {
                mutableState.update { it.copy(phase = ServerPhase.InvalidInput(invalidAddressCopy)) }
                return
            }
            mutableState.update { it.copy(phase = ServerPhase.Testing) }
            testJob =
                viewModelScope.launch {
                    val phase = phaseFor(mediaServer.discover(typed))
                    mutableState.update { it.copy(phase = phase) }
                }
        }

        fun onCancel() {
            testJob?.cancel()
            mutableState.update { it.copy(phase = ServerPhase.Idle) }
        }

        /** Edit address: back to a quiet screen, with the typed text kept. */
        fun onEditAddress() {
            mutableState.update { it.copy(phase = ServerPhase.Idle) }
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

        private fun phaseFor(result: ServerResult<ResolvedServer>): ServerPhase =
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
                    if (result.error is MediaServerError.InvalidAddress) {
                        ServerPhase.InvalidInput(copy)
                    } else {
                        ServerPhase.Failed(result.error, copy)
                    }
                }
            }

        private companion object {
            val invalidAddressCopy = ErrorCopyTable.copyFor(MediaServerError.InvalidAddress())
        }
    }
