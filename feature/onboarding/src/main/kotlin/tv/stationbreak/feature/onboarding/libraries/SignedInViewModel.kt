// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.feature.onboarding.server.ConnectedServer
import tv.stationbreak.feature.onboarding.server.ServerHandOff
import tv.stationbreak.feature.onboarding.signin.SignInResult
import tv.stationbreak.feature.onboarding.signin.SignedInHandOff
import javax.inject.Inject

/**
 * What "Signed in as" shows: the [name] the user typed, which is null after Quick Connect or a restored session (the
 * server's answer to those carries no user name), so the screen names the server instead. [switching] is true while
 * the sign-out runs.
 */
internal data class SignedInUiState(
    val name: String?,
    val serverName: String,
    val switching: Boolean = false,
) {
    // The name is the user's own; keep it out of any line built from the state.
    override fun toString(): String = "SignedInUiState(switching=$switching)"
}

/**
 * Back from the library picker (spec 05, Unit 4): who is signed in, with Continue and Switch user. It never signs the
 * user out on its own: only Switch user does, and then the sign-in form comes back for the same server.
 */
@HiltViewModel
internal class SignedInViewModel
    @Inject
    constructor(
        private val sessions: SessionRepository,
        private val signedIn: SignedInHandOff,
        private val handOff: ServerHandOff,
        private val settings: ServerSettings,
    ) : ViewModel() {
        private val held: SignInResult? = signedIn.peek()

        /** True when no sign-in is held (the process was restarted under the screen): launch routing takes over. */
        val missingSession: Boolean get() = held == null

        private val mutableState =
            MutableStateFlow(
                SignedInUiState(
                    name = held?.username,
                    serverName =
                        held
                            ?.session
                            ?.server
                            ?.name
                            .orEmpty(),
                ),
            )
        val state: StateFlow<SignedInUiState> = mutableState.asStateFlow()

        private val results = Channel<Unit>(Channel.BUFFERED)

        /** Fires once the user has been signed out and the sign-in form has its server again. */
        val switched: Flow<Unit> = results.receiveAsFlow()

        /**
         * Switch user: clears the stored session and asks the server to forget the token ([SessionRepository.signOut]),
         * then hands the same server to the sign-in form.
         */
        fun onSwitchUser() {
            val result = held ?: return
            if (mutableState.value.switching) return
            mutableState.update { it.copy(switching = true) }
            viewModelScope.launch {
                val session = result.session
                // First: once the user has chosen to switch, nothing may find this session to use while the
                // sign-out is still on its way (it runs to its end even if this screen is gone).
                signedIn.clear()
                sessions.signOut(session)
                // The next user chooses their libraries afresh, so a restart can't find setup done for the last one.
                settings.clearLibraries()
                handOff.put(ConnectedServer(ResolvedServer(session.address, session.server), session.acknowledgement))
                results.send(Unit)
            }
        }
    }
