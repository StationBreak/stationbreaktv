// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings

import android.util.Log
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
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.selection.ChangeResult
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.domain.selection.LibraryChoices
import tv.stationbreak.core.domain.selection.LibraryListing
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import javax.inject.Inject
import kotlin.time.TimeSource

/**
 * Holds the Libraries screen (spec 26): the server's libraries with their ticks, the focus, and the changes the viewer
 * makes through [LibraryChoices]. The ViewModel outlives the screen, so [open] starts a clean visit, which asks the
 * server for its libraries once. Until it answers, a visit after a listing shows that listing's rows and takes presses
 * ([lastListed]): the screen never sits empty while the server is slow.
 *
 * A Select flips the focused row at once: the press is kept as what the viewer wants for that row ([wanted]), shown as
 * saving (a tick) or removing (an untick) until it is stored. One change is out at a time; when it returns and
 * presses are left that are not stored, the next change sends them all, so presses made meanwhile are coalesced, the
 * last press of a row wins, and none is dropped. A change that did not go through puts its rows back as stored and
 * says why. Keeping one chosen library is checked on the press, against what the viewer wants. The presses and the
 * change out are the ViewModel's, not the visit's: a change still out when the screen closes finishes, and the next
 * visit shows its rows as still saving.
 *
 * When the server cannot be asked, the stored choice shows read-only, with the names an earlier listing gave (a
 * library no listing named shows without one). Nobody signed in shows no rows.
 *
 * State is changed under [lock] in one assignment, so a collector never reads a half-made state. A change's result
 * shows on the visit open when it arrives, so a change from an earlier visit that failed still says so.
 */
