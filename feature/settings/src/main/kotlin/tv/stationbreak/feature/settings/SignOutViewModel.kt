// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** The two answers to the sign-out question, top to bottom: the safe one first. */
internal enum class SignOutChoice { Stay, SignOut }

/**
 * Where the sign-out question is: [Asking] (the viewer chooses), [Busy] while the sign-out runs, or [NotRevoked] once
 * it ran but the server could not be told (OK or Back then goes on).
 */
internal enum class SignOutStep { Asking, Busy, NotRevoked }

/**
 * What the sign-out question shows: its [step], the answer under the D-pad, the signed-in server's name, and whether
 * the last sign-out [failed] (it threw before it finished: the question asks again, and says so until the next key).
 * [moved] is whether the viewer has moved between the answers since it opened: only then is a move announced.
 */
internal data class SignOutState(
    val step: SignOutStep = SignOutStep.Asking,
    val focus: SignOutChoice = SignOutChoice.Stay,
    val serverName: String? = null,
    val failed: Boolean = false,
    val moved: Boolean = false,
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
        private val wentOn = AtomicBoolean(false)

        val state: StateFlow<SignOutState> = shown.asStateFlow()

        /** Fires once, and only once, when the sign-out has run (and has been read, when the server was not told). */
        val signedOut: Flow<Unit> get() = signOuts.receiveAsFlow()

        override fun onCleared() {
            scope.cancel()
        }

        /** The question shows again: on Stay signed in, with the server's name as it is now. A run keeps its state. */
        fun open() {
            shown.update { if (it.step == SignOutStep.Asking) SignOutState(serverName = account.serverName()) else it }
        }

        /**
         * The question has closed (the viewer stayed): the focus goes back to Stay signed in now, so the next showing
         * never draws a frame on Sign out before [open] runs.
         */
        fun close() {
            shown.update {
                if (it.step == SignOutStep.Asking) it.copy(focus = SignOutChoice.Stay, moved = false) else it
            }
        }

        /** Up to Stay signed in, Down to Sign out, while the question is asked. */
        fun move(up: Boolean) {
            shown.update {
                if (it.step != SignOutStep.Asking) return@update it
                it.copy(focus = if (up) SignOutChoice.Stay else SignOutChoice.SignOut, failed = false, moved = true)
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
                SignOutStep.NotRevoked -> goOn()
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
                    goOn()
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
                when (signOutOrNull()) {
                    true -> goOn()

                    false -> shown.update { it.copy(step = SignOutStep.NotRevoked) }

                    // It threw: what was cleared is not known, so the TV claims nothing. The question asks again, on
                    // Stay signed in, and says it went wrong.
                    null -> shown.update { SignOutState(serverName = it.serverName, failed = true) }
                }
            }
        }

        // The sign-out's answer (whether the server was told), or null when it threw. A cancellation is not a failure:
        // it goes on up. The log is a fixed word, never the error, which could carry the server's address.
        @Suppress("TooGenericExceptionCaught") // Any throw from the sign-out is one failure the viewer is told of.
        private suspend fun signOutOrNull(): Boolean? =
            try {
                withContext(worker) { account.signOut() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                log("SignOutFailed")
                null
            }

        // The host is told once, however many presses follow: it leaves for the server screen only once.
        private fun goOn() {
            if (wentOn.compareAndSet(false, true)) signOuts.trySend(Unit)
        }
    }

private const val LOG_TAG = "Lineup"
