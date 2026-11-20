// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.selection.LibrarySetup
import javax.inject.Inject

/** The two answers to the sign-out question, top to bottom: the safe one first. */
internal enum class SignOutChoice { Stay, SignOut }

/**
 * Where the sign-out question is: [Asking] (the viewer chooses), [Busy] while the sign-out runs, or [NotRevoked] once
 * it ran but the server could not be told (OK or Back then goes on).
 */
internal enum class SignOutStep { Asking, Busy, NotRevoked }

/** What the sign-out question shows: its [step], the answer under the D-pad, and the signed-in server's name. */
internal data class SignOutState(
    val step: SignOutStep = SignOutStep.Asking,
    val focus: SignOutChoice = SignOutChoice.Stay,
    val serverName: String? = null,
)

/**
 * Holds the sign-out question (#336), which the guide's Settings sheet asks over itself: Stay signed in or Sign out,
 * with the focus on Stay signed in each time it opens, so a press of OK straight after opening it (or a run of them)
 * never signs the household out; only a move down and then OK does. Back stays signed in.
 *
 * A sign-out runs once, on the ViewModel's own scope (and to its end: [AccountSignOut.signOut] is not cancelled with
 * the screen), and [signedOut] fires when it has run. When the server could not be told, the question says so first,
 * and [signedOut] fires when the viewer goes on.
 */
@HiltViewModel
internal class SignOutViewModel
    internal constructor(
        private val account: AccountSignOut,
        dispatcher: CoroutineDispatcher,
        // Where the sign-out runs: off the main thread in the app.
        private val worker: CoroutineDispatcher = dispatcher,
        private val log: (String) -> Unit = {},
    ) : ViewModel() {
        @Inject
        constructor(sessions: SessionRepository, setup: LibrarySetup) : this(
            SessionSignOut(sessions, setup),
            Dispatchers.Main,
            Dispatchers.Default,
            { runCatching { Log.i(LOG_TAG, it) } },
        )

        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val shown = MutableStateFlow(SignOutState())
        private val signOuts = Channel<Unit>(Channel.BUFFERED)

        val state: StateFlow<SignOutState> = shown.asStateFlow()

        /** Fires once when the sign-out has run (and has been read, when the server could not be told). */
        val signedOut: Flow<Unit> get() = signOuts.receiveAsFlow()

        override fun onCleared() {
            scope.cancel()
        }

        /** The question shows again: on Stay signed in, with the server's name as it is now. A run keeps its state. */
        fun open() {
            shown.update { if (it.step == SignOutStep.Asking) SignOutState(serverName = account.serverName()) else it }
        }

        /** Up to Stay signed in, Down to Sign out, while the question is asked. */
        fun move(up: Boolean) {
            shown.update {
                if (it.step != SignOutStep.Asking) return@update it
                it.copy(focus = if (up) SignOutChoice.Stay else SignOutChoice.SignOut)
            }
        }

        /**
         * OK: true on Stay signed in, when the host closes the question. On Sign out it runs the sign-out once; a press
         * while it runs does nothing; once it ran with the server not told, OK goes on.
         */
        fun select(): Boolean {
            val now = shown.value
            when (now.step) {
                SignOutStep.Asking -> if (now.focus == SignOutChoice.Stay) return true else signOut()
                SignOutStep.Busy -> Unit
                SignOutStep.NotRevoked -> signOuts.trySend(Unit)
            }
            return false
        }

        /**
         * Back: true while the question is asked (the viewer stays signed in). Nothing while it runs; once it ran with
         * the server not told, Back goes on.
         */
        fun back(): Boolean =
            when (shown.value.step) {
                SignOutStep.Asking -> {
                    true
                }

                SignOutStep.Busy -> {
                    false
                }

                SignOutStep.NotRevoked -> {
                    signOuts.trySend(Unit)
                    false
                }
            }

        private fun signOut() {
            var started = false
            shown.update {
                started = it.step == SignOutStep.Asking
                if (started) it.copy(step = SignOutStep.Busy) else it
            }
            if (!started) return
            log("SignOutChosen")
            scope.launch {
                val revoked = withContext(worker) { account.signOut() }
                if (revoked) signOuts.send(Unit) else shown.update { it.copy(step = SignOutStep.NotRevoked) }
            }
        }
    }

private const val LOG_TAG = "Lineup"
