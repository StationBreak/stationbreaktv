// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.SyncNow
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.domain.details.LogoCache
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.PlayKey
import tv.stationbreak.core.domain.lineup.PlayingAiring
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.domain.lineup.onGuide
import tv.stationbreak.core.domain.selection.ChoiceView
import tv.stationbreak.core.domain.selection.LibrarySelectionView
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.model.ItemLogo
import tv.stationbreak.core.ui.ProgramText
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Holds the program guide (spec 17): loads the schedule window, keeps focus and the viewport, fetches the details of
 * the focused program after a short dwell, ticks the clock, and asks for the channel to be tuned.
 *
 * What the grid draws is one [GuideSnapshot]: the [GuideState] and the focus, replaced together in a single assignment,
 * so a collector that conflates (a Compose frame) can never read a model from one moment and a focus from another. A
 * screen reads [snapshot] and derives each cell's "focused" from it. The focus is by identity ([GuideFocus]), and a
 * published one always resolves with `indexOf` in the snapshot's model. The details [panel] is a flow of its own, so a
 * key press does not recompose the grid.
 *
 * Each [open] starts a session that [close] (or the next [open]) ends; the session owns the clock tick, the fill-in,
 * the extensions and every details request, so nothing runs while the guide is closed. State is changed under [lock],
 * which is held only to look and to publish: the model is built outside it, so a key press never waits on a rebuild.
 * The keys arrive on the main thread and the session works on [dispatcher].
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass") // One session's steps, each small; its ports.
@HiltViewModel
internal class GuideViewModel
    internal constructor(
        private val schedule: ScheduleSource,
        private val details: ProgramDetailsCache,
        private val clock: Clock,
        private val dispatcher: CoroutineDispatcher,
        /** The lineup, watched for a change while channel management shows and just after (spec 21). */
        private val lineup: ChannelRepository,
        /** Whether this build writes the guide's measurement line (Task 9); the app decides, per build type. */
        val measure: Boolean = false,
        /** Where the one line about a lineup watch that ended by an error goes; numbers only. */
        private val log: (String) -> Unit = {},
        /**
         * The stored library selection (spec 26). While it is gone (the server lists none of the chosen libraries) the
         * screen says so where the grid goes, and the focus stays on the menu row, where Settings leads to Libraries.
         */
        private val selection: StateFlow<ChoiceView?> = MutableStateFlow(null),
        /** Sync now (spec 26, Part 5), which the Settings panel's row starts and whose state it shows. */
        private val sync: SyncNow = SyncNow.None,
        /** The details panel's logos (#723): decoded, or null for none. */
        private val logos: GuideLogos = GuideLogos.None,
        /** Measures how long a logo took after the focus rested; a test brings its own. */
        private val timeSource: TimeSource = TimeSource.Monotonic,
    ) : ViewModel() {
        @Inject
        constructor(
            schedule: ScheduleSource,
            details: ProgramDetailsCache,
            clock: Clock,
            lineup: ChannelRepository,
            @GuideMeasured measure: Boolean,
            choices: LibrarySelectionView,
            syncNow: SyncNow,
            logos: LogoCache,
        ) : this(
            schedule,
            details,
            clock,
            Dispatchers.Default,
            lineup,
            measure,
            { Log.w(LOG_TAG, it) },
            choices.selection,
            syncNow,
            CachedGuideLogos(logos),
        )

        private val shownSnapshot = MutableStateFlow(GuideSnapshot.Closed)
        private val shownPanel = MutableStateFlow<CellDetails?>(null)
        private val eventChannel = Channel<GuideEvent>(Channel.BUFFERED)

        /** The grid and its focus, always consistent with each other. */
        val snapshot: StateFlow<GuideSnapshot> = shownSnapshot.asStateFlow()

        /** The details of the focused cell, filled in after [DWELL_MS] of staying on it; its logo, in time or never. */
        val panel: StateFlow<CellDetails?> = shownPanel.asStateFlow()

        /** Where Sync now stands, for the line beside the Settings panel's row (spec 26, Part 5). */
        val syncNowState: StateFlow<SyncNowState> = sync.state

        /** Select on the Settings panel's Sync now row: starts a sync, or nothing while one runs (then false). */
        fun syncNow(): Boolean = sync.start()

        /** Things for the screen to do once, in order. Events from an ended session are dropped. */
        val events: Flow<GuideEvent> = eventChannel.receiveAsFlow()

        private val lock = Any()
        private val root = SupervisorJob()

        // Everything below is read and written under [lock].
        private var session: CoroutineScope? = null
        private var window: ScheduleWindow? = null
        private var currentChannel: ChannelId? = null

        // What live is playing on [currentChannel], which the schedule's now cell gives way to until it ends (#556).
        // Written under [lock] at the open, read by the builds outside it.
        @Volatile private var playing: PlayingAiring? = null
        private var loaded = false
        private var tickJob: Job? = null
        private var dwellJob: Job? = null
        private var extensionJob: Job? = null
        private var extendedFrom = NEVER
        private var scrollAsked: Pair<ChannelId, Long>? = null

        // The lineup as channel management opened and as it stands, while that screen shows; null when it does not.
        private var lineupWatch: Job? = null
        private var lineupAtOpen: List<Triple<ChannelId, Int?, PlayKey>>? = null
        private var lineupNow: List<Triple<ChannelId, Int?, PlayKey>>? = null
        private var managing = false
        private var loadJob: Job? = null
        private var loadId = 0
        private var firstWindowMs = NO_MEASURE
        private val wrapHold = WrapHold()
        private var asked = 0
        private var applied = 0
        private var tickLag = 0L
        private var tickLate = 0L

        /**
         * Opens the guide with the cell on air now on [current] focused (or the first channel's, when unknown). [playing]
         * is what live is playing on it, shown as its now cell until that ends (#556); one for another channel is not
         * the playing channel's, and is ignored.
         */
        fun open(
            current: ChannelId?,
            playing: PlayingAiring? = null,
        ) {
            synchronized(lock) {
                endSession()
                currentChannel = current
                this.playing = playing?.takeIf { it.channel == current }
                startWork(newSession())
            }
        }

        // What a session runs, at an open and again at each reload (which starts a fresh session): the first read,
        // build and fill-in, the lineup watch, and the library selection. Under [lock].
        private fun startWork(s: CoroutineScope) {
            // The flag is right from the first snapshot, not only once the collector below has run.
            shownSnapshot.value = shownSnapshot.value.copy(noLibraries = selection.value?.gone == true)
            startLoad(s)
            startWatch(s)
            s.launch { followSelection(s) }
        }

        // No libraries: the grid is not shown, so the focus goes to the menu row, on Settings. It stays on the menu
        // row (Down does nothing) until a library is chosen again.
        private suspend fun followSelection(s: CoroutineScope) {
            selection.collect { view ->
                val gone = view?.gone == true
                synchronized(lock) {
                    val current = shownSnapshot.value
                    if (session === s) {
                        shownSnapshot.value =
                            when {
                                gone && current.menu == null -> current.toSettings().copy(noLibraries = true)
                                else -> current.copy(noLibraries = gone)
                            }
                    }
                }
            }
        }

        // The one value: the flag the snapshot carries, set by [followSelection] and nowhere else.
        private fun noLibraries(): Boolean = shownSnapshot.value.noLibraries

        // The menu row, on Settings, with nothing left focused on the channel column: where the libraries are chosen.
        private fun GuideSnapshot.toSettings(): GuideSnapshot = copy(menu = GuideMenuEntry.Settings, onChannel = false)

        private fun newSession(): CoroutineScope {
            val fresh = CoroutineScope(dispatcher + SupervisorJob(root))
            session = fresh
            return fresh
        }

        // Runs the first read, build and fill-in of a session or of its reload; under [lock].
        private fun startLoad(s: CoroutineScope) {
            val id = ++loadId
            // A reload has a grid on screen: it stays, with its focus, until a window with rows arrives.
            val keepsGrid = shownSnapshot.value.state is GuideState.Ready
            loadJob = s.launch { load(s, id, keepsGrid) }
        }

        /** How long the session's first schedule call took, in milliseconds, or -1 before it has returned (Task 9). */
        val windowMs: Long get() = synchronized(lock) { firstWindowMs }

        /** How often this session asked for more of the built schedule, for the measurement line (numbers only). */
        val extensionsAsked: Int get() = synchronized(lock) { asked }

        /** How many of this session's extensions reached the grid (one that lost a race is dropped). */
        val extensionsApplied: Int get() = synchronized(lock) { applied }

        /**
         * The most this session's ticks fell behind, in milliseconds: how many minutes earlier than the minute a tick
         * was due at the moment it showed was (0 normally; 60000 for a tick that read the clock a minute short). For
         * the measurement line, to settle on a device whether the panel's clock can lag (#440).
         */
        val tickLagMaxMs: Long get() = synchronized(lock) { tickLag }

        /**
         * The latest this session's ticks read the clock after the moment they were due, in milliseconds (a late
         * wake-up, or a wall clock ahead of the timer); 0 when none was late. Early reads are [tickLagMaxMs]'s.
         */
        val tickLateMaxMs: Long get() = synchronized(lock) { tickLate }

        /** Closes the guide and stops everything its session was doing. */
        fun close() {
            synchronized(lock) { endSession() }
        }

        /**
         * One step of focus. Scrolling toward the end of what is loaded asks for more of what is already built, as the
         * clock does; so does a key that cannot move on at the end of a row, since more may have been built since.
         */
        fun move(
            direction: GuideDirection,
            repeat: Boolean = false,
            atMs: Long,
        ) {
            synchronized(lock) {
                val current = shownSnapshot.value
                when {
                    // A cell of a grid that is not shown is never moved to: the menu row takes the focus.
                    current.menu == null && noLibraries() -> shownSnapshot.value = current.toSettings()

                    current.menu != null -> moveOnMenu(current, current.menu, direction)

                    current.onChannel -> moveOnChannel(current, direction, repeat, atMs)

                    else -> moveOnGrid(current, direction, repeat, atMs)
                }
            }
        }

        // On the channel column Up and Down move between the channels, staying on the column (the grid's own wrap and
        // held-key wait); Right goes back to the row's cell; Left has nowhere to go.
        private fun moveOnChannel(
            current: GuideSnapshot,
            direction: GuideDirection,
            repeat: Boolean,
            atMs: Long,
        ) {
            when (direction) {
                GuideDirection.Right -> shownSnapshot.value = current.copy(onChannel = false)
                GuideDirection.Left -> Unit
                GuideDirection.Up, GuideDirection.Down -> moveOnGrid(current, direction, repeat, atMs)
            }
        }

        // On the menu row Left and Right move between the entries (and stop at the ends); Down goes to the playing
        // cell, or to the cell last focused when there is none; Up does nothing.
        private fun moveOnMenu(
            current: GuideSnapshot,
            entry: GuideMenuEntry,
            direction: GuideDirection,
        ) {
            val entries = GuideMenuEntry.entries
            when (direction) {
                GuideDirection.Left -> {
                    entries.getOrNull(entry.ordinal - 1)?.let { shownSnapshot.value = current.copy(menu = it) }
                }

                GuideDirection.Right -> {
                    entries.getOrNull(entry.ordinal + 1)?.let { shownSnapshot.value = current.copy(menu = it) }
                }

                GuideDirection.Down -> {
                    if (!noLibraries()) leaveMenu(current)
                }

                GuideDirection.Up -> {
                    Unit
                }
            }
        }

        // Down from the menu row: the playing cell, as Back from elsewhere on the grid goes to it, else the cell last
        // focused; a guide with no cell keeps the focus on the menu.
        private fun leaveMenu(current: GuideSnapshot) {
            val ready = current.state as? GuideState.Ready ?: return
            val playing = current.playingFocus(currentChannel)
            val focus = current.focus
            when {
                playing != null -> showPlaying(ready, playing)
                focus != null -> publish(ready, focus, menu = null)
            }
        }

        // Up and Down on the grid wrap at the ends of the rows, a held key waiting at the end first. The menu row is
        // reached by Back only, so a guide with no cell to focus (nothing on) has nothing for a direction to do.
        private fun moveOnGrid(
            current: GuideSnapshot,
            direction: GuideDirection,
            repeat: Boolean,
            atMs: Long,
        ) {
            val ready = current.state as? GuideState.Ready
            val from = current.focus
            if (from == null || ready == null) return
            if (direction == GuideDirection.Left && ready.model.isEarliestCell(from)) {
                // Where Left used to do nothing: onto the channel column, which names this row.
                shownSnapshot.value = current.copy(onChannel = true)
            } else {
                val to = ready.model.move(from, direction, ready.nowMs)
                val wraps = ready.model.wrapped(from, to, direction)
                if (wrapHold.allows(wraps, repeat, atMs)) step(ready, from, to, direction)
            }
        }

        private fun step(
            ready: GuideState.Ready,
            from: GuideFocus,
            to: GuideFocus,
            direction: GuideDirection,
        ) {
            val shown =
                if (to == from) {
                    ready
                } else {
                    val viewport = viewportStartFor(to, ready.model, ready.viewportStartMs, direction)
                    val row = ready.firstRowFor(to, ready.firstVisibleRow)
                    ready.copy(viewportStartMs = viewport, firstVisibleRow = row).also { publish(it, to) }
                }
            session?.let { extendIfScrolledNearTheEnd(it, shown, to) }
        }

        /**
         * Select on the focused cell: tunes its channel. On a cell on air now (a slate too) that is changing channel;
         * on a program that has not begun, or has ended but is still on the grid, it is the same: the channel plays
         * what is on it now (the hint says "Watch channel"), and the details panel already carries what Select once
         * opened. A later slate or unavailable cell has nothing to watch, so Select does nothing there.
         */
        fun select() {
            synchronized(lock) {
                val current = shownSnapshot.value
                when {
                    current.menu != null -> selectOnMenu(current.menu)

                    // No grid is shown without libraries: there is no cell or channel to act on.
                    !noLibraries() -> selectOnGrid(current)
                }
            }
        }

        // Select on the grid's focus, a cell or a channel; under [lock].
        private fun selectOnGrid(current: GuideSnapshot) {
            val ready = current.state as? GuideState.Ready
            val located = current.focus?.let { ready?.model?.indexOf(it) }
            if (ready == null || located == null) return
            val row = ready.model.rows[located.first]
            if (current.onChannel) {
                eventChannel.trySend(GuideEvent.OpenChannelProperties(row.channel))
            } else {
                selectCell(row, row.cells[located.second], ready.nowMs)
            }
        }

        // The moment the grid shows, as the hint does: what OK does must be what the hint said. Under the lock.
        private fun selectCell(
            row: GuideRow,
            cell: GuideCell,
            now: Long,
        ) {
            when {
                cell.kind != CellKind.Program && !(cell.startMs <= now && now < cell.endMs) -> Unit
                else -> eventChannel.trySend(GuideEvent.Tune(row.channel))
            }
        }

        // Select on a menu entry: what it opens, or Exit; under [lock].
        private fun selectOnMenu(entry: GuideMenuEntry) {
            eventChannel.trySend(
                when (entry) {
                    GuideMenuEntry.Channels -> GuideEvent.OpenChannels
                    GuideMenuEntry.Settings -> GuideEvent.OpenSettings
                    GuideMenuEntry.Exit -> GuideEvent.Exit
                },
            )
        }

        /**
         * Back, one step outwards: from a cell that is not the playing channel's cell on
         * air now to that cell, with the grid back at now and that row at the top, as when the guide opened; from that
         * cell (or when there is none to go to) to the menu row, on Channels. True when it moved. False on the menu
         * row, while the guide loads and when there is no grid or menu to go to: Back then
         * leaves live TV. Nothing is added for a screen reader: the jump changes the focused cell or entry, so the
         * details panel's polite live region reads the playing program as it does after any move.
         */
        fun back(): Boolean =
            synchronized(lock) {
                val current = shownSnapshot.value
                val ready = current.state as? GuideState.Ready
                val target = current.playingFocus(currentChannel)
                val focus = current.focus
                val showsMenu = ready != null || current.state == GuideState.Empty
                when {
                    current.menu != null -> {
                        false
                    }

                    // No grid is shown: the menu row, on Settings, where the libraries are chosen again.
                    noLibraries() -> {
                        shownSnapshot.value = current.toSettings()
                        true
                    }

                    !showsMenu -> {
                        false
                    }

                    // From the channel column back to the row's cell, as Right does.
                    current.onChannel -> {
                        shownSnapshot.value = current.copy(onChannel = false)
                        true
                    }

                    ready != null && target != null && focus != null && !focus.sameCellAs(target) -> {
                        showPlaying(ready, target)
                        true
                    }

                    else -> {
                        shownSnapshot.value = current.copy(menu = GuideMenuEntry.Channels)
                        true
                    }
                }
            }

        // The grid at now, [target] focused and its row at the top.
        private fun showPlaying(
            ready: GuideState.Ready,
            target: GuideFocus,
        ) {
            val viewport = viewportStartFor(target, ready.model, ready.model.originMs, direction = null)
            val shown = ready.copy(viewportStartMs = viewport, firstVisibleRow = 0)
            publish(shown.copy(firstVisibleRow = shown.openingRowFor(target)), target, menu = null)
        }

        /**
         * Channel management opens over the guide: the session stays, the clock stops ticking under it, and a change
         * of the lineup waits until [channelsClosed] (the screen shows what it is changing).
         */
        fun channelsOpened() {
            synchronized(lock) {
                if (session == null) return
                managing = true
                tickJob?.cancel()
                tickJob = null
            }
        }

        /** The Libraries screen opens over the guide: as for channel management, the clock and reloads wait. */
        fun librariesOpened() = channelsOpened()

        /**
         * The Libraries screen closed: the guide shows again as it was (on Settings, where the panel was left open),
         * and a lineup that changed meanwhile loads the grid again, as for [propertiesClosed].
         */
        fun librariesClosed() = propertiesClosed()

        // The lineup (the On channels and their numbers) is watched for the whole session, against what the grid was
        // loaded from. A change that lands while the screen is not showing reloads the grid in place, with the focus
        // where it is: a slow write (a reset on older hardware) can land long after the screen closed. A lineup that
        // cannot be read is a lineup that did not change, as far as the guide knows; the watch ending that way is
        // logged once.
        //
        // The baseline is the watch's first emission, with no read of the lineup ahead of the schedule read (the open
        // path waits for nothing but the schedule). So a write that lands between that read and the first emission is
        // missed until the next change or the next open: accepted.
        private fun startWatch(s: CoroutineScope) {
            lineupWatch = s.launch { watchLineup(s) }
        }

        private suspend fun watchLineup(s: CoroutineScope) {
            try {
                lineup
                    .activeChannels()
                    .map { it.onGuide() }
                    .distinctUntilChanged()
                    .collect { now ->
                        synchronized(lock) {
                            if (session === s) {
                                if (lineupAtOpen == null) lineupAtOpen = now
                                lineupNow = now
                                if (!managing && now != lineupAtOpen) reload(menu = shownSnapshot.value.menu)
                            }
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                log("guide lineupWatch=failed")
            }
        }

        /**
         * Channel management closed without tuning: the guide shows again on the Channels entry. When the lineup
         * changed meanwhile the grid is loaded again (see [reload]) so it shows the new lineup at once; otherwise it is
         * as it was.
         */
        fun channelsClosed() {
            synchronized(lock) {
                managing = false
                if (lineupAtOpen != null && lineupNow != lineupAtOpen) {
                    reload(menu = GuideMenuEntry.Channels)
                } else {
                    shownSnapshot.value = shownSnapshot.value.copy(menu = GuideMenuEntry.Channels)
                    resumeTick()
                }
            }
        }

        /**
         * A channel's properties sheet closed without tuning: the guide shows again as it was, on the channel column.
         * Like [channelsClosed], a lineup that changed meanwhile loads the grid again; the focus stays where it is.
         */
        fun propertiesClosed() {
            synchronized(lock) {
                managing = false
                if (lineupAtOpen != null && lineupNow != lineupAtOpen) {
                    reload(menu = shownSnapshot.value.menu)
                } else {
                    resumeTick()
                }
            }
        }

        /** Channel management ended with a tune: the guide goes with it. */
        fun channelsEnded() {
            synchronized(lock) {
                managing = false
                resumeTick()
            }
        }

        // The clock under the screen stopped; it starts again with a tick at once, as the minute may have turned.
        private fun resumeTick() {
            val s = session ?: return
            if (window != null && tickJob == null) {
                tickJob =
                    s.launch {
                        tick(s, nowMs())
                        tickLoop(s)
                    }
            }
        }

        // The grid loaded again from the lineup as it is now, in a session of its own: the old one is cancelled whole,
        // so a read or build of it that is already past its last suspension point finds `session === s` false and
        // publishes nothing. The shown grid stays until the new one replaces it, and the focus stays on its cell
        // (found again by identity, or the nearest row when its channel is gone), or on the menu entry [menu] names.
        // The measurement stays as it is: the line describes the opening, so [windowMs] stays the first read's, while
        // the extensions and tick lag of the reload count in the session's totals. Under [lock].
        private fun reload(menu: GuideMenuEntry?) {
            val old = session ?: return
            old.coroutineContext[Job]?.cancel()
            val fresh = newSession()
            resetLoadState()
            lineupWatch = null
            lineupAtOpen = lineupNow
            if (shownSnapshot.value.menu != menu) {
                shownSnapshot.value = shownSnapshot.value.copy(menu = menu)
            }
            startWork(fresh)
        }

        override fun onCleared() {
            root.cancel()
        }

        // Sessions

        // What a load, a tick and the extensions of one scope hold, forgotten when that scope is replaced.
        private fun resetLoadState() {
            wrapHold.reset()
            loadJob = null
            window = null
            loaded = false
            tickJob = null
            dwellJob = null
            extensionJob = null
            extendedFrom = NEVER
            scrollAsked = null
        }

        // Ends the session and resets everything it published, including events it queued and nobody has taken yet.
        private fun endSession() {
            session?.coroutineContext?.get(Job)?.cancel()
            session = null
            resetLoadState()
            lineupWatch = null
            lineupAtOpen = null
            lineupNow = null
            managing = false
            firstWindowMs = NO_MEASURE
            asked = 0
            applied = 0
            tickLag = 0
            tickLate = 0
            while (eventChannel.tryReceive().isSuccess) {
                // Dropped: a Tune from this session must not fire in the next.
            }
            shownPanel.value = null
            shownSnapshot.value = GuideSnapshot.Closed
        }

        // A snapshot of [state], on the menu entry the one it replaces is on: the menu outlives the grid being rebuilt.
        private fun snapshotOf(
            state: GuideState,
            focus: GuideFocus?,
        ) = shownSnapshot.value.let { it.copy(state = state, focus = focus, onChannel = it.onChannel && focus != null) }

        private fun publishIfCurrent(
            s: CoroutineScope,
            state: GuideState,
        ) {
            synchronized(lock) { if (session === s) shownSnapshot.value = snapshotOf(state, null) }
        }

        // A first read that is quick shows nothing until the grid; one still out after [LOADING_AFTER_MS] says the
        // guide is loading, so a blank guide does not invite a second Back (which leaves live TV).
        private suspend fun load(
            s: CoroutineScope,
            id: Int,
            keepsGrid: Boolean,
        ) {
            val slow =
                s.launch {
                    delay(LOADING_AFTER_MS.milliseconds)
                    synchronized(lock) {
                        if (session === s && shownSnapshot.value.state == GuideState.Closed) {
                            shownSnapshot.value = snapshotOf(GuideState.Loading, null)
                        }
                    }
                }
            try {
                val origin = originMs()
                val nothing = ScheduleWindow(emptyList(), complete = false)
                val cached = windowOrNull(origin, ScheduleBuild.CachedOnly, onFailure = nothing)
                slow.cancel()
                if (showCached(s, cached, keepsGrid)) fillIn(s, origin, keepsGrid)
            } finally {
                slow.cancel()
                synchronized(lock) { if (session === s && loadId == id) loaded = true }
            }
        }

        // Shows what the first read found, and says whether the build is still to come. Nothing yet while a grid is
        // shown (a reload) leaves that grid and its focus; the build says whether it is final.
        private fun showCached(
            s: CoroutineScope,
            cached: ScheduleWindow?,
            keepsGrid: Boolean,
        ): Boolean {
            val noRows = cached == null || cached.rows.isEmpty()
            val final = cached == null || cached.complete
            when {
                !noRows -> apply(s, checkNotNull(cached), extension = false)
                keepsGrid -> Unit
                final -> publishIfCurrent(s, GuideState.Empty)
                else -> publishIfCurrent(s, GuideState.Loading)
            }
            // No answer at all while a grid is shown (a reload) is not an answer either: the build decides.
            return if (cached == null) keepsGrid else !final || (keepsGrid && noRows)
        }

        // The one build. An incomplete window after it is final: a channel stopped airing or the lineup changed.
        private suspend fun fillIn(
            s: CoroutineScope,
            origin: Long,
            keepsGrid: Boolean,
        ) {
            val filled = windowOrNull(origin, ScheduleBuild.AsNeeded, onFailure = null)
            if (filled != null && filled.rows.isNotEmpty()) {
                apply(s, filled, extension = false)
                return
            }
            // Nothing came of it. A reload's final build with no rows means nothing is on: the guide is empty. A failed
            // read, or any other guide that already has a grid, keeps it; one still loading is empty.
            synchronized(lock) {
                val finalEmpty = keepsGrid && filled != null
                if (session === s && (finalEmpty || shownSnapshot.value.state !is GuideState.Ready)) {
                    shownSnapshot.value = snapshotOf(GuideState.Empty, null)
                }
            }
        }

        private suspend fun windowOrNull(
            fromMs: Long,
            build: ScheduleBuild,
            onFailure: ScheduleWindow?,
            untilMs: Long = fromMs + WINDOW.inWholeMilliseconds,
        ): ScheduleWindow? {
            val owner = synchronized(lock) { session }
            val started = System.nanoTime()
            val result =
                try {
                    schedule.schedule(
                        Instant.fromEpochMilliseconds(fromMs),
                        Instant.fromEpochMilliseconds(untilMs),
                        build,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The port reports a lineup that cannot be read as null; anything else is the same to the guide.
                    onFailure
                }
            synchronized(lock) {
                // Only the session that made the call: an ended session's late answer is not the next one's.
                if (session === owner && firstWindowMs == NO_MEASURE) {
                    firstWindowMs = (System.nanoTime() - started) / NANOS_PER_MS
                }
            }
            return result
        }

        // Makes [fresh] the window. The model is built before the lock is taken, never at a moment before the one
        // shown. A tick that publishes a later moment while it is built wins: the model is built again at that moment,
        // under the lock (rare), so the origin and the "now" line never go back. An [extension] that would end before
        // the grid does (it lost a race with something that read further) is dropped.
        private fun apply(
            s: CoroutineScope,
            fresh: ScheduleWindow,
            extension: Boolean,
        ) {
            val built = maxOf(nowMs(), shownNowMs())
            val early = modelAt(fresh, built)
            synchronized(lock) {
                if (session !== s) return
                val shownNow = shownNowMs()
                val now = maxOf(built, shownNow)
                val model = if (now == built) early else modelAt(fresh, now)
                val endNow = (shownSnapshot.value.state as? GuideState.Ready)?.model?.endMs
                if (extension && endNow != null && model.endMs < endNow) return
                if (extension) applied++
                window = fresh
                commit(model, now)
                if (tickJob == null && !managing) tickJob = s.launch { tickLoop(s) }
            }
        }

        // The grid for [window] at [now]: the playing channel's now cell is what live plays, until that item ends.
        private fun modelAt(
            window: ScheduleWindow,
            now: Long,
        ): GuideModel = buildGuideModel(window.holding(playing, now), now)

        // The moment the grid shows, or the earliest there is when it shows none.
        private fun shownNowMs(): Long = (shownSnapshot.value.state as? GuideState.Ready)?.nowMs ?: Long.MIN_VALUE

        // Ticks

        // Each tick is due at a moment (the next minute, or a block's end) and is told it, so a wake-up a little before
        // that moment by the clock still shows it: see [tick].
        // The next is worked out from no earlier than the last moment shown, so a clock still short of it does not tick
        // that moment again. Never from the moment due when an earlier one was shown: a tick that read the clock too
        // far short of its minute showed the minute before, and the next must come at the clock's minute, not a whole
        // minute later.
        private suspend fun tickLoop(s: CoroutineScope) {
            var lastShown = Long.MIN_VALUE
            while (true) {
                val (wait, due) =
                    synchronized(lock) {
                        val now = maxOf(nowMs(), lastShown)
                        val wait = (shownSnapshot.value.state as? GuideState.Ready).nextTickDelayMs(now)
                        wait to now + wait
                    }
                delay(wait)
                lastShown = minOf(due, tick(s, due))
            }
        }

        /**
         * Moves the clock: rebuilds the grid at the new time, dropping cells that ended before the origin, which always
         * advances (the viewport follows it unless the viewer has scrolled away). The next tick is at the next minute
         * or at the end of the nearest block in the visible range, as it stood when the previous tick was published,
         * so a block end revealed by scrolling may be ticked up to a minute late.
         *
         * The tick shows the moment it was [due] at when the clock reads a little short of it (the timer and the wall
         * clock need not agree to the millisecond). Showing the moment before would show the minute before for a whole
         * minute: the next tick is worked out from a later reading, already past [due], so it is a minute away, and
         * every "min left" would read one too many. A clock read further short than [EARLY_WAKE_MS] shows the moment it
         * read, which may be the minute before; the loop then ticks again at the clock's minute, and [tickLagMaxMs]
         * counts it. A read after [due] is counted in [tickLateMaxMs]. Both are for the device check (#440).
         *
         * Returns the moment shown ([due] when nothing was shown).
         */
        private fun tick(
            s: CoroutineScope,
            due: Long,
        ): Long {
            val built = synchronized(lock) { if (session === s) window else null } ?: return due
            val read = nowMs()
            val now = if (due - read in 1..EARLY_WAKE_MS) due else read
            val model = modelAt(built, now)
            return synchronized(lock) {
                // A window that was replaced while this one was built has its own newer grid; a later moment is shown.
                if (session !== s || window !== built || now < shownNowMs()) {
                    due
                } else {
                    commit(model, now)
                    // Measured: how far the shown minute is behind the minute this tick was for, and how late it read.
                    val minute = ProgramText.MINUTE_MS
                    tickLag = maxOf(tickLag, (due.floorDiv(minute) - now.floorDiv(minute)) * minute)
                    tickLate = maxOf(tickLate, read - due)
                    scrollAsked = null
                    extendIfNearTheEnd(s, model, now)
                    now
                }
            }
        }

        // The clock asks for more when the end of the shortest row comes within reach of now, at most once per half
        // hour: the grid's own end is its longest row's, which can run hours past the others (see below).
        private fun extendIfNearTheEnd(
            s: CoroutineScope,
            model: GuideModel,
            now: Long,
        ) {
            val shortest = model.rows.mapNotNull { it.cells.lastOrNull()?.endMs }.minOrNull() ?: model.endMs
            val near = shortest - now < EXTEND_WITHIN.inWholeMilliseconds
            if (near && extendedFrom != model.originMs) extend(s, model)
        }

        // Scrolling asks for more when the visible range or the focused cell comes within reach of the end of the
        // focused row, once for each end that row reaches. The row's end, not the grid's: the grid ends where its
        // longest row does (a channel off air to the end of its day is one slate that runs hours past the window), and
        // a row that stops at the window must still be scrolled past it. Each tick forgets what was asked, so a row
        // whose ask found nothing more built is asked for again, at most once a minute. Under the lock.
        private fun extendIfScrolledNearTheEnd(
            s: CoroutineScope,
            ready: GuideState.Ready,
            focus: GuideFocus,
        ) {
            val model = ready.model
            val located = model.indexOf(focus)
            val cellEnd = located?.let { (r, c) -> model.rows[r].cells[c].endMs } ?: focus.cellStartMs
            val row = located?.let { model.rows[it.first] }
            val rowEnd = row?.cells?.lastOrNull()?.endMs ?: model.endMs
            val reach = maxOf(ready.viewportStartMs + GUIDE_VIEWPORT_SPAN.inWholeMilliseconds, cellEnd)
            val near = rowEnd - reach < EXTEND_WITHIN.inWholeMilliseconds
            val ask = focus.channel to rowEnd
            if (near && scrollAsked != ask && extend(s, model)) scrollAsked = ask
        }

        // Extensions only ever read what is already built (never `AsNeeded`), one at a time, and never while the
        // open's own load (and its fill-in) is still running. True when one was started. Under the lock.
        private fun extend(
            s: CoroutineScope,
            model: GuideModel,
        ): Boolean {
            if (!loaded || extensionJob?.isActive == true) return false
            extendedFrom = model.originMs
            asked++
            extensionJob =
                s.launch {
                    val longer =
                        windowOrNull(
                            model.originMs,
                            ScheduleBuild.CachedOnly,
                            onFailure = null,
                            untilMs = model.endMs + EXTEND_BY.inWholeMilliseconds,
                        )
                    if (longer != null && longer.rows.isNotEmpty()) apply(s, longer, extension = true)
                }
            return true
        }

        // Publishing

        // Shows [model] at [now], keeping focus on its cell, or on the cell that replaced it; a focus on the program
        // that was on now and has just ended goes to what is on now in its row. Under the lock.
        private fun commit(
            model: GuideModel,
            now: Long,
        ) {
            val current = shownSnapshot.value
            val previous = current.state as? GuideState.Ready
            val focus =
                model.focusAfterRebuild(current.focus, currentChannel, now)?.let {
                    model.followingNow(it, previous?.nowMs, now)
                }
            // The range follows the origin unless the viewer has scrolled it away from there.
            val scrolled = previous != null && previous.viewportStartMs != previous.model.originMs
            val viewport =
                if (previous != null && scrolled) maxOf(previous.viewportStartMs, model.originMs) else model.originMs
            val shown = focus?.let { viewportStartFor(it, model, viewport, direction = null) } ?: viewport
            val ready = GuideState.Ready(model, shown, 0, now)
            // The first grid of a session opens with the focused row at the top, as a cable guide does; later ones
            // keep the rows still while the focus is in view.
            val rows =
                if (previous == null) ready.openingRowFor(focus) else ready.firstRowFor(focus, previous.firstVisibleRow)
            publish(ready.copy(firstVisibleRow = rows), focus)
        }

        // One assignment, so the grid and its focus change together; then the panel follows the focus. A focus that
        // does not resolve in the model is a bug in the steps above, never something for the screen to cope with.
        private fun publish(
            ready: GuideState.Ready,
            focus: GuideFocus?,
            menu: GuideMenuEntry? = shownSnapshot.value.menu,
        ) {
            val located = focus?.let { ready.model.indexOf(it) }
            check(focus == null || located != null) { "The focus is not in the model it is published with" }
            val before = shownSnapshot.value.focus
            // The channel column keeps the focus across a move of rows or a rebuild, never together with the menu.
            val onChannel = shownSnapshot.value.onChannel && menu == null && focus != null
            shownSnapshot.value = GuideSnapshot(ready, focus, menu, onChannel, shownSnapshot.value.noLibraries)
            follow(ready.model, before, focus, located)
        }

        // Details

        private fun follow(
            model: GuideModel,
            before: GuideFocus?,
            focus: GuideFocus?,
            located: Pair<Int, Int>?,
        ) {
            if (focus == null || located == null) {
                dwellJob?.cancel()
                dwellJob = null
                shownPanel.value = null
                return
            }
            val row = model.rows[located.first]
            val cell = row.cells[located.second]
            if (before != null && before.channel == focus.channel && before.cellStartMs == focus.cellStartMs) {
                // Same cell, maybe rebuilt (a window that was extended, or a lineup change that renumbered or renamed
                // its channel): show the new one, keep waiting or showing.
                shownPanel.value =
                    shownPanel.value?.copy(cell = cell, channelNumber = row.number, channelName = row.name)
                return
            }
            dwellJob?.cancel()
            val id = cell.detailsId()
            val cached = id?.let(details::cached)
            val waiting = id != null && cached == null
            shownPanel.value = CellDetails(cell, row.number, row.name, cached, waiting)
            dwellJob = if (id != null) session?.launch { restOn(id, cached, focus) } else null
        }

        // After the focus has rested on the cell for [DWELL_MS]: its details if they are not held, then its logo. Both
        // are for the focused cell alone, so a move on cancels this, and the cache cancels what nobody waits on.
        private suspend fun restOn(
            id: CanonicalId,
            cached: ItemDetails?,
            focus: GuideFocus,
        ) {
            delay(DWELL_MS.milliseconds)
            val settled = timeSource.markNow()
            val item =
                cached
                    ?: fetch(id).also { fetched ->
                        synchronized(lock) {
                            if (isOn(focus)) shownPanel.value = shownPanel.value?.copy(item = fetched, loading = false)
                        }
                    }
            item?.logo?.let { showLogo(it, focus, settled) }
        }

        // The logo replaces the text title only if it is in hand within [LOGO_WINDOW_MS] of the highlight settling (a
        // held one is, in a few milliseconds): a logo that comes later is finished and kept for next time, and never
        // swapped in under the viewer. The request is left running past the window, but only while the focus stays.
        private suspend fun showLogo(
            logo: ItemLogo,
            focus: GuideFocus,
            settled: TimeMark,
        ) = coroutineScope {
            val loading = async { loadLogo(logo) }
            val left = LOGO_WINDOW_MS.milliseconds - settled.elapsedNow()
            val image = if (left > Duration.ZERO) withTimeoutOrNull(left) { loading.await() } else null
            if (image != null) {
                synchronized(lock) { if (isOn(focus)) shownPanel.value = shownPanel.value?.copy(logo = image) }
            }
        }

        private suspend fun loadLogo(logo: ItemLogo): ImageBitmap? =
            try {
                logos.load(logo)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // "No logo", whatever went wrong: the text title is there.
                null
            }

        // Under the lock: the focus is still on [focus]'s cell.
        private fun isOn(focus: GuideFocus): Boolean {
            val now = shownSnapshot.value.focus
            return now?.channel == focus.channel && now.cellStartMs == focus.cellStartMs
        }

        private suspend fun fetch(id: CanonicalId): ItemDetails? =
            try {
                details.details(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The cache gives null for what the server could not say; anything else is the same to the panel.
                null
            }

        /** The item whose details are fetched: none for a slate or a program that left the library. */
        private fun GuideCell.detailsId(): CanonicalId? = itemId.takeIf { kind == CellKind.Program }

        private fun nowMs(): Long = clock.now().toEpochMilliseconds()

        private fun originMs(): Long = nowMs().let { it - it.mod(SLOT_MS) }

        private companion object {
            /** How long the focus rests on a cell before its details and logo are asked for. */
            const val DWELL_MS = 200L

            /** How long after the focus has rested a logo may still replace the text title. */
            const val LOGO_WINDOW_MS = 150L
            const val LOADING_AFTER_MS = 400L
            const val NEVER = Long.MIN_VALUE
            const val NO_MEASURE = -1L
            const val NANOS_PER_MS = 1_000_000L
            val WINDOW = 6.hours
            val EXTEND_BY = 6.hours
            val EXTEND_WITHIN = 2.hours

            /** How far short of its moment a tick may read the clock and still show that moment. */
            const val EARLY_WAKE_MS = 1_000L
        }
    }