@HiltViewModel
@Suppress("TooManyFunctions") // One screen's actions and its visit's steps, each small.
internal class LibrariesSettingsViewModel
    internal constructor(
        private val choices: LibraryChoices,
        dispatcher: CoroutineDispatcher,
        private val ticks: TimeSource = TimeSource.Monotonic,
        // Where a change runs, so the mark of its return is taken before the hop back to [dispatcher].
        private val worker: CoroutineDispatcher = dispatcher,
        private val log: (String) -> Unit = {},
    ) : ViewModel() {
        // The main thread, as a ViewModel's own work runs: it is light, and the selection moves its slow work to its
        // own threads. Not Dispatchers.Default, where the day builds a change sets off would hold up the end of a
        // change. Not Main.immediate: a change is launched under [lock], and must not start inside it.
        @Inject
        constructor(choices: LibraryChoices) : this(
            choices,
            Dispatchers.Main,
            TimeSource.Monotonic,
            Dispatchers.Default,
            { runCatching { Log.i(LOG_TAG, it) } },
        )

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
            /**
             * The row focused when the last visit closed, until this visit has its final rows (or the viewer moves);
             * null once placed.
             */
            val restoreFocus: ItemId? = null,
            /** This visit's own listing has answered, so its rows are final. */
            val answered: Boolean = false,
            val notice: Notice? = null,
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

        // What the viewer pressed and is not stored yet, by library: ticked or not. Under [lock], for the ViewModel's
        // life, not the visit's, so a new visit shows the presses still saving.
        private var wanted: Map<ItemId, Boolean> = emptyMap()

        // The presses the change out carries, or null when none is out. Under [lock].
        private var sending: Map<ItemId, Boolean>? = null

        // The server's libraries as the last listing gave them, shown by the next visit until its own listing answers;
        // null when the last listing did not list them. Under [lock].
        private var lastListed: List<Library>? = null

        // Names from every listing this ViewModel read, for the rows a later listing leaves out or cannot give.
        private val names = HashMap<ItemId, String>()

        // The row focused when the screen last closed, for the ViewModel's life. Under [lock].
        private var closedOn: ItemId? = null

        // A change failed while the screen was closed: the next visit says so, once. Under [lock].
        private var missedFailure = false

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
                val known = lastListed
                val notice = if (missedFailure) Notice.Failed else null
                missedFailure = false
                // The stored choice at once, so the rows never show unticked for a frame before the watch delivers it.
                val view = choices.selection.value
                model =
                    if (known != null) {
                        Model(
                            generation,
                            LibrariesPhase.Listed,
                            known,
                            view = view,
                            restoreFocus = closedOn,
                            notice = notice,
                        )
                    } else {
                        Model(generation, view = view, restoreFocus = closedOn, notice = notice)
                    }
                visible = true
                apply { it }
                session = scope.launch { run(generation) }
            }
        }

        /** The screen has gone: nothing is read or watched until the next [open], and nothing of this visit shows. */
        fun close() {
            synchronized(lock) {
                // The row the viewer was on, for the next visit; kept when this visit never showed rows.
                closedOn = rowsOf(model).getOrNull(model.focus)?.id ?: model.restoreFocus ?: closedOn
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
                    lastListed = listing.libraries
                    m.copy(phase = LibrariesPhase.Listed, listed = listing.libraries, answered = true)
                }

                is LibraryListing.Unavailable -> {
                    lastListed = null
                    m.copy(phase = LibrariesPhase.Unavailable(listing.error), answered = true)
                }

                is LibraryListing.NotSignedIn -> {
                    lastListed = null
                    m.copy(phase = LibrariesPhase.Unavailable(null), signedOut = true, answered = true)
                }

                null -> {
                    lastListed = null
                    m.copy(phase = LibrariesPhase.Unavailable(null), answered = true)
                }
            }

        /** Focus to the row above or below, stopping at the first and the last. */
        fun move(up: Boolean) {
            // [apply] keeps it on a row. A move is the viewer's own choice, so the old row is no longer restored, but
            // not while there are no rows to move over: the restore waits for them.
            synchronized(lock) {
                apply {
                    val restore = it.restoreFocus.takeIf { _ -> it.phase == LibrariesPhase.Loading }
                    it.copy(focus = if (up) it.focus - 1 else it.focus + 1, restoreFocus = restore)
                }
            }
        }

        /**
         * Ticks or unticks the focused row at once, while the server's libraries are listed, and has it saved: now, or
         * after the change out. Unticking the last chosen library (adds do not count) is refused with its notice.
         */
        fun select() {
            synchronized(lock) {
                val row = rowsOf(model).getOrNull(model.focus)?.takeIf { phaseOf(model) == LibrariesPhase.Listed }
                val view = model.view
                if (row == null || view == null) return
                val next = wanted + (row.id to !row.ticked)
                val ticks = (view.chosen + view.adding).wantedAs(next)
                if ((ticks intersect view.chosen).isEmpty()) {
                    apply { it.copy(notice = Notice.LastLibrary) }
                    return
                }
                wanted = next
                apply { it.copy(notice = null) }
                if (sending == null) sendNext()
            }
        }

        // Under [lock]. Sends what the viewer wants as one change, when a press is not stored yet; else none is out.
        private fun sendNext() {
            val stored = storedNow()
            val presses = wanted.filter { (id, ticked) -> ticked != id in stored }
            wanted = presses
            if (presses.isEmpty()) {
                sending = null
                return
            }
            sending = presses
            val ticked = stored.wantedAs(presses)
            // On the ViewModel's scope, not the visit's: it finishes when the screen closes.
            scope.launch {
                val (result, returned) = withContext(worker) { send(ticked) to ticks.markNow() }
                synchronized(lock) { settle(presses, result) }
                log("SelectionChangeActed gap=${returned.elapsedNow().inWholeMilliseconds}")
            }
        }

        // Under [lock]. A change of [sent] returned [result]: its presses are stored or dropped, and the next is sent.
        private fun settle(
            sent: Map<ItemId, Boolean>,
            result: ChangeResult,
        ) {
            wanted =
                if (result == ChangeResult.Saved) {
                    // A press made again since the change was sent stays, to be sent next.
                    wanted.filter { (id, ticked) -> sent[id] != ticked }
                } else {
                    // Its rows go back as stored; a later press of another row is still sent.
                    wanted - sent.keys
                }
            if (!visible && result == ChangeResult.Failed) missedFailure = true
            sendNext()
            // The ticks follow the stored choice now, not when the watch next delivers it.
            val notice = result.notice()
            apply {
                val m = it.withView(choices.selection.value)
                when {
                    notice != Notice.Saved -> m.copy(notice = notice)
                    sending == null && m.notice == null -> m.copy(notice = Notice.Saved)
                    else -> m
                }
            }
        }

        // The stored choice, chosen or being added.
        private fun storedNow(): Set<ItemId> =
            choices.selection.value
                ?.let { it.chosen + it.adding }
                .orEmpty()

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
        // The focus is kept on a row: when rows go (a left-out library unticked or confirmed missing), it is stored
        // clamped, so the next Select and Up act from the row drawn.
        private fun apply(step: (Model) -> Model) {
            val next = placed(step(model))
            val last = (rowsOf(next).size - 1).coerceAtLeast(0)
            model = next.copy(focus = next.focus.coerceIn(0, last))
            if (visible) shown.value = derive(model)
        }

        // A visit starts on the row the last one closed on, when it is still shown, else on the first. The row is
        // placed as soon as the rows show (the last listing's, while this visit's own is out) and again when the
        // visit's own listing answers, which may have left it out. Not while the rows are still loading.
        private fun placed(m: Model): Model {
            val id = m.restoreFocus
            if (id == null || m.phase == LibrariesPhase.Loading) return m
            val at = rowsOf(m).indexOfFirst { it.id == id }
            return m.copy(focus = at.coerceAtLeast(0), restoreFocus = id.takeIf { at >= 0 && !m.answered })
        }

        private fun derive(m: Model): LibrariesSettingsState {
            val rows = rowsOf(m)
            return LibrariesSettingsState(phaseOf(m), rows, m.focus, m.notice)
        }

        // Listed with no stored choice (another account's) is the read-only state of a server that could not be asked.
        private fun phaseOf(m: Model): LibrariesPhase =
            if (m.phase == LibrariesPhase.Listed && m.view == null) LibrariesPhase.Unavailable(null) else m.phase

        private fun rowsOf(m: Model): List<LibraryRow> =
            libraryRows(phaseOf(m), m.listed, m.view, names, !m.signedOut, wanted, sending?.keys.orEmpty())
    }

private const val LOG_TAG = "Lineup"
