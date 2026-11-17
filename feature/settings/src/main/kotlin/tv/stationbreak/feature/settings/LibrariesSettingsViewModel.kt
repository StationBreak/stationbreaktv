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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.selection.ChangeResult
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.domain.selection.LibraryChoices
import tv.stationbreak.core.domain.selection.LibraryListing
import tv.stationbreak.core.domain.selection.LibrarySetup
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
        private val account: AccountSignOut = AccountSignOut.None,
    ) : ViewModel() {
        // The main thread, as a ViewModel's own work runs: it is light, and the selection moves its slow work to its
        // own threads. Not Dispatchers.Default, where the day builds a change sets off would hold up the end of a
        // change. Not Main.immediate: a change is launched under [lock], and must not start inside it.
        @Inject
        constructor(choices: LibraryChoices, sessions: SessionRepository, setup: LibrarySetup) : this(
            choices,
            Dispatchers.Main,
            TimeSource.Monotonic,
            Dispatchers.Default,
            { runCatching { Log.i(LOG_TAG, it) } },
            SessionSignOut(sessions, setup),
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
            /** The signed-in server's name when the visit opened; null when nobody was signed in (#336). */
            val serverName: String? = null,
            /** A first Select on the sign-out row armed it; a second signs out. */
            val armed: Boolean = false,
            /** The viewer moved onto the sign-out row in this visit: only then can Select arm it. */
            val onSignOutByMove: Boolean = false,
            /** The sign-out is running: the screen is about to be left. */
            val signingOut: Boolean = false,
            /** It ran, but the server could not be told: the screen says so until the next Select or Back. */
            val notRevoked: Boolean = false,
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
                val server = account.serverName()
                model =
                    if (known != null) {
                        Model(
                            generation,
                            LibrariesPhase.Listed,
                            known,
                            view = view,
                            restoreFocus = closedOn,
                            notice = notice,
                            serverName = server,
                        )
                    } else {
                        Model(generation, view = view, restoreFocus = closedOn, notice = notice, serverName = server)
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
                    val to = if (up) it.focus - 1 else it.focus + 1
                    // Down past the last library lands on (or stays on) the sign-out row.
                    val onSignOut = hasSignOut(it) && to >= rowsOf(it).size
                    it.copy(focus = to, restoreFocus = restore, armed = false, onSignOutByMove = onSignOut)
                }
            }
        }

        /**
         * Ticks or unticks the focused row at once, while the server's libraries are listed, and has it saved: now, or
         * after the change out. Unticking the last chosen library (adds do not count) is refused with its notice.
         */
        fun select() {
            synchronized(lock) {
                if (hasSignOut(model) && model.focus == rowsOf(model).size) signOutPressed() else selectRow()
            }
        }

        // Under [lock]. Select on a library row.
        private fun selectRow() {
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

        /** Fires once when the sign-out has run: the host leaves the screen for launch routing (#336). */
        val signedOut: Flow<Unit> get() = signOuts.receiveAsFlow()

        private val signOuts = Channel<Unit>(Channel.BUFFERED)

        /**
         * Back while the sign-out row is armed: disarms it and keeps the screen. False when nothing was armed, so Back
         * finishes the screen as usual.
         */
        fun disarm(): Boolean =
            synchronized(lock) {
                if (model.notRevoked) {
                    signOuts.trySend(Unit)
                    return true
                }
                if (!model.armed) return false
                apply { it.copy(armed = false) }
                true
            }

        // Under [lock]. The first Select arms the row; the second signs out, on the ViewModel's scope so a closing
        // screen does not stop it, and the host is told once it has run. Only a row reached by a move arms: never one
        // the focus started on (no rows yet, or none at all), so a double Select on opening the screen cannot sign out.
        private fun signOutPressed() {
            when {
                model.notRevoked -> {
                    signOuts.trySend(Unit)
                }

                model.signingOut || !model.onSignOutByMove -> {
                    Unit
                }

                !model.armed -> {
                    apply { it.copy(armed = true) }
                }

                else -> {
                    apply { it.copy(armed = false, signingOut = true) }
                    log("SignOutChosen")
                    scope.launch {
                        val revoked = withContext(worker) { account.signOut() }
                        if (revoked) {
                            signOuts.send(Unit)
                        } else {
                            ranNotRevoked()
                        }
                    }
                }
            }
        }

        // The sign-out ran but the server could not be told: say so until the viewer goes on.
        private fun ranNotRevoked() {
            synchronized(lock) { apply { it.copy(signingOut = false, notRevoked = true) } }
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
            // The sign-out row, when there is one, is one past the last library.
            val size = rowsOf(next).size
            val last = (size - 1 + if (hasSignOut(next)) 1 else 0).coerceAtLeast(0)
            val focus = next.focus.coerceIn(0, last)
            // Off the sign-out row (rows came or went under it, or it went), it is neither armed nor reached by a move.
            val onRow = hasSignOut(next) && focus == size
            model =
                next.copy(
                    focus = focus,
                    armed = next.armed && onRow,
                    onSignOutByMove = next.onSignOutByMove && onRow,
                )
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
            val signOut =
                when {
                    !hasSignOut(m) -> null
                    m.notRevoked -> SignOutStep.NotRevoked
                    m.signingOut -> SignOutStep.Busy
                    m.armed -> SignOutStep.Armed
                    else -> SignOutStep.Ready
                }
            val server = m.serverName.takeIf { signOut != null }
            return LibrariesSettingsState(phaseOf(m), rows, m.focus, m.notice, server, signOut)
        }

        // Somebody is signed in, as the visit found them and the listing did not contradict, and the libraries are no
        // longer loading (the row never shows, or takes the focus, before them).
        private fun hasSignOut(m: Model): Boolean =
            m.serverName != null &&
                !m.signedOut &&
                (m.phase != LibrariesPhase.Loading || m.notRevoked || m.signingOut)

        // Listed with no stored choice (another account's) is the read-only state of a server that could not be asked.
        private fun phaseOf(m: Model): LibrariesPhase =
            if (m.phase == LibrariesPhase.Listed && m.view == null) LibrariesPhase.Unavailable(null) else m.phase

        private fun rowsOf(m: Model): List<LibraryRow> =
            libraryRows(phaseOf(m), m.listed, m.view, names, !m.signedOut, wanted, sending?.keys.orEmpty())
    }

private const val LOG_TAG = "Lineup"
