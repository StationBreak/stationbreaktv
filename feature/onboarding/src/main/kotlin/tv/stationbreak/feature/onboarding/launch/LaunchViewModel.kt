// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.feature.onboarding.server.ConnectedServer
import tv.stationbreak.feature.onboarding.server.ErrorCopy
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.server.ServerHandOff
import tv.stationbreak.feature.onboarding.signin.SignInResult
import tv.stationbreak.feature.onboarding.signin.SignedInHandOff
import tv.stationbreak.feature.onboarding.trust.CertificateTrust
import tv.stationbreak.feature.onboarding.trust.TrustOutcome
import tv.stationbreak.feature.onboarding.trust.TrustPrompt
import tv.stationbreak.feature.onboarding.trust.toTrustPrompt
import javax.inject.Inject

/** What the launch screen shows while it has not yet moved the app on. */
internal sealed interface LaunchPhase {
    /** The stored settings and session are being checked. A plain progress screen: onboarding never flashes. */
    data object Routing : LaunchPhase

    /** The server could not be asked. The panel explains why and offers Retry, never a way back into setup. */
    data class Unavailable(
        val error: MediaServerError,
        val copy: ErrorCopy,
    ) : LaunchPhase

    /**
     * The stored server shows a certificate the user may trust (it changed, or the pin is gone): the trust screen of
     * Unit 3, over the progress screen. [input] is the stored address as a URL, the text the pin is made for.
     */
    data class Trust(
        val input: String,
        val prompt: TrustPrompt,
        val error: MediaServerError,
        val address: ServerAddress,
    ) : LaunchPhase {
        // The prompt shows nothing of the certificate; the address is the user's own and is not logged.
        override fun toString(): String = "Trust(prompt=$prompt)"
    }

    /** The user said yes to the certificate and it is being checked and stored. */
    data object Trusting : LaunchPhase
}

/** Where launch routing sends the app. Each is the last thing this screen does. */
internal enum class LaunchTarget { NoServer, SignIn, Libraries, FirstSync, SetupComplete }

/**
 * Launch routing (spec 05, Unit 4): runs [LaunchRouter] at startup and again on Retry, hands what the next screen
 * needs over in memory, and answers the certificate question for a stored server whose certificate is not the one
 * trusted before. Accepting a certificate drops the stored session (decision 10 told the user so on the button) and
 * continues at sign-in; refusing, or Back, leaves the error with Retry.
 */
@HiltViewModel
internal class LaunchViewModel
    @Inject
    constructor(
        private val router: LaunchRouter,
        private val serverHandOff: ServerHandOff,
        private val signedIn: SignedInHandOff,
        private val certificateTrust: CertificateTrust,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<LaunchPhase>(LaunchPhase.Routing)
        val state: StateFlow<LaunchPhase> = mutableState.asStateFlow()

        private val results = Channel<LaunchTarget>(Channel.BUFFERED)

        /** Fires once when routing has decided. */
        val targets: Flow<LaunchTarget> = results.receiveAsFlow()

        // The route in flight. A new one cancels it, so a late answer from the old one can't move the app on.
        private var routeJob: Job? = null

        init {
            route()
        }

        /** Retry: checks again from the start. */
        fun onRetry() = route()

        private fun route() {
            routeJob?.cancel()
            mutableState.value = LaunchPhase.Routing
            routeJob =
                viewModelScope.launch {
                    when (val route = router.route()) {
                        LaunchRoute.NoServer -> {
                            results.send(LaunchTarget.NoServer)
                        }

                        is LaunchRoute.SignIn -> {
                            serverHandOff.put(route.server)
                            results.send(LaunchTarget.SignIn)
                        }

                        is LaunchRoute.Libraries -> {
                            // Restored from the secret store, so it is persisted. No name is known for it.
                            signedIn.put(SignInResult(route.session, persisted = true, username = null))
                            results.send(LaunchTarget.Libraries)
                        }

                        LaunchRoute.FirstSync -> {
                            results.send(LaunchTarget.FirstSync)
                        }

                        LaunchRoute.SetupComplete -> {
                            results.send(LaunchTarget.SetupComplete)
                        }

                        is LaunchRoute.Unavailable -> {
                            mutableState.value = unavailable(route.error, route.address)
                        }
                    }
                }
        }

        private fun unavailable(
            error: MediaServerError,
            address: ServerAddress,
        ): LaunchPhase {
            val prompt = error.toTrustPrompt()
            return if (prompt != null) {
                LaunchPhase.Trust(address.baseUrl, prompt, error, address)
            } else {
                LaunchPhase.Unavailable(error, ErrorCopyTable.copyFor(error))
            }
        }

        /** Trust the new certificate: pins the SPKI the failed handshake carried, drops the session, signs in again. */
        fun onTrust() {
            val asked = mutableState.value as? LaunchPhase.Trust ?: return
            mutableState.value = LaunchPhase.Trusting
            viewModelScope.launch {
                when (val outcome = confirmed(asked)) {
                    is TrustOutcome.Trusted -> {
                        serverHandOff.put(ConnectedServer(outcome.server, acknowledgement = null))
                        results.send(LaunchTarget.SignIn)
                    }

                    is TrustOutcome.Refused -> {
                        mutableState.value = unavailable(outcome.error, asked.address)
                    }

                    TrustOutcome.NotSaved -> {
                        mutableState.value =
                            LaunchPhase.Unavailable(MediaServerError.Unknown(), ErrorCopyTable.trustNotSaved)
                    }
                }
            }
        }

        // Runs to its end even if this view model is cleared: the pin and the record are stored together or taken back
        // together, never left half-done by a cancellation. A bug or a failing store ends in the plain error, not in
        // Trusting for good.
        private suspend fun confirmed(asked: LaunchPhase.Trust): TrustOutcome =
            try {
                withContext(NonCancellable) { certificateTrust.confirm(asked.input, asked.prompt) }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                if (e is CancellationException) throw e
                TrustOutcome.Refused(MediaServerError.Unknown())
            }

        /** Don't trust, and Back from the trust screen: nothing is pinned, and the error stays with Retry. */
        fun onDontTrust() {
            val asked = mutableState.value as? LaunchPhase.Trust ?: return
            mutableState.value = LaunchPhase.Unavailable(asked.error, ErrorCopyTable.copyFor(asked.error))
        }
    }
