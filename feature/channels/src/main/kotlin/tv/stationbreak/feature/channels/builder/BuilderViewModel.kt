// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.builder.BuildRefusal
import tv.stationbreak.core.domain.lineup.builder.BuildResult
import tv.stationbreak.core.domain.lineup.builder.CatalogPicker
import tv.stationbreak.core.domain.lineup.builder.ChannelBuilder
import tv.stationbreak.core.domain.lineup.builder.ChannelDraft
import tv.stationbreak.core.domain.lineup.builder.ChannelPreviews
import tv.stationbreak.core.domain.lineup.builder.Listed
import tv.stationbreak.core.domain.lineup.builder.PickerEntry
import tv.stationbreak.core.domain.lineup.builder.PlayOrder
import tv.stationbreak.core.domain.lineup.builder.hasPool
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.PanelMove
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** How long the draft stays quiet before the bar and the preview are worked out again. */
internal val PREVIEW_DEBOUNCE = 400.milliseconds

/** How long the search text stays quiet before the library is searched. */
internal val SEARCH_DEBOUNCE = 300.milliseconds

/**
 * Holds the channel builder (spec 22): the draft being built or edited, the page it is on, the focus, the pickers and
 * the preview. The ViewModel outlives the screen (it is scoped to live's back-stack entry), so each open starts
 * clean, and a result that arrives after the builder was opened again is dropped. It holds only structured values and
 * no text: the screens word them.
 *
 * State is changed under [lock] in one assignment, so a collector never reads a half-made state. Whatever changes the
 * pool of the draft starts the wait for the preview in the same assignment.
 */
