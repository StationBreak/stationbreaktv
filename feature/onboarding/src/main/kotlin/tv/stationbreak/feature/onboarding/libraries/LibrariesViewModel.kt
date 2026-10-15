// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.core.model.Session
import tv.stationbreak.feature.onboarding.server.ErrorCopy
import tv.stationbreak.feature.onboarding.server.ErrorCopyTable
import tv.stationbreak.feature.onboarding.signin.SignedInHandOff
import javax.inject.Inject

/** Where the library picker is. */
internal sealed interface LibrariesPhase {
    /** The server is being asked which libraries it has. */
    data object Loading : LibrariesPhase

    /** The listing failed. The panel explains why and offers Retry. */
    data class LoadFailed(
        val error: MediaServerError,
        val copy: ErrorCopy,
    ) : LibrariesPhase

    /** The server answered with no library StationBreak can use. */
    data object NoLibraries : LibrariesPhase

    /** The checklist. Done is on when something is ticked. */
    data object Choosing : LibrariesPhase

    /** Done was pressed and the choice is being stored (or has been, and the screen is on its way out). */
    data object Saving : LibrariesPhase

    /** The choice could not be stored. It is kept on screen, and Done (which still works) stores it again. */
    data object SaveFailed : LibrariesPhase
}

/** What the picker tells its screen, once each: setup is saved, or the session turned out not to be good any more. */
internal enum class LibrariesEvent { Finished, SessionLost }

/** What the picker shows. Names and ticks only: no address, token or user. */
internal data class LibrariesUiState(
    val phase: LibrariesPhase = LibrariesPhase.Loading,
    val libraries: List<Library> = emptyList(),
    val selected: Set<ItemId> = emptySet(),
) {
    /** Done is available: the checklist is showing and something is ticked. After a failed Done it is the retry. */
    val canFinish: Boolean
        get() = (phase == LibrariesPhase.Choosing || phase == LibrariesPhase.SaveFailed) && selected.isNotEmpty()

    override fun toString(): String = "LibrariesUiState(phase=${phase::class.simpleName}, libraries=${libraries.size})"
}

/**
 * The library picker (S0.5.4): lists the server's libraries, ticks every one except home videos (decision 7), and on
 * Done stores the choice through [ServerSettings], beside the server record. The session it asks with comes from the
 * in-memory [SignedInHandOff]; with none (the process was restarted under the screen) the screen goes back to launch
 * routing, which restores it. Nothing here is logged.
 */
@HiltViewModel
internal class LibrariesViewModel
    @Inject
    constructor(
        private val mediaServer: MediaServer,
        private val sessions: SessionRepository,
        private val settings: ServerSettings,
        private val signedIn: SignedInHandOff,
    ) : ViewModel() {
        private val session: Session? = signedIn.peek()?.session

        /** True when there is no session to ask with. */
        val missingSession: Boolean get() = session == null

        /** For the headline's context line; empty when [missingSession]. */
        val serverName: String = session?.server?.name.orEmpty()

        private val mutableState = MutableStateFlow(LibrariesUiState())
        val state: StateFlow<LibrariesUiState> = mutableState.asStateFlow()

        private val results = Channel<LibrariesEvent>(Channel.BUFFERED)

        /** Fires once per outcome that moves the screen on. */
        val events: Flow<LibrariesEvent> = results.receiveAsFlow()

        private var loadJob: Job? = null

        init {
            load()
        }

        private fun load() {
            val held = session ?: return
            loadJob?.cancel()
            mutableState.value = LibrariesUiState()
            loadJob =
                viewModelScope.launch {
                    when (val result = mediaServer.libraries(held)) {
                        is ServerResult.Success -> listed(result.value)
                        is ServerResult.Failure -> failed(result.error)
                    }
                }
        }

        private fun listed(libraries: List<Library>) {
            mutableState.value =
                if (libraries.isEmpty()) {
                    LibrariesUiState(phase = LibrariesPhase.NoLibraries)
                } else {
                    // Everything is ticked except home videos (decision 7).
                    val ticked = libraries.filter { it.kind != LibraryKind.HomeVideos }.map { it.id }.toSet()
                    LibrariesUiState(LibrariesPhase.Choosing, libraries, ticked)
                }
        }

        private suspend fun failed(error: MediaServerError) {
            // A refused session is cleared, and launch routing sends the user to sign in again.
            if (sessions.onServerError(error)) {
                signedIn.clear()
                results.send(LibrariesEvent.SessionLost)
            } else {
                val copy = ErrorCopyTable.copyFor(error)
                mutableState.value = LibrariesUiState(phase = LibrariesPhase.LoadFailed(error, copy))
            }
        }

        /** Select on a row. Only a library the server listed can be ticked. */
        fun onToggle(id: ItemId) {
            mutableState.update { current ->
                val editable = current.phase == LibrariesPhase.Choosing || current.phase == LibrariesPhase.SaveFailed
                if (!editable || current.libraries.none { it.id == id }) {
                    current
                } else {
                    val ticked = if (id in current.selected) current.selected - id else current.selected + id
                    current.copy(phase = LibrariesPhase.Choosing, selected = ticked)
                }
            }
        }

        /** Done: stores the choice. Does nothing while nothing is ticked, so an empty choice is never stored. */
        fun onDone() {
            val current = mutableState.value
            if (!current.canFinish) return
            mutableState.update { it.copy(phase = LibrariesPhase.Saving) }
            viewModelScope.launch {
                if (store(current.selected)) {
                    // Setup is saved: nothing needs the signed-in session held in memory any more.
                    signedIn.clear()
                    results.send(LibrariesEvent.Finished)
                } else {
                    mutableState.update { it.copy(phase = LibrariesPhase.SaveFailed) }
                }
            }
        }

        /**
         * Stores the choice. When there is no server record to attach it to (Continue could not write it), the record
         * is stored first, from the address the session was issued for; a record that is there, with its pin, is never
         * replaced.
         */
        private suspend fun store(ids: Set<ItemId>): Boolean {
            val held = session
            val storable = held != null && settings.load() == null && settings.save(ServerRecord(held.address))
            return settings.saveLibraries(ids) || (storable && settings.saveLibraries(ids))
        }

        /**
         * Back: calls [leave] unless the choice is being stored. A Back mid-save would push the previous screen only
         * for [LibrariesEvent.Finished] to replace it.
         */
        fun onBack(leave: () -> Unit) {
            if (mutableState.value.phase != LibrariesPhase.Saving) leave()
        }

        /** Retry, after a failed listing or when there is nothing to choose from: asks the server again. */
        fun onRetry() {
            when (mutableState.value.phase) {
                is LibrariesPhase.LoadFailed, LibrariesPhase.NoLibraries -> load()
                else -> Unit
            }
        }
    }
