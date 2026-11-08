// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.selection.ChangeResult
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.domain.selection.LibraryChoices
import tv.stationbreak.core.domain.selection.LibraryListing
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import javax.inject.Inject

/**
 * Holds the Libraries screen (spec 26): the server's libraries with the stored choice as ticks, the focus, and the one
 * change the viewer makes at a time through [LibraryChoices]. The ViewModel outlives the screen, so [open] starts a
 * clean visit, which asks the server for its libraries once.
 *
 * The ticks are always the stored choice ([LibraryChoices.selection]), never a guess: a change that did not go through
 * leaves them as they are. A Select sends one change, applied at once; a Select while it is out sends nothing.
 *
 * When the server cannot be asked, the stored choice shows read-only, with the names an earlier listing gave (a
 * library no listing named shows without one). Nobody signed in shows no rows.
 *
 * State is changed under [lock] in one assignment, so a collector never reads a half-made state. A result that
 * arrives after the screen was opened again is dropped; the change itself still finishes.
 */
@HiltViewModel
@Suppress("TooManyFunctions") // One screen's actions and its visit's steps, each small.
internal class LibrariesSettingsViewModel
    internal constructor(
        private val choices: LibraryChoices,
        dispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        @Inject
        constructor(choices: LibraryChoices) : this(choices, Dispatchers.Default)

        /** What one visit knows beyond what [LibrariesSettingsState] shows. */
        private data class Model(
            val generation: Int = 0,
            val phase: LibrariesPhase = LibrariesPhase.Loading,
            /** The server's libraries, in its order, once listed. */
            val listed: List<Library> = emptyList(),
            /** Nobody is signed in: nothing to show. */
            val signedOut: Boolean = false,
            val view: ChoiceView? = null,
            val focus: Int = 0,
            val notice: Notice? = null,
            /** A change is out, so a Select sends nothing. */
            val changing: Boolean = false,
            /** The adds already shown as given up in this visit, so each is said once. */
            val stalledSeen: Set<ItemId> = emptySet(),
        ) {
            // The stored choice now. A newly given-up add says Failed once; the ticks follow the stored choice.
            fun withView(view: ChoiceView?): Model {
                val stalled = view?.stalled.orEmpty()
                val fresh = stalled - stalledSeen
                return copy(
                    view = view,
                    stalledSeen = stalledSeen + stalled,
                    notice = if (fresh.isNotEmpty()) Notice.Failed else notice,
                )
            }
        }

        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val lock = Any()
        private var model = Model()
        private var session: Job? = null

        // Names from every listing this ViewModel read, for the rows a later listing leaves out or cannot give.
        private val names = HashMap<ItemId, String>()

        /** Between [open] and [close]: nothing is published outside it. */
        private var visible = false
        private val shown = MutableStateFlow(LibrariesSettingsState.Empty)

        val state: StateFlow<LibrariesSettingsState> = shown.asStateFlow()

        override fun onCleared() {
            scope.cancel()
        }

        fun open() {
            val generation: Int
            synchronized(lock) {
                session?.cancel()
                generation = model.generation + 1
                model = Model(generation = generation)
                visible = true
                shown.value = derive(model)
            }
            session = scope.launch { run(generation) }
        }

        /** The screen has gone: nothing is read or watched until the next [open], and nothing of this visit shows. */
        fun close() {
            synchronized(lock) {
                session?.cancel()
                session = null
                visible = false
                shown.value = LibrariesSettingsState.Empty
            }
        }

        private suspend fun run(generation: Int) =
            coroutineScope {
                val watching = launch { choices.selection.collect { v -> change(generation) { it.withView(v) } } }
                val listing = listOnce()
                change(generation) { onListing(it, listing) }
                watching.join()
            }

        @Suppress("TooGenericExceptionCaught") // A listing that threw is a server that could not be asked.
        private suspend fun listOnce(): LibraryListing? =
            try {
                choices.listing()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        private fun onListing(
            m: Model,
            listing: LibraryListing?,
        ): Model =
            when (listing) {
                is LibraryListing.Listed -> {
                    listing.libraries.forEach { names[it.id] = it.name }
                    m.copy(phase = LibrariesPhase.Listed, listed = listing.libraries)
                }

                is LibraryListing.Unavailable -> {
                    m.copy(phase = LibrariesPhase.Unavailable(listing.error))
                }

                is LibraryListing.NotSignedIn -> {
                    m.copy(phase = LibrariesPhase.Unavailable(null), signedOut = true)
                }

                null -> {
                    m.copy(phase = LibrariesPhase.Unavailable(null))
                }
            }

        /** Focus to the row above or below, stopping at the first and the last. */
        fun move(up: Boolean) {
            synchronized(lock) {
                val last = (rowsOf(model).size - 1).coerceAtLeast(0)
                val to = (if (up) model.focus - 1 else model.focus + 1).coerceIn(0, last)
                apply { it.copy(focus = to) }
            }
        }

        /** Ticks or unticks the focused row: one change, while the server's libraries are listed and none is out. */
        fun select() {
            val generation: Int
            val ticked: Set<ItemId>
            synchronized(lock) {
                val open = model.phase == LibrariesPhase.Listed && !model.changing
                val row = rowsOf(model).getOrNull(model.focus)?.takeIf { open }
                val view = model.view
                if (row == null || view == null) return
                val stored = view.chosen + view.adding
                ticked = if (row.ticked) stored - row.id else stored + row.id
                generation = model.generation
                apply { it.copy(changing = true, notice = null) }
            }
            scope.launch {
                val result = send(ticked)
                change(generation) { it.copy(changing = false, notice = result.notice()) }
            }
        }

        @Suppress("TooGenericExceptionCaught") // A change that threw stored nothing the screen can show.
        private suspend fun send(ticked: Set<ItemId>): ChangeResult =
            try {
                choices.change(ticked)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ChangeResult.Failed
            }

        // Applies [step] when [generation] is still the visit showing.
        private fun change(
            generation: Int,
            step: (Model) -> Model,
        ) {
            synchronized(lock) {
                if (model.generation != generation) return
                apply(step)
            }
        }

        // Under [lock].
        private fun apply(step: (Model) -> Model) {
            model = step(model)
            if (visible) shown.value = derive(model)
        }

        private fun derive(m: Model): LibrariesSettingsState {
            val rows = rowsOf(m)
            val focus = m.focus.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
            return LibrariesSettingsState(m.phase, rows, focus, m.notice)
        }

        private fun rowsOf(m: Model): List<LibraryRow> {
            val listOf = !m.signedOut
            return libraryRows(m.phase, m.listed, m.view, names, listOf)
        }
    }