@Suppress("TooManyFunctions") // One flow's actions, each small.
@HiltViewModel
internal class BuilderViewModel
    @Suppress("LongParameterList") // The builder's ports, and the dispatcher a test brings.
    internal constructor(
        private val library: LibraryRepository,
        private val builder: ChannelBuilder,
        private val picker: CatalogPicker,
        private val previews: ChannelPreviews,
        private val channels: ChannelRepository,
        private val clock: Clock,
        dispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        @Inject
        constructor(
            library: LibraryRepository,
            builder: ChannelBuilder,
            picker: CatalogPicker,
            previews: ChannelPreviews,
            channels: ChannelRepository,
            clock: Clock,
        ) : this(library, builder, picker, previews, channels, clock, Dispatchers.Default)

        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val lock = Any()
        private var model = BuilderModel()
        private var session: Job? = null
        private var previewJob: Job? = null
        private var searchJob: Job? = null
        private val shown = MutableStateFlow(model.toUi())
        private val sent = Channel<BuilderEvent>(Channel.BUFFERED)

        val ui: StateFlow<BuilderUi> = shown.asStateFlow()

        val events: Flow<BuilderEvent> = sent.receiveAsFlow()

        override fun onCleared() {
            scope.cancel()
        }

        fun openNew() = begin(BuilderMode.New) { account -> Opening.Draft(builder.blank(account, kids = false)) }

        fun openEdit(id: ChannelId) =
            begin(BuilderMode.Edit(id)) { account -> opening(builder.open(account, id, copy = false)) { it.draft } }

        /** [formatName] words the copy's name from the built-in's: the ViewModel holds no text. */
        fun openCopy(
            from: ChannelId,
            formatName: (String) -> String,
        ) = begin(BuilderMode.Copy(from)) { account ->
            opening(builder.open(account, from, copy = true)) { opened ->
                opened.draft.copy(name = formatName(opened.builtInName ?: opened.draft.name))
            }
        }

        // Starts a clean visit; the draft arrives when the builder has made or opened it.
        private fun begin(
            mode: BuilderMode,
            open: suspend (LibraryAccount) -> Opening,
        ) {
            synchronized(lock) {
                session?.cancel()
                previewJob?.cancel()
                searchJob?.cancel()
                val generation = model.generation + 1
                model = BuilderModel(generation = generation, mode = mode, onSheet = mode is BuilderMode.Edit)
                shown.value = model.toUi()
                session = scope.launch { arrive(generation, mode, open) }
            }
        }

        private suspend fun arrive(
            generation: Int,
            mode: BuilderMode,
            open: suspend (LibraryAccount) -> Opening,
        ) {
            val account = library.activeAccount().filterNotNull().first()
            when (val opening = open(account)) {
                is Opening.Draft -> {
                    val held = if (mode is BuilderMode.Edit) emptyMap() else heldNumbers(account)
                    change(generation) { it.arrived(account, opening.draft).copy(held = held) }
                }

                is Opening.Failed -> {
                    change(generation) { it.copy(account = account, message = opening.message) }
                }
            }
            readTab(generation, SourceKindTab.Libraries)
        }

        // The numbers other channels hold, with their names: a new channel cannot trade for one, so it steps over them.
        private suspend fun heldNumbers(account: LibraryAccount): Map<Int, String> =
            channels
                .channels(account)
                .first()
                .mapNotNull { channel -> channel.number?.let { it to channel.name } }
                .toMap()

        /** Applies [f] to the model of [generation], and publishes. */
        private fun change(
            generation: Int,
            f: (BuilderModel) -> BuilderModel,
        ) {
            synchronized(lock) {
                if (model.generation != generation) return
                apply(f)
            }
        }

        private fun apply(f: (BuilderModel) -> BuilderModel) {
            val old = model
            var next = f(old)
            if (next.draft != old.draft) {
                // A new draft is a new attempt: what was refused about the old one no longer stands.
                if (next.message == old.message) next = next.copy(message = null)
                next = next.copy(refused = null)
            }
            if (next.account != null && next.draft.poolOnly() != old.draft.poolOnly()) next = startPreview(next)
            model = next.copy(focus = next.mended())
            shown.value = model.toUi()
        }

        // The bar and the preview are worked out after a quiet 400 ms; until then they show Working.
        private fun startPreview(m: BuilderModel): BuilderModel {
            previewJob?.cancel()
            val account = m.account
            val draft = m.draft
            val hasPool = draft.hasPool()
            if (account != null && hasPool) {
                val generation = m.generation
                previewJob =
                    scope.launch {
                        delay(PREVIEW_DEBOUNCE)
                        val answer = previews.preview(account, draft, clock.now())
                        change(generation) { if (previewJob === coroutineContext[Job]) it.previewed(answer) else it }
                    }
            }
            return if (hasPool) {
                m.copy(preview = PreviewView.Working, barCount = null)
            } else {
                m.copy(preview = PreviewView.NothingFits(emptyList()), barCount = null)
            }
        }

        // The pickers

        /** Reads [tab]'s list unless it has been read (the libraries again while the server was out of reach). */
        private fun readTab(
            generation: Int,
            tab: SourceKindTab,
        ) {
            synchronized(lock) {
                val m = model
                val account = m.account
                val have = m.reads[tab]
                val again = tab == SourceKindTab.Libraries && have == Listed.Unavailable
                val unread = have == null || again
                val wanted = account != null && tab != SourceKindTab.Pick && tab !in m.reading
                if (m.generation != generation || !wanted || !unread) return
                apply { it.copy(reading = it.reading + tab) }
                scope.launch {
                    val read = readOf(checkNotNull(account), tab)
                    change(generation) { it.copy(reads = it.reads + (tab to read), reading = it.reading - tab) }
                }
            }
        }

        private suspend fun readOf(
            account: LibraryAccount,
            tab: SourceKindTab,
        ): Listed<List<PickerEntry>> =
            when (tab) {
                SourceKindTab.Libraries -> picker.libraries(account)
                SourceKindTab.Collections -> picker.collections(account)
                SourceKindTab.Tags -> picker.tags(account)
                SourceKindTab.Genres -> picker.genres(account)
                SourceKindTab.Pick -> Listed.Ok(emptyList())
            }

        /** The keyboard's text: the search when that has the focus, else the name. */
        fun type(text: String) {
            synchronized(lock) {
                if (model.saving) return
                when (model.focus) {
                    BuilderFocus.Search -> {
                        searchFor(text)
                    }

                    BuilderFocus.NameRow(NameRowKind.Name), BuilderFocus.Sheet(SheetRow.Name) -> {
                        apply { it.copy(draft = it.draft.copy(name = text)) }
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }

        // The search waits for a quiet 300 ms, and a query still running is cancelled by the next one.
        private fun searchFor(text: String) {
            searchJob?.cancel()
            val account = model.account ?: return
            val generation = model.generation
            val trimmed = text.trim()
            val enough = trimmed.codePointCount(0, trimmed.length) >= MIN_SEARCH_POINTS
            apply { it.copy(searchText = text, searching = enough, hits = if (enough) it.hits else null) }
            if (!enough) return
            searchJob =
                scope.launch {
                    delay(SEARCH_DEBOUNCE)
                    val hits = picker.search(account, text)
                    change(generation) { if (it.searchText == text) it.copy(hits = hits, searching = false) else it }
                }
        }

        // The D-pad

        /** A direction key: Left and Right turn the value the focus is on, else the focus moves. */
        fun move(move: PanelMove) {
            synchronized(lock) {
                val m = model
                val focus = m.focus ?: return
                val by = if (move == PanelMove.Left) -1 else 1
                val turning = move == PanelMove.Left || move == PanelMove.Right
                val knob = knobOf(focus).takeIf { turning && m.confirm == null && !m.saving }
                when {
                    knob != null -> {
                        apply { it.copy(draft = it.draft.turned(knob, by, wrap = false, it.held.keys)) }
                    }

                    turning && m.confirm == null && focus == BuilderFocus.NarrowRow(NarrowRowKind.Genres) -> {
                        apply { it.copy(genreCursor = it.cursorAfter(by)) }
                    }

                    else -> {
                        focusTo(navigate(m.navView(), focus, move))
                    }
                }
            }
        }

        // Moving onto another tab shows its list, which is read the first time.
        private fun focusTo(focus: BuilderFocus) {
            val tab = (focus as? BuilderFocus.Tab)?.tab
            apply { it.copy(focus = focus, tab = tab ?: it.tab) }
            if (tab != null) readTab(model.generation, tab)
        }

        /** OK: answers a question, ticks an entry, or does what the focused row does. */
        fun select() {
            synchronized(lock) {
                val m = model
                val focus = m.focus ?: return
                if (m.saving) return
                apply { it.copy(message = null) }
                when {
                    m.confirm != null -> answer(focus)
                    focus is BuilderFocus.Tab -> focusTo(navigate(m.navView(), focus, PanelMove.Right))
                    focus is BuilderFocus.Entry -> tick(focus.key)
                    focus is BuilderFocus.Narrow -> toggleNarrow()
                    focus is BuilderFocus.NarrowRow -> selectNarrow(focus.row)
                    focus is BuilderFocus.Next -> next()
                    focus is BuilderFocus.PlayRow -> selectPlay(focus.row)
                    focus is BuilderFocus.NameRow -> if (focus.row == NameRowKind.Save) save(andWatch = true)
                    focus is BuilderFocus.Sheet -> selectSheet(focus.row)
                }
            }
        }

        private fun tick(key: String) {
            apply { m ->
                val source = m.sourceFor(key) ?: return@apply m
                val draft = m.draft
                if (m.leavingOut) {
                    m.copy(draft = draft.copy(leaveOut = draft.leaveOut.toggled(source)))
                } else {
                    m.copy(draft = draft.copy(sources = draft.sources.toggled(source)))
                }
            }
        }

        private fun toggleNarrow() {
            apply { it.copy(narrowOpen = !it.narrowOpen) }
            if (model.narrowOpen) readTab(model.generation, SourceKindTab.Genres)
        }

        private fun selectNarrow(row: NarrowRowKind) {
            when (row) {
                NarrowRowKind.Genres -> toggleGenre()
                NarrowRowKind.LeaveOut -> apply { it.copy(leavingOut = !it.leavingOut) }
                else -> turnSelected()
            }
        }

        // Select on a value row goes to the next choice, round to the first after the last.
        private fun turnSelected() {
            val knob = knobOf(model.focus) ?: return
            apply { it.copy(draft = it.draft.turned(knob, 1, wrap = true, it.held.keys)) }
        }

        private fun toggleGenre() {
            apply { m ->
                val genre = (m.reads[SourceKindTab.Genres] as? Listed.Ok)?.value?.getOrNull(m.genreCursor)
                val genres = m.draft.filters.genres
                if (genre == null) {
                    m
                } else {
                    val next = if (genre.key in genres) genres - genre.key else genres + genre.key
                    m.copy(draft = m.draft.copy(filters = m.draft.filters.copy(genres = next)))
                }
            }
        }

        private fun selectPlay(row: PlayRowKind) {
            when (row) {
                PlayRowKind.Order -> turnSelected()
                PlayRowKind.Kids -> toggleKids()
                PlayRowKind.Kept -> useBuildersSettings()
                else -> Unit
            }
        }

        private fun selectSheet(row: SheetRow) {
            when (row) {
                SheetRow.Sources -> openPage(row, BuilderStep.Sources, BuilderFocus.Tab(model.tab), narrow = false)
                SheetRow.NarrowDown -> openPage(row, BuilderStep.Sources, BuilderFocus.Narrow, narrow = true)
                SheetRow.PlayOrder -> turnSelected()
                SheetRow.Kids -> toggleKids()
                SheetRow.Kept -> useBuildersSettings()
                SheetRow.Number -> openPage(row, BuilderStep.Name, BuilderFocus.NameRow(NameRowKind.Number), false)
                SheetRow.Save -> save(andWatch = false)
                SheetRow.Reshuffle -> reshuffle()
                SheetRow.Delete -> delete()
                else -> Unit
            }
        }

        private fun openPage(
            row: SheetRow,
            step: BuilderStep,
            focus: BuilderFocus,
            narrow: Boolean,
        ) {
            apply { it.copy(onSheet = false, sheetRow = row, step = step, focus = focus, narrowOpen = narrow) }
            if (step == BuilderStep.Sources) readTab(model.generation, model.tab)
            if (narrow) readTab(model.generation, SourceKindTab.Genres)
        }

        // Steps

        /** On to the next page: Sources needs a source; Play moves on with a name suggested from the first source. */
        fun next() {
            synchronized(lock) {
                val m = model
                if (m.confirm != null || m.onSheet) return
                if (m.step == BuilderStep.Sources && m.nextEnabled()) {
                    val order = BuilderFocus.PlayRow(PlayRowKind.Order)
                    apply { it.copy(step = BuilderStep.Play, focus = order, narrowOpen = false) }
                } else if (m.step == BuilderStep.Play) {
                    val name = BuilderFocus.NameRow(NameRowKind.Name)
                    apply { it.copy(step = BuilderStep.Name, draft = it.draft.suggested(), focus = name) }
                }
            }
        }

        /** Back: closes a confirm, else goes back a page, else asks to leave if there is work to lose, else closes. */
        fun back() {
            synchronized(lock) {
                val m = model
                when {
                    m.confirm != null -> {
                        closeConfirm()
                    }

                    m.narrowOpen && !m.onSheet -> {
                        apply { it.copy(narrowOpen = false, focus = BuilderFocus.Narrow) }
                    }

                    m.editing() && !m.onSheet -> {
                        apply {
                            it.copy(
                                onSheet = true,
                                narrowOpen = false,
                                focus = BuilderFocus.Sheet(it.sheetRow),
                            )
                        }
                    }

                    m.step == BuilderStep.Name -> {
                        apply {
                            it.copy(
                                step = BuilderStep.Play,
                                focus = BuilderFocus.PlayRow(PlayRowKind.Order),
                            )
                        }
                    }

                    m.step == BuilderStep.Play -> {
                        apply { it.copy(step = BuilderStep.Sources, focus = BuilderFocus.Tab(it.tab)) }
                    }

                    m.dirty() -> {
                        ask(BuilderConfirm.Leave)
                    }

                    else -> {
                        sent.trySend(BuilderEvent.Closed)
                    }
                }
            }
        }

        // Confirms

        // A question starts on its keep: the second choice, the one that changes nothing.
        private fun ask(confirm: BuilderConfirm) {
            apply { it.copy(confirm = confirm, resume = it.focus, focus = BuilderFocus.Choice(1)) }
        }

        private fun closeConfirm() {
            apply { it.copy(confirm = null, focus = it.resume, resume = null) }
        }

        private fun answer(focus: BuilderFocus) {
            val m = model
            val yes = (focus as? BuilderFocus.Choice)?.index == 0
            closeConfirm()
            if (!yes) return
            when (val confirm = m.confirm) {
                BuilderConfirm.Leave -> sent.trySend(BuilderEvent.Closed)
                BuilderConfirm.KidsOff -> setKids(on = false)
                is BuilderConfirm.Delete -> deleteNow()
                is BuilderConfirm.Swap -> submit(m.andWatch, m.swapHolder, confirm.draft)
                null -> Unit
            }
        }

        // The kids switch

        private fun toggleKids() {
            if (model.draft.kids) ask(BuilderConfirm.KidsOff) else setKids(on = true)
        }

        private fun setKids(on: Boolean) {
            val account = model.account ?: return
            val generation = model.generation
            scope.launch {
                val number = builder.numberFor(account, kids = on)
                change(generation) { it.kidsDone(on, number) }
            }
        }

        /** Switches the draft to the builder's own order and caps, in place of any the copied channel keeps. */
        fun useBuildersSettings() {
            synchronized(lock) {
                val fresh =
                    model.draft.copy(
                        order = PlayOrder.Shuffle,
                        seriesCap = ChannelDraft.DEFAULT_SERIES_CAP,
                        filmCap = ChannelDraft.DEFAULT_FILM_CAP,
                    )
                apply { it.copy(draft = fresh) }
            }
        }

        // Saving

        /** Saves the draft; the result is a message, a refusal or a [BuilderEvent.Saved]. */
        fun save(andWatch: Boolean) = submit(andWatch, swapWith = null)

        // [sending] is the draft a Swap was asked about; otherwise the draft as it is now.
        private fun submit(
            andWatch: Boolean,
            swapWith: ChannelId?,
            sending: ChannelDraft? = null,
        ) {
            val m =
                synchronized(lock) {
                    val current = model.copy(draft = sending ?: model.draft)
                    val problem = current.draft.problem()
                    // A channel made is made once; a draft that cannot be saved says why, whichever page this is.
                    val open = current.account != null && !current.saving && !current.saved
                    if (open && problem != null) apply { it.copy(refused = problem, message = null) }
                    val ready = open && problem == null
                    if (ready) apply { it.copy(saving = true, message = null, refused = null, andWatch = andWatch) }
                    current.takeIf { ready }
                } ?: return
            val account = checkNotNull(m.account)
            scope.launch {
                val result = send(account, m, swapWith)
                val holder = ((result as? BuildResult.Refused)?.reason as? BuildRefusal.NumberTaken)?.holder
                val holderName = holder?.let { nameOf(account, it) }.orEmpty()
                change(m.generation) {
                    if (result is BuildResult.Done) sent.trySend(BuilderEvent.Saved(result.id, andWatch))
                    it.saved(m.draft, result, holderName)
                }
            }
        }

        private suspend fun send(
            account: LibraryAccount,
            m: BuilderModel,
            swapWith: ChannelId?,
        ): BuildResult =
            when (val mode = m.mode) {
                is BuilderMode.Edit -> builder.save(account, mode.id, m.draft, swapWith)
                else -> builder.create(account, m.draft)
            }

        private suspend fun nameOf(
            account: LibraryAccount,
            id: ChannelId,
        ): String =
            channels
                .channels(account)
                .first()
                .firstOrNull { it.id == id }
                ?.name
                .orEmpty()

        // Reshuffle and delete

        /** Sends the channel a new seed and opens it again with the seed and the preview that go with it. */
        fun reshuffle() {
            val m =
                synchronized(lock) {
                    val current = model
                    // Only what the sheet offers: a Shuffle with nothing unsaved, and one reshuffle at a time.
                    val offered = SheetRow.Reshuffle in current.sheetRows() && !current.saving
                    if (offered) apply { it.copy(saving = true, message = null) }
                    current.takeIf { offered }
                } ?: return
            val account = m.account
            val mode = m.mode as? BuilderMode.Edit
            if (account == null || mode == null) return
            scope.launch {
                val result = builder.reshuffle(account, mode.id)
                if (result is BuildResult.Done) {
                    val opening = opening(builder.open(account, mode.id, copy = false)) { it.draft }
                    change(m.generation) {
                        val reopened = it.reopened(opening)
                        if (opening is Opening.Draft) reopened.copy(message = BuilderMessage.Reshuffled) else reopened
                    }
                } else {
                    change(m.generation) { it.copy(message = messageOf(result), saving = false) }
                }
            }
        }

        /** Asks whether to delete the channel being edited. */
        fun delete() {
            synchronized(lock) {
                if (model.editing() && model.confirm == null) ask(BuilderConfirm.Delete(model.draft.name))
            }
        }

        private fun deleteNow() {
            val m = model
            val account = m.account
            val mode = m.mode as? BuilderMode.Edit
            if (account == null || mode == null) return
            scope.launch {
                val result = builder.delete(account, mode.id)
                if (result is BuildResult.Done) {
                    // Through the generation guard: a delete that ends after the builder was opened again is dropped.
                    change(m.generation) {
                        sent.trySend(BuilderEvent.Closed)
                        it
                    }
                } else {
                    change(m.generation) { it.copy(message = messageOf(result)) }
                }
            }
        }
    }
