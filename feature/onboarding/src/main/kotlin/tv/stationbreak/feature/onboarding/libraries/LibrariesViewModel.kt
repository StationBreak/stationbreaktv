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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryDefaults
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.lineup.CollectionsAtSetup
import tv.stationbreak.core.domain.lineup.HouseholdSetting
import tv.stationbreak.core.domain.lineup.SetupOutcome
import tv.stationbreak.core.domain.logName
import tv.stationbreak.core.domain.selection.LibrarySetup
import tv.stationbreak.core.domain.selection.SetupResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
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
    /** The "Use my collections" switch, and the value it started from (the account's stored setting; on if none). */
    val collections: Boolean = true,
    val collectionsAtStart: Boolean = true,
    /** The account already has a finalised lineup (a re-onboarding), so a change to the switch waits for tomorrow. */
    val lineupFinalised: Boolean = false,
) {
    /** "Takes effect tomorrow." shows: the viewer changed the switch and the change waits for the next boundary. */
    val collectionsTomorrow: Boolean
        get() = lineupFinalised && collections != collectionsAtStart

    /** Done is available: the checklist is showing and something is ticked. After a failed Done it is the retry. */
    val canFinish: Boolean
        get() = (phase == LibrariesPhase.Choosing || phase == LibrariesPhase.SaveFailed) && selected.isNotEmpty()

    override fun toString(): String {
        val phaseName = phase.javaClass.logName()
        return "LibrariesUiState(phase=$phaseName, libraries=${libraries.size})"
    }
}

/**
 * The library picker (S0.5.4): lists the server's libraries, ticks every one except home videos (decision 7), and on
 * Done stores the choice through [LibrarySetup], beside the server record. A choice still stored (a sign-in's clear
 * that failed) is cleared once more first. The session it asks with comes from the
 * in-memory [SignedInHandOff]; with none (the process was restarted under the screen) the screen goes back to launch
 * routing, which restores it. Nothing here is logged.
 */
@HiltViewModel
@Suppress("LongParameterList") // The ports the picker reads and writes, one for one.
internal class LibrariesViewModel
    @Inject
    constructor(
        private val mediaServer: MediaServer,
        private val sessions: SessionRepository,
        private val settings: ServerSettings,
        private val setup: LibrarySetup,
        private val household: HouseholdSetting,
        private val atSetup: CollectionsAtSetup,
        private val signedIn: SignedInHandOff,
    ) : ViewModel() {
        private val session: Session? = signedIn.peek()?.session

        // The hand-off session's account, as the lineup keys it; sign-in has already made it the active account.
        private val account: LibraryAccount? = session?.let { LibraryAccount(it.server.id, it.userId) }

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

        private suspend fun listed(libraries: List<Library>) {
            val who = account
            mutableState.value =
                if (libraries.isEmpty()) {
                    LibrariesUiState(phase = LibrariesPhase.NoLibraries)
                } else {
                    // Everything is ticked except home videos (decision 7).
                    val ticked = LibraryDefaults.ticked(libraries)
                    val start = who?.let { household.useMyCollections(it).first() } ?: true
                    val finalised = who?.let { atSetup.lineupFinalised(it).first() } ?: false
                    LibrariesUiState(LibrariesPhase.Choosing, libraries, ticked, start, start, finalised)
                }
        }

        private suspend fun failed(error: MediaServerError) {
            // A refused session is cleared, and launch routing sends the user to sign in again.
            if (sessions.onServerError(error, session)) {
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

        /** Select on the "Use my collections" switch: written on Continue, and only if it differs from its start. */
        fun onToggleCollections() {
            mutableState.update { current ->
                val editable = current.phase == LibrariesPhase.Choosing || current.phase == LibrariesPhase.SaveFailed
                if (editable) {
                    current.copy(phase = LibrariesPhase.Choosing, collections = !current.collections)
                } else {
                    current
                }
            }
        }

        /** Done: stores the choice. Does nothing while nothing is ticked, so an empty choice is never stored. */
        fun onDone() {
            val current = mutableState.value
            if (!current.canFinish) return
            mutableState.update { it.copy(phase = LibrariesPhase.Saving) }
            viewModelScope.launch {
                if (store(current)) {
                    // Setup is saved: nothing needs the signed-in session held in memory any more.
                    signedIn.clear()
                    results.send(LibrariesEvent.Finished)
                } else {
                    mutableState.update { it.copy(phase = LibrariesPhase.SaveFailed) }
                }
            }
        }

        /**
         * Stores the choice, as a setup ([LibrarySetup.choose]: the repair record is emptied first, so no repair of an
         * earlier choice shows over this one). When there is no server record to attach it to (Continue could not
         * write it), the record is stored first, from the address the session was issued for; a record that is there,
         * with its pin, is never replaced.
         */
        private suspend fun store(current: LibrariesUiState): Boolean {
            val ids = current.selected
            val held = session
            if (held != null && settings.load() == null) settings.save(ServerRecord(held.address))
            // Before the choice: the first lineup is planned once the choice is stored, and reads the flag then.
            val who = account
            val changed = current.collections != current.collectionsAtStart
            // Unwritable (a lineup that may not be written) is no reason to hold Continue: the switch is a setting,
            // not the choice, and a retry would not help. Only a failure a retry can fix shows save-failed.
            val choice = if (who != null && changed) atSetup.chooseAtSetup(who, ids, current.collections) else null
            if (choice == SetupOutcome.Failed) return false
            return when (setup.choose(ids)) {
                SetupResult.Saved -> {
                    true
                }

                // The sign-in's clear did not reach the store: clear again, and ask once more.
                SetupResult.ChoiceStillStored -> {
                    setup.clear()
                    setup.choose(ids) == SetupResult.Saved
                }

                SetupResult.Failed -> {
                    false
                }
            }
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
