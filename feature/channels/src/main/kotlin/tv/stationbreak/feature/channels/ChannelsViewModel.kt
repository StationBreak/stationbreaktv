// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.ChangeRefusal
import tv.stationbreak.core.domain.lineup.ChangeResult
import tv.stationbreak.core.domain.lineup.ChannelChange
import tv.stationbreak.core.domain.lineup.ChannelEditor
import tv.stationbreak.core.domain.lineup.ChannelNumbers
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupAccess
import tv.stationbreak.core.domain.lineup.ResetResult
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.lineup.neighbourOf
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.builder.BuilderMode
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** How long the lineup stays quiet before the reasons are read again (a burst of edits is read once). */
internal val REASONS_DEBOUNCE = 1.seconds

/** How long a New, Copy or Edit press waits for the lineup to be known before it says it is not ready. */
internal val ACCESS_WAIT = 10.seconds

/**
 * Holds channel management (spec 21): the lineup as rows, the focus, the dialog over it, and the changes the viewer
 * makes through the [ChannelEditor]. The ViewModel outlives the screen (it is scoped to live's back-stack entry), so
 * [open] starts clean. The focus is a value by [ChannelId], so it follows a row that moves group.
 *
 * State is changed under [lock] in one assignment of [ChannelsUi], so a collector never reads a half-made state. A
 * result that arrives after the screen was opened again is dropped.
 *
 * A write started in one visit finishes on this ViewModel's scope even if the screen goes meanwhile. A reset that
 * finishes after the screen was opened again is not used by [closing] (accepted: the new visit starts from the list).
 */
