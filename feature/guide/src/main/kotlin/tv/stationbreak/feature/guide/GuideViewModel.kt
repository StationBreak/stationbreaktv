// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemDetails
import tv.stationbreak.core.ui.ProgramText
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Holds the program guide (spec 17): loads the schedule window, keeps focus and the viewport, fetches the details of
 * the focused program after a short dwell, ticks the clock, and asks for the channel to be tuned.
 *
 * What the grid draws is one [GuideSnapshot]: the [GuideState] and the focus, replaced together in a single assignment,
 * so a collector that conflates (a Compose frame) can never read a model from one moment and a focus from another. A
 * screen reads [snapshot] and derives each cell's "focused" from it. The focus is by identity ([GuideFocus]), and a
 * published one always resolves with `indexOf` in the snapshot's model. The details [panel] and the [info] overlay are
 * flows of their own, so a key press does not recompose the grid.
 *
 * Each [open] starts a session that [close] (or the next [open]) ends; the session owns the clock tick, the fill-in,
 * the extensions and every details request, so nothing runs while the guide is closed. State is changed under [lock],
 * which is held only to look and to publish: the model is built outside it, so a key press never waits on a rebuild.
 * The keys arrive on the main thread and the session works on [dispatcher].
 */
@Suppress("TooManyFunctions") // One session's steps (load, tick, extend, commit, follow), each small.
@HiltViewModel
internal class GuideViewModel
    internal constructor(
        private val schedule: ScheduleSource,
        private val details: ProgramDetailsCache,
        private val clock: Clock,
        private val dispatcher: CoroutineDispatcher,
        /** Whether this build writes the guide's measurement line (Task 9); the app decides, per build type. */
        val measure: Boolean = false,
    ) : ViewModel() {
        @Inject
        constructor(
            schedule: ScheduleSource,
            details: ProgramDetailsCache,
            clock: Clock,
            @GuideMeasured measure: Boolean,
        ) : this(schedule, details, clock, Dispatchers.Default, measure)

        private val shownSnapshot = MutableStateFlow(GuideSnapshot.Closed)
        private val shownPanel = MutableStateFlow<CellDetails?>(null)
        private val shownInfo = MutableStateFlow<CellDetails?>(null)
        private val eventChannel = Channel<GuideEvent>(Channel.BUFFERED)

        /** The grid and its focus, always consistent with each other. */
        val snapshot: StateFlow<GuideSnapshot> = shownSnapshot.asStateFlow()

        /** The details of the focused cell, filled in after [DWELL_MS] of staying on it. */
        val panel: StateFlow<CellDetails?> = shownPanel.asStateFlow()

        /** The info overlay, while one is open. */
        val info: StateFlow<CellDetails?> = shownInfo.asStateFlow()

        /** Things for the screen to do once, in order. Events from an ended session are dropped. */
        val events: Flow<GuideEvent> = eventChannel.receiveAsFlow()

        private val lock = Any()
        private val root = SupervisorJob()

        // Everything below is read and written under [lock].
        private var session: CoroutineScope? = null
        private var window: ScheduleWindow? = null
        private var currentChannel: ChannelId? = null
        private var loaded = false
        private var tickJob: Job? = null
        private var dwellJob: Job? = null
        private var infoJob: Job? = null
        private var infoOf: Pair<ChannelId, Long>? = null
        private var extensionJob: Job? = null
        private var extendedFrom = NEVER
        private var scrollAsked: Pair<ChannelId, Long>? = null
        private var firstWindowMs = NO_MEASURE
        private val wrapHold = WrapHold()
        private var asked = 0
        private var applied = 0
        private var tickLag = 0L
        private var tickLate = 0L

        /** Opens the guide with the cell on air now on [current] focused (or the first channel's, when unknown). */
        fun open(current: ChannelId?) {
            val started =
                synchronized(lock) {
                    endSession()
                    currentChannel = current
                    CoroutineScope(dispatcher + SupervisorJob(root)).also { session = it }
                }
            started.launch { load(started) }
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
         * One step of focus. Ignored while the info overlay is open, which owns the keys. Scrolling toward the end of
         * what is loaded asks for more of what is already built, as the clock does; so does a key that cannot move on
         * at the end of a row, since more may have been built since.
         */
        fun move(
            direction: GuideDirection,
            repeat: Boolean = false,
            atMs: Long,
        ) {
            synchronized(lock) {
                val current = shownSnapshot.value
                val ready = current.state as? GuideState.Ready
                val from = current.focus
                if (ready == null || from == null || shownInfo.value != null) return
                val to = ready.model.move(from, direction, ready.nowMs)
                if (!wrapHold.allows(ready.model.wrapped(from, to, direction), repeat, atMs)) return
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
        }

        /**
         * Select on the focused cell: tunes its channel when it is on air now (a slate too: that is changing channel),
         * otherwise opens the info overlay for a program (also one that has already ended but is still on the grid),
         * fetching the details at once. A later slate has nothing to tell, so Select does nothing there.
         */
        fun select() {
            synchronized(lock) {
                val current = shownSnapshot.value
                val ready = current.state as? GuideState.Ready
                val located = current.focus?.let { ready?.model?.indexOf(it) }
                if (ready == null || located == null || shownInfo.value != null) return
                val row = ready.model.rows[located.first]
                val cell = row.cells[located.second]
                // The moment the grid shows, as the hint does: what OK does must be what the hint said.
                val now = ready.nowMs
                when {
                    cell.startMs <= now && now < cell.endMs -> eventChannel.trySend(GuideEvent.Tune(row.channel))
                    cell.kind == CellKind.Slate -> Unit
                    else -> openInfo(row, cell)
                }
            }
        }

        /**
         * Back with the info overlay closed: moves the focus to the cell on air now on the playing channel's row, with
         * the grid back at now and that row at the top, as when the guide opened; true when it did. False when the
         * focus is already there, the overlay is open, there is no grid, or the playing channel is not on it: Back
         * then leaves live TV. Nothing is added for a screen reader: the jump changes the focused cell, so the details
         * panel's polite live region reads the playing program as it does after any move.
         */
        fun backToPlaying(): Boolean =
            synchronized(lock) {
                val current = shownSnapshot.value
                val ready = current.state as? GuideState.Ready
                val target = current.playingFocus(currentChannel)
                val focus = current.focus
                if (ready == null || target == null || focus == null) return false
                if (shownInfo.value != null || focus.sameCellAs(target)) return false
                val viewport = viewportStartFor(target, ready.model, ready.model.originMs, direction = null)
                val shown = ready.copy(viewportStartMs = viewport, firstVisibleRow = 0)
                publish(shown.copy(firstVisibleRow = shown.openingRowFor(target)), target)
                true
            }

        /** Closes the info overlay; true when there was one to close (so Back stops there). */
        fun closeInfo(): Boolean =
            synchronized(lock) {
                if (shownInfo.value == null) return false
                infoJob?.cancel()
                infoJob = null
                infoOf = null
                shownInfo.value = null
                true
            }

        override fun onCleared() {
            root.cancel()
        }

        // Sessions

        // Ends the session and resets everything it published, including events it queued and nobody has taken yet.
        private fun endSession() {
            wrapHold.reset()
            session?.coroutineContext?.get(Job)?.cancel()
            session = null
            window = null
            loaded = false
            tickJob = null
            dwellJob = null
            infoJob = null
            infoOf = null
            extensionJob = null
            extendedFrom = NEVER
            scrollAsked = null
            firstWindowMs = NO_MEASURE
            asked = 0
            applied = 0
            tickLag = 0
            tickLate = 0
            while (eventChannel.tryReceive().isSuccess) {
                // Dropped: a Tune from this session must not fire in the next.
            }
            shownPanel.value = null
            shownInfo.value = null
            shownSnapshot.value = GuideSnapshot.Closed
        }

        private fun publishIfCurrent(
            s: CoroutineScope,
            state: GuideState,
        ) {
            synchronized(lock) { if (session === s) shownSnapshot.value = GuideSnapshot(state, null) }
        }

        // A first read that is quick shows nothing until the grid; one still out after [LOADING_AFTER_MS] says the
        // guide is loading, so a blank guide does not invite a second Back (which leaves live TV).
        private suspend fun load(s: CoroutineScope) {
            val slow =
                s.launch {
                    delay(LOADING_AFTER_MS.milliseconds)
                    synchronized(lock) {
                        if (session === s && shownSnapshot.value.state == GuideState.Closed) {
                            shownSnapshot.value = GuideSnapshot(GuideState.Loading, null)
                        }
                    }
                }
            try {
                val origin = originMs()
                val nothing = ScheduleWindow(emptyList(), complete = false)
                val cached = windowOrNull(origin, ScheduleBuild.CachedOnly, onFailure = nothing)
                slow.cancel()
                val nothingToShow = cached == null || (cached.rows.isEmpty() && cached.complete)
                when {
                    nothingToShow -> publishIfCurrent(s, GuideState.Empty)
                    cached.rows.isEmpty() -> publishIfCurrent(s, GuideState.Loading)
                    else -> apply(s, cached, extension = false)
                }
                if (cached != null && !cached.complete) fillIn(s, origin)
            } finally {
                slow.cancel()
                synchronized(lock) { if (session === s) loaded = true }
            }
        }

        // The one build. An incomplete window after it is final: a channel stopped airing or the lineup changed.
        private suspend fun fillIn(
            s: CoroutineScope,
            origin: Long,
        ) {
            val filled = windowOrNull(origin, ScheduleBuild.AsNeeded, onFailure = null)
            if (filled != null && filled.rows.isNotEmpty()) {
                apply(s, filled, extension = false)
                return
            }
            // Nothing came of it: a guide that already has a grid keeps it, one still loading is empty.
            synchronized(lock) {
                if (session === s && shownSnapshot.value.state !is GuideState.Ready) {
                    shownSnapshot.value = GuideSnapshot(GuideState.Empty, null)
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
            val early = buildGuideModel(fresh, built)
            synchronized(lock) {
                if (session !== s) return
                val shownNow = shownNowMs()
                val now = maxOf(built, shownNow)
                val model = if (now == built) early else buildGuideModel(fresh, now)
                val endNow = (shownSnapshot.value.state as? GuideState.Ready)?.model?.endMs
                if (extension && endNow != null && model.endMs < endNow) return
                if (extension) applied++
                window = fresh
                commit(model, now)
                if (tickJob == null) tickJob = s.launch { tickLoop(s) }
            }
        }

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
            val model = buildGuideModel(built, now)
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
        ) {
            val located = focus?.let { ready.model.indexOf(it) }
            check(focus == null || located != null) { "The focus is not in the model it is published with" }
            val before = shownSnapshot.value.focus
            shownSnapshot.value = GuideSnapshot(ready, focus)
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
                // Same cell, maybe rebuilt (a window that was extended): show the new one, keep waiting or showing.
                shownPanel.value = shownPanel.value?.let { if (it.cell == cell) it else it.copy(cell = cell) }
                return
            }
            dwellJob?.cancel()
            val id = cell.itemId
            val cached = id?.let(details::cached)
            val waiting = id != null && cached == null
            shownPanel.value = CellDetails(cell, row.number, row.name, cached, waiting)
            dwellJob =
                if (id != null && waiting) {
                    session?.launch {
                        delay(DWELL_MS.milliseconds)
                        val item = fetch(id)
                        synchronized(lock) {
                            val now = shownSnapshot.value.focus
                            if (now?.channel == focus.channel && now.cellStartMs == focus.cellStartMs) {
                                shownPanel.value = shownPanel.value?.copy(item = item, loading = false)
                            }
                        }
                    }
                } else {
                    null
                }
        }

        private fun openInfo(
            row: GuideRow,
            cell: GuideCell,
        ) {
            infoJob?.cancel()
            val id = cell.itemId
            val cached = id?.let(details::cached)
            val waiting = id != null && cached == null
            val target = row.channel to cell.startMs
            infoOf = target
            shownInfo.value = CellDetails(cell, row.number, row.name, cached, waiting)
            infoJob =
                if (id != null && waiting) {
                    session?.launch {
                        val item = fetch(id)
                        synchronized(lock) {
                            val open = shownInfo.value
                            if (open != null && infoOf == target) {
                                shownInfo.value = open.copy(item = item, loading = false)
                            }
                        }
                    }
                } else {
                    null
                }
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

        private fun nowMs(): Long = clock.now().toEpochMilliseconds()

        private fun originMs(): Long = nowMs().let { it - it.mod(SLOT_MS) }

        private companion object {
            const val DWELL_MS = 500L
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