@Suppress("TooManyFunctions") // One screen's actions, each small.
@HiltViewModel
internal class ChannelsViewModel
    internal constructor(
        private val library: LibraryRepository,
        private val repository: ChannelRepository,
        private val editor: ChannelEditor,
        private val schedule: ScheduleSource,
        private val clock: Clock,
        dispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        @Inject
        constructor(
            library: LibraryRepository,
            repository: ChannelRepository,
            editor: ChannelEditor,
            schedule: ScheduleSource,
            clock: Clock,
        ) : this(library, repository, editor, schedule, clock, Dispatchers.Default)

        /** What one visit to the screen knows beyond what [ChannelsUi] shows. */
        private data class Model(
            val generation: Int = 0,
            val account: LibraryAccount? = null,
            val channels: List<ChannelSummary> = emptyList(),
            val reasons: Map<ChannelId, HiddenReason?> = emptyMap(),
            /** The first read of [reasons] has landed, or failed. */
            val reasonsRead: Boolean = false,
            /** [channels] and [reasons] as rows, made only when one of them changes. */
            val rows: List<ChannelRow> = emptyList(),
            val access: LineupAccess = LineupAccess.Loading,
            val focus: ChannelsFocus? = null,
            /** The channel to focus when the list first arrives. */
            val wantFocus: ChannelId? = null,
            val panel: ChannelsPanel? = null,
            /** The channel that holds the number a [ChannelsPanel.Swap] offers to take. */
            val holder: ChannelId? = null,
            val message: ChannelsMessage? = null,
            val confirmingReset: Boolean = false,
            val choice: Int = 0,
            val picker: PickerState? = null,
            val choosing: Boolean = false,
            val reset: ResetResult? = null,
            /** Lists that arrived while a reset ran: one of them is the lineup the reset made. */
            val listsDuringReset: Int = 0,
            /** A reset chose channels and the list it made has not arrived yet. */
            val awaitingList: Boolean = false,
            /** A change is out, so a second press sends nothing. */
            val pending: Boolean = false,
            val current: ChannelId? = null,
            val currentNumber: Int? = null,
            /** The builder is showing; the focus stays where it left from, so Back lands there. */
            val builder: BuilderMode? = null,
            /** A channel the builder just made, to focus when its row arrives. */
            val pendingFocus: ChannelId? = null,
            /** The first list of this visit has arrived: a wanted channel not in it no longer holds the focus back. */
            val listed: Boolean = false,
            /** The builder was asked for while the lineup's access was still loading: opened once it is known. */
            val wantBuilder: BuilderMode? = null,
        )

        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val lock = Any()
        private var model = Model()
        private var session: Job? = null
        private var waiting: Job? = null

        /** Between [open] and [close]: nothing is published outside it. */
        private var visible = false
        private val shown = MutableStateFlow(ChannelsUi.Empty)

        val ui: StateFlow<ChannelsUi> = shown.asStateFlow()

        override fun onCleared() {
            scope.cancel()
        }

        fun open(current: ChannelId?) {
            val generation: Int
            synchronized(lock) {
                session?.cancel()
                waiting?.cancel()
                generation = model.generation + 1
                // New is always drawn, so with no channel to start on the focus is on it at once, without waiting for
                // an account or a list; a channel to start on is waited for (until the first list, see onList).
                val start = if (current == null) ChannelsFocus.New else null
                model = Model(generation = generation, current = current, wantFocus = current, focus = start)
                visible = true
                shown.value = derive(model)
            }
            session = scope.launch { run(generation) }
        }

        /**
         * The screen has gone: nothing is read or watched until the next [open], and nothing of this visit is shown
         * again (a panel left open must not appear for a frame when the screen comes back).
         */
        fun close() {
            synchronized(lock) {
                session?.cancel()
                waiting?.cancel()
                session = null
                visible = false
                shown.value = ChannelsUi.Empty
            }
        }

        private suspend fun run(generation: Int) =
            coroutineScope {
                val account = library.activeAccount().filterNotNull().first()
                change(generation) { it.copy(account = account) }
                val reasons = launch { readReasons(generation) }
                val access =
                    launch {
                        editor.access(account).collect { a -> change(generation) { it.copy(access = a) } }
                    }
                var pending: Job? = reasons
                var first = true
                try {
                    repository.channels(account).collect { list ->
                        change(generation) { onList(it, list) }
                        if (first) first = false else pending = rereadLater(generation, pending)
                    }
                } finally {
                    pending?.cancel()
                    access.cancel()
                }
            }

        private fun CoroutineScope.rereadLater(
            generation: Int,
            pending: Job?,
        ): Job {
            pending?.cancel()
            return launch {
                delay(REASONS_DEBOUNCE)
                readReasons(generation)
            }
        }

        @Suppress("TooGenericExceptionCaught") // A failed read leaves the reasons as they were.
        private suspend fun readReasons(generation: Int) {
            val airings =
                try {
                    schedule.airings(clock.now())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The reasons stay as they were; the read has been tried, so Watch is not held back for good.
                    change(generation) { it.copy(reasonsRead = true) }
                    return
                }
            val reasons = airings.associate { it.id to it.hidden }
            change(generation) { it.copy(reasons = reasons, reasonsRead = true) }
        }

        /** A new list: it is kept, and a dialog about a channel that changed or went is closed. */
        private fun onList(
            m: Model,
            list: List<ChannelSummary>,
        ): Model {
            val panel = m.panel
            val before = panel?.let { p -> m.channels.firstOrNull { it.id == p.id } }
            val after = panel?.let { p -> list.firstOrNull { it.id == p.id } }
            val keep = panel == null || before == null || before == after
            val currentNumber =
                list.firstOrNull { it.id == m.current && it.state == ChannelStatus.On }?.number ?: m.currentNumber
            return m.copy(
                channels = list,
                panel = if (keep) panel else null,
                holder = if (keep) m.holder else null,
                currentNumber = currentNumber,
                listsDuringReset = m.listsDuringReset + 1,
                listed = true,
                // A wanted channel the first list lacks may still arrive (the lineup is read in steps): it takes the
                // focus from New when it does, unless the viewer has moved by then.
                pendingFocus = m.pendingFocus ?: m.wantFocus.takeIf { !m.listed && it !in list.map { c -> c.id } },
                awaitingList = false,
            )
        }

        /** Applies [f] to the model of [generation], mends the focus, and publishes. */
        private fun change(
            generation: Int,
            f: (Model) -> Model,
        ) {
            synchronized(lock) {
                if (model.generation != generation) return
                apply(f)
            }
        }

        private fun apply(f: (Model) -> Model) {
            val old = model
            var next = f(old)
            // The rows are sorted only when the channels or their reasons changed: a key press does no sorting.
            if (next.channels !== old.channels || next.reasons !== old.reasons) next = next.copy(rows = rowsOf(next))
            // A new panel (or the reset question) starts with its own choice and digits, in the same assignment.
            if (next.panel != old.panel || next.confirmingReset != old.confirmingReset) {
                next =
                    next.copy(
                        choice = startChoice(next.panel, next.confirmingReset),
                        picker = (next.panel as? ChannelsPanel.Number)?.let { PickerState.of(it.start) },
                    )
            }
            next = builderWhenKnown(next)
            val ids = next.rows.map { it.id }
            val pending = next.pendingFocus
            next =
                if (pending != null && pending in ids) {
                    next.copy(focus = ChannelsFocus.Row(pending), pendingFocus = null)
                } else {
                    next
                }
            model = next.copy(focus = mended(next, old.rows.map { it.id }, ids))
            if (visible) shown.value = derive(model)
        }

        // A builder asked for while the access loaded opens when it is Editable, or says so when it is ReadOnly.
        private fun builderWhenKnown(m: Model): Model {
            val want = m.wantBuilder ?: return m
            return when (m.access) {
                LineupAccess.Editable -> m.copy(builder = want, wantBuilder = null)
                LineupAccess.ReadOnly -> m.copy(wantBuilder = null, message = ChannelsMessage.NotEditable)
                LineupAccess.Loading -> m
            }
        }

        /** A D-pad key inside the open panel: the choice moves, or the number picker's digit or position does. */
        fun panelMove(move: PanelMove) {
            synchronized(lock) {
                if (model.builder != null) return
                val picker = model.picker
                if (picker != null) {
                    apply {
                        it.copy(
                            picker =
                                when (move) {
                                    PanelMove.Up -> picker.up()
                                    PanelMove.Down -> picker.down()
                                    PanelMove.Left -> picker.left()
                                    PanelMove.Right -> picker.right()
                                },
                        )
                    }
                } else if (model.panel != null || model.confirmingReset) {
                    val last = choicesOf(derive(model)).lastIndex
                    val step = if (move == PanelMove.Up || move == PanelMove.Left) -1 else 1
                    apply { it.copy(choice = (it.choice + step).coerceIn(0, last.coerceAtLeast(0))) }
                }
            }
        }

        /**
         * Select inside the open panel acts on the choice or number the state shows, so a key always does what was on
         * screen. Returns the channel to tune when the choice is Watch (tuning is the caller's), else null. 000 is not
         * a channel number: Select on it does nothing.
         */
        fun panelSelect(): ChannelId? {
            val ui = synchronized(lock) { derive(model) }
            if (ui.builder != null) return null
            val panel = ui.panel
            val picker = ui.picker
            val choice = choicesOf(ui).getOrNull(ui.choice)
            return when {
                picker != null && panel != null -> {
                    if (picker.value > 0) setNumber(panel.id, picker.value)
                    null
                }

                choice == Choice.Cancel -> {
                    dismiss()
                    null
                }

                choice == Choice.Confirm -> {
                    confirm(panel)
                    null
                }

                panel == null || choice == null -> {
                    null
                }

                else -> {
                    choose(choice, panel)
                }
            }
        }

        // The choice of an actions panel; the channel to tune for Watch, else null.
        private fun choose(
            choice: Choice,
            panel: ChannelsPanel,
        ): ChannelId? {
            when (choice) {
                // The panel is closed in the model first, so the screen is finished with nothing open over it.
                Choice.Watch -> dismiss()

                Choice.SwitchOn -> switchOn(panel.id)

                Choice.SwitchOff -> switchOff(panel.id)

                Choice.CopyToCustomise -> openBuilder(BuilderMode.Copy(panel.id))

                Choice.Edit -> openBuilder(BuilderMode.Edit(panel.id))

                Choice.ChangeNumber -> startNumber(panel.id)

                Choice.DefaultNumber -> defaultNumber(panel.id)

                Choice.Confirm, Choice.Cancel -> Unit
            }
            return panel.id.takeIf { choice == Choice.Watch }
        }

        private fun confirm(panel: ChannelsPanel?) {
            when (panel) {
                is ChannelsPanel.ConfirmOptIn -> confirmOptIn()
                is ChannelsPanel.Swap -> swap()
                else -> reset()
            }
        }

        private fun mended(
            m: Model,
            oldIds: List<ChannelId>,
            ids: List<ChannelId>,
        ): ChannelsFocus? {
            val focus = m.focus
            return when {
                focus == null -> firstFocus(m, ids)
                focus == ChannelsFocus.Reset || focus == ChannelsFocus.New -> focus
                focus.id in ids -> focus
                ids.isEmpty() -> ChannelsFocus.Reset
                else -> ChannelsFocus.Row(ids[oldIds.indexOf(focus.id).coerceIn(0, ids.lastIndex)])
            }
        }

        private fun firstFocus(
            m: Model,
            ids: List<ChannelId>,
        ): ChannelsFocus? =
            when {
                m.wantFocus != null && m.wantFocus in ids -> ChannelsFocus.Row(m.wantFocus)

                // A channel to start on is waited for until the first list arrives; after it, or with none, the list
                // starts on New (a wanted channel that comes later takes the focus: see onList).
                !m.listed && m.wantFocus != null -> null

                else -> ChannelsFocus.New
            }

        private val ChannelsFocus.id: ChannelId?
            get() = (this as? ChannelsFocus.Row)?.id

        private fun derive(m: Model): ChannelsUi =
            ChannelsUi(
                rows = m.rows,
                focus = m.focus,
                access = m.access,
                choosing = m.choosing,
                panel = m.panel,
                message = m.message,
                confirmingReset = m.confirmingReset,
                choice = m.choice,
                picker = m.picker,
                reasonsRead = m.reasonsRead,
                builder = m.builder,
                gettingReady = m.wantBuilder != null,
            )

        private fun rowsOf(m: Model): List<ChannelRow> =
            m.channels
                .sortedWith(compareBy<ChannelSummary>({ groupOf(it) }, { it.number ?: Int.MAX_VALUE }))
                .map { c ->
                    val group = groupOf(c)
                    ChannelRow(
                        id = c.id,
                        number = c.number,
                        name = c.name,
                        group = group,
                        kind = c.kind,
                        kid = c.kid,
                        optIn = c.optIn,
                        reason = if (group == ChannelGroup.On) m.reasons[c.id] else null,
                        hasOverride = c.defaultNumber != null && c.number != c.defaultNumber,
                    )
                }

        private fun groupOf(c: ChannelSummary): ChannelGroup =
            when (c.state) {
                ChannelStatus.On -> ChannelGroup.On
                ChannelStatus.Shelf -> ChannelGroup.Shelf
                ChannelStatus.UserHidden -> ChannelGroup.Off
            }

        /** Focus to the row above or below; New is the first stop and Reset the last. */
        fun move(up: Boolean) {
            synchronized(lock) {
                if (model.builder != null) return
                val ids = model.rows.map { it.id }
                val stops = listOf(ChannelsFocus.New) + ids.map { ChannelsFocus.Row(it) } + ChannelsFocus.Reset
                val at = stops.indexOf(model.focus)
                val to = if (at < 0) 0 else (if (up) at - 1 else at + 1).coerceIn(0, stops.lastIndex)
                apply { it.copy(focus = stops[to], wantBuilder = null, pendingFocus = null) }
            }
        }

        /** On New: opens the builder. On a row: opens its actions. On Reset: asks to reset. */
        fun select() {
            synchronized(lock) {
                if (model.builder != null) return
                when (val focus = model.focus) {
                    is ChannelsFocus.Row -> {
                        apply {
                            it.copy(panel = ChannelsPanel.Actions(focus.id), message = null, pendingFocus = null)
                        }
                    }

                    ChannelsFocus.New -> {
                        openBuilder(BuilderMode.New)
                    }

                    ChannelsFocus.Reset -> {
                        askReset()
                    }

                    null -> {
                        Unit
                    }
                }
            }
        }

        // Shows the builder for [mode] when the lineup can be written; the focus stays on what it left from.
        private fun openBuilder(mode: BuilderMode) {
            synchronized(lock) {
                if (model.access == LineupAccess.Loading) {
                    // Not known yet whether the lineup can be written: ask again when it is, but not for ever.
                    apply { it.copy(wantBuilder = mode, panel = null, holder = null, message = null) }
                    giveUpWaitingLater(model.generation, mode)
                    return
                }
                if (notEditable()) return
                apply { it.copy(builder = mode, panel = null, holder = null, message = null, pendingFocus = null) }
            }
        }

        // A wait that is still on after ACCESS_WAIT is dropped and says so; one that resolved (or was cancelled by a
        // key) is left alone, and a newer wait is not cut short by an older timer.
        private fun giveUpWaitingLater(
            generation: Int,
            mode: BuilderMode,
        ) {
            waiting?.cancel()
            waiting =
                scope.launch {
                    delay(ACCESS_WAIT)
                    change(generation) {
                        if (it.wantBuilder == mode && it.access == LineupAccess.Loading) {
                            it.copy(wantBuilder = null, message = ChannelsMessage.NotReady)
                        } else {
                            it
                        }
                    }
                }
        }

        /** The builder was left without making a channel: the list shows again, on the row or New it left from. */
        fun builderClosed() {
            synchronized(lock) { apply { it.copy(builder = null, pendingFocus = null) } }
        }

        /**
         * The builder made (or changed) the channel [id]. With [andWatch] the screen is finished and the channel to
         * tune is returned; without it the list shows again, on that channel's row once the row is there.
         */
        fun builderSaved(
            id: ChannelId,
            andWatch: Boolean,
        ): ChannelId? {
            synchronized(lock) {
                apply { it.copy(builder = null, pendingFocus = if (andWatch) null else id) }
            }
            return id.takeIf { andWatch }
        }

        /** Closes the panel, else the reset question, else the message. */
        fun dismiss() {
            synchronized(lock) {
                if (model.builder != null) return
                apply {
                    when {
                        it.wantBuilder != null -> it.copy(wantBuilder = null)
                        it.panel != null -> it.copy(panel = null, holder = null)
                        it.confirmingReset -> it.copy(confirmingReset = false)
                        else -> it.copy(message = null)
                    }
                }
            }
        }

        fun switchOn(id: ChannelId) {
            val asks =
                synchronized(lock) {
                    if (notEditable()) return
                    val optIn = model.channels.firstOrNull { it.id == id }?.optIn == true
                    if (optIn) apply { it.copy(panel = ChannelsPanel.ConfirmOptIn(id)) }
                    optIn
                }
            if (!asks) submit(ChannelChange.SwitchOn(id, confirmedOptIn = false))
        }

        /** When the lineup may not be changed: closes what is open, says so, and returns true. Under [lock]. */
        private fun notEditable(): Boolean {
            if (model.access == LineupAccess.Editable) return false
            apply {
                it.copy(panel = null, holder = null, confirmingReset = false, message = ChannelsMessage.NotEditable)
            }
            return true
        }

        fun confirmOptIn() {
            val id = synchronized(lock) { (model.panel as? ChannelsPanel.ConfirmOptIn)?.id } ?: return
            submit(ChannelChange.SwitchOn(id, confirmedOptIn = true))
        }

        fun switchOff(id: ChannelId) = submit(ChannelChange.SwitchOff(id))

        /** Opens the number dialog for [id], at its number (or the first free band's start). */
        fun startNumber(id: ChannelId) {
            synchronized(lock) {
                val c = model.channels.firstOrNull { it.id == id } ?: return
                if (notEditable()) return
                val start = c.number ?: ChannelNumbers.startFor(c.kid)
                apply { it.copy(panel = ChannelsPanel.Number(id, start)) }
            }
        }

        fun setNumber(
            id: ChannelId,
            number: Int,
        ) = submit(ChannelChange.SetNumber(id, number))

        fun defaultNumber(id: ChannelId) = submit(ChannelChange.ClearNumber(id))

        /** Takes the number from the holder the Swap panel names, who gets this channel's number. */
        fun swap() {
            val change =
                synchronized(lock) {
                    val panel = model.panel as? ChannelsPanel.Swap ?: return
                    val holder = model.holder ?: return
                    if (panel.toDefault) {
                        ChannelChange.ClearNumber(panel.id, holder)
                    } else {
                        ChannelChange.SetNumber(panel.id, panel.number, holder)
                    }
                }
            submit(change)
        }

        private fun submit(change: ChannelChange) {
            val (generation, account) =
                synchronized(lock) {
                    if (notEditable() || model.pending || model.choosing) return
                    val account = model.account ?: return
                    apply { it.copy(pending = true) }
                    model.generation to account
                }
            scope.launch {
                val result = editor.change(account, change)
                change(generation) { applyResult(it, change, result) }
            }
        }

        private fun applyResult(
            m: Model,
            change: ChannelChange,
            result: ChangeResult,
        ): Model {
            val own = m.panel?.id == change.channel
            val closed =
                if (own) m.copy(panel = null, holder = null, pending = false) else m.copy(pending = false)
            return when (result) {
                ChangeResult.Done -> closed.copy(message = null)
                is ChangeResult.Refused -> refused(m, closed, change, result.reason)
                ChangeResult.NotEditable -> closed.copy(message = ChannelsMessage.NotEditable)
                ChangeResult.SaveFailed -> closed.copy(message = ChannelsMessage.SaveFailed)
                ChangeResult.Failed -> closed.copy(message = ChannelsMessage.Failed)
            }
        }

        private val ChannelChange.channel: ChannelId
            get() =
                when (this) {
                    is ChannelChange.SwitchOn -> id
                    is ChannelChange.SwitchOff -> id
                    is ChannelChange.SetNumber -> id
                    is ChannelChange.ClearNumber -> id
                }

        private fun refused(
            m: Model,
            closed: Model,
            change: ChannelChange,
            reason: ChangeRefusal,
        ): Model =
            when (reason) {
                is ChangeRefusal.NumberTaken -> swapPanel(m, closed, change, reason.holder)
                ChangeRefusal.KidsBandOnly -> closed.copy(message = ChannelsMessage.KidsBand)
                ChangeRefusal.LastOn -> closed.copy(message = ChannelsMessage.LastOn)
                ChangeRefusal.NoFreeNumber -> closed.copy(message = ChannelsMessage.NoNumber)
                else -> closed.copy(message = ChannelsMessage.Failed)
            }

        private fun swapPanel(
            m: Model,
            closed: Model,
            change: ChannelChange,
            holder: ChannelId,
        ): Model {
            val name =
                m.channels
                    .firstOrNull { it.id == holder }
                    ?.name
                    .orEmpty()
            val panel =
                when (change) {
                    is ChannelChange.SetNumber -> {
                        ChannelsPanel.Swap(change.id, change.number, name, toDefault = false)
                    }

                    is ChannelChange.ClearNumber -> {
                        val number = m.channels.firstOrNull { it.id == change.id }?.defaultNumber
                        number?.let { ChannelsPanel.Swap(change.id, it, name, toDefault = true) }
                    }

                    else -> {
                        null
                    }
                }
            return if (panel == null) {
                closed.copy(message = ChannelsMessage.Failed)
            } else {
                closed.copy(panel = panel, holder = holder, message = null)
            }
        }

        fun askReset() {
            synchronized(lock) {
                if (!notEditable()) apply { it.copy(confirmingReset = true, panel = null) }
            }
        }

        fun reset() {
            val (generation, account) =
                synchronized(lock) {
                    if (notEditable() || model.pending || model.choosing) return
                    val account = model.account ?: return
                    apply {
                        it.copy(
                            confirmingReset = false,
                            choosing = true,
                            panel = null,
                            message = null,
                            listsDuringReset = 0,
                            awaitingList = false,
                        )
                    }
                    model.generation to account
                }
            scope.launch {
                val result = editor.reset(account)
                change(generation) { resetDone(it, result) }
            }
        }

        private fun resetDone(
            m: Model,
            result: ResetResult,
        ): Model {
            val done = m.copy(choosing = false)
            return when (result) {
                ResetResult.Chosen -> done.copy(reset = result, awaitingList = m.listsDuringReset == 0)
                ResetResult.NotChosenYet -> done.copy(reset = result, message = ChannelsMessage.NotChosenYet)
                ResetResult.NotEditable -> done.copy(message = ChannelsMessage.NotEditable)
                ResetResult.SaveFailed -> done.copy(message = ChannelsMessage.SaveFailed)
                ResetResult.Failed -> done.copy(message = ChannelsMessage.Failed)
                ResetResult.LibrariesUnavailable -> done.copy(message = ChannelsMessage.LibrariesUnavailable)
            }
        }

        /** What to tune when the screen closes, or null to leave the picture as it is. */
        fun closing(): ChannelId? =
            synchronized(lock) {
                val on =
                    model.channels
                        .filter { it.state == ChannelStatus.On && it.number != null }
                        .sortedBy { it.number }
                val current = model.current
                when {
                    // The lowest On of the list the reset made; until that list arrives there is nothing to name.
                    model.reset == ResetResult.Chosen -> {
                        if (model.awaitingList) null else on.firstOrNull()?.id
                    }

                    model.reset == ResetResult.NotChosenYet -> {
                        null
                    }

                    current == null || on.any { it.id == current } -> {
                        null
                    }

                    else -> {
                        // The domain's rule, as cable does: the next higher, else the next lower; never round to the lowest.
                        val gone = model.currentNumber
                        if (gone == null) on.firstOrNull()?.id else on.map { it.id to it.number }.neighbourOf(gone)
                    }
                }
            }
    }
