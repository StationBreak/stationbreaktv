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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tv.stationbreak.core.domain.details.ProgramDetailsCache
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemDetails
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
 * screen reads [snapshot] and derives each cell's "focused" from it. The focus is by identity ([GuideFocus]); one that
 * does not resolve with `indexOf` in the snapshot's model must be treated as no focus. [state] and [focus] are the two
 * halves as flows of their own, for a consumer that wants only one. The details [panel] and the [info] overlay are
 * flows of their own too, so a key press does not recompose the grid.
 *
 * Each [open] starts a session that [close] (or the next [open]) ends; the session owns the clock tick, the fill-in,
 * the extensions and every details request, so nothing runs while the guide is closed. State is changed under [lock],
 * which is held only to look and to publish: the model is built outside it, so a key press never waits on a rebuild.
 * The keys arrive on the main thread and the session works on [dispatcher].
 */
@Suppress("TooManyFunctions") // One session's steps (load, tick, extend, commit, follow), each small.
@HiltViewModel
class GuideViewModel
    internal constructor(
        private val schedule: ScheduleSource,
        private val details: ProgramDetailsCache,
        private val clock: Clock,
        private val dispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        @Inject
        constructor(
            schedule: ScheduleSource,
            details: ProgramDetailsCache,
            clock: Clock,
        ) : this(schedule, details, clock, Dispatchers.Default)

        private val shownSnapshot = MutableStateFlow(GuideSnapshot.Closed)
        private val shownPanel = MutableStateFlow<PanelDetails?>(null)
        private val shownInfo = MutableStateFlow<GuideInfo?>(null)
        private val eventChannel = Channel<GuideEvent>(Channel.BUFFERED)

        /** The grid and its focus, always consistent with each other. */
        val snapshot: StateFlow<GuideSnapshot> = shownSnapshot.asStateFlow()

        /** The grid alone: the model, the visible time range and rows, and the time of the "now" line. */
        val state: Flow<GuideState> = shownSnapshot.map { it.state }.distinctUntilChanged()

        /** The focus alone. Treat one that does not resolve in the model it is read with as no focus. */
        val focus: Flow<GuideFocus?> = shownSnapshot.map { it.focus }.distinctUntilChanged()

        /** The details of the focused cell, filled in after [DWELL_MS] of staying on it. */
        val panel: StateFlow<PanelDetails?> = shownPanel.asStateFlow()

        /** The info overlay, while one is open. */
        val info: StateFlow<GuideInfo?> = shownInfo.asStateFlow()

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
        private var firstWindowMs = NO_MEASURE

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

        /** Closes the guide and stops everything its session was doing. */
        fun close() {
            synchronized(lock) { endSession() }
        }

        /** One step of focus. Ignored while the info overlay is open, which owns the keys. */
        fun move(direction: GuideDirection) {
            synchronized(lock) {
                val current = shownSnapshot.value
                val ready = current.state as? GuideState.Ready
                val from = current.focus
                if (ready == null || from == null || shownInfo.value != null) return
                val to = ready.model.move(from, direction)
                if (to == from) return
                val viewport = viewportStartFor(to, ready.model, ready.viewportStartMs, direction)
                val row = ready.firstRowFor(to, ready.firstVisibleRow)
                publish(ready.copy(viewportStartMs = viewport, firstVisibleRow = row), to)
            }
        }

        /**
         * Select on the focused cell: tunes its channel when it is on air now, otherwise opens the info overlay for it
         * (also for a cell that has already ended but is still on the grid), fetching the details at once.
         */
        fun select() {
            synchronized(lock) {
                val current = shownSnapshot.value
                val ready = current.state as? GuideState.Ready
                val located = current.focus?.let { ready?.model?.indexOf(it) }
                if (ready == null || located == null || shownInfo.value != null) return
                val row = ready.model.rows[located.first]
                val cell = row.cells[located.second]
                val now = nowMs()
                if (cell.startMs <= now && now < cell.endMs) {
                    eventChannel.trySend(GuideEvent.Tune(row.channel))
                } else {
                    openInfo(row, cell)
                }
            }
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
            firstWindowMs = NO_MEASURE
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

        private suspend fun load(s: CoroutineScope) {
            try {
                val origin = originMs()
                val nothing = ScheduleWindow(emptyList(), complete = false)
                val cached = windowOrNull(origin, ScheduleBuild.CachedOnly, onFailure = nothing)
                val nothingToShow = cached == null || (cached.rows.isEmpty() && cached.complete)
                when {
                    nothingToShow -> publishIfCurrent(s, GuideState.Empty)
                    cached.rows.isEmpty() -> publishIfCurrent(s, GuideState.Loading)
                    else -> apply(s, cached, extension = false)
                }
                if (cached != null && !cached.complete) fillIn(s, origin)
            } finally {
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
                if (firstWindowMs == NO_MEASURE) firstWindowMs = (System.nanoTime() - started) / NANOS_PER_MS
            }
            return result
        }

        // Makes [fresh] the window. The model is built before the lock is taken. An [extension] that would end before
        // the grid does (it lost a race with something that read further) is dropped.
        private fun apply(
            s: CoroutineScope,
            fresh: ScheduleWindow,
            extension: Boolean,
        ) {
            val now = nowMs()
            val model = buildGuideModel(fresh, now)
            synchronized(lock) {
                if (session !== s) return
                val endNow = (shownSnapshot.value.state as? GuideState.Ready)?.model?.endMs
                if (extension && endNow != null && model.endMs < endNow) return
                window = fresh
                commit(model, now)
                if (tickJob == null) tickJob = s.launch { tickLoop(s) }
            }
        }

        // Ticks

        private suspend fun tickLoop(s: CoroutineScope) {
            while (true) {
                delay(synchronized(lock) { (shownSnapshot.value.state as? GuideState.Ready).nextTickDelayMs(nowMs()) })
                tick(s)
            }
        }

        /**
         * Moves the clock: rebuilds the grid at the new time, dropping cells that ended before the origin, which always
         * advances (the viewport follows it unless the viewer has scrolled away). The next tick is at the next minute
         * or at the end of the nearest block in the visible range, as it stood when the previous tick was published,
         * so a block end revealed by scrolling may be ticked up to a minute late.
         */
        private fun tick(s: CoroutineScope) {
            val built = synchronized(lock) { if (session === s) window else null } ?: return
            val now = nowMs()
            val model = buildGuideModel(built, now)
            synchronized(lock) {
                // A window that was replaced while this one was built has its own newer grid.
                if (session !== s || window !== built) return
                commit(model, now)
                extendIfNearTheEnd(s, model, now)
            }
        }

        // Extensions only ever read what is already built (never `AsNeeded`), at most once per half hour, and never
        // while the open's own load (and its fill-in) is still running.
        private fun extendIfNearTheEnd(
            s: CoroutineScope,
            model: GuideModel,
            now: Long,
        ) {
            val near = model.endMs - now < EXTEND_WITHIN.inWholeMilliseconds
            val busy = extendedFrom == model.originMs || extensionJob?.isActive == true
            if (!loaded || !near || busy) return
            extendedFrom = model.originMs
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
        }

        // Publishing

        // Shows [model] at [now], keeping focus on its cell, or on the cell that replaced it. Under the lock.
        private fun commit(
            model: GuideModel,
            now: Long,
        ) {
            val current = shownSnapshot.value
            val previous = current.state as? GuideState.Ready
            val focus = model.focusAfterRebuild(current.focus, currentChannel, now)
            // The range follows the origin unless the viewer has scrolled it away from there.
            val scrolled = previous != null && previous.viewportStartMs != previous.model.originMs
            val viewport =
                if (previous != null && scrolled) maxOf(previous.viewportStartMs, model.originMs) else model.originMs
            val shown = focus?.let { viewportStartFor(it, model, viewport, GuideDirection.Right) } ?: viewport
            val ready = GuideState.Ready(model, shown, 0, now)
            publish(ready.copy(firstVisibleRow = ready.firstRowFor(focus, previous?.firstVisibleRow ?: 0)), focus)
        }

        // One assignment, so the grid and its focus change together; then the panel follows the focus.
        private fun publish(
            ready: GuideState.Ready,
            focus: GuideFocus?,
        ) {
            val before = shownSnapshot.value.focus
            shownSnapshot.value = GuideSnapshot(ready, focus)
            follow(ready.model, before, focus)
        }

        // Details

        private fun follow(
            model: GuideModel,
            before: GuideFocus?,
            focus: GuideFocus?,
        ) {
            val located = focus?.let { model.indexOf(it) }
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
            shownPanel.value = PanelDetails(cell, row.number, row.name, cached, waiting)
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
            shownInfo.value = GuideInfo(cell, row.number, row.name, cached, waiting)
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
            const val NEVER = Long.MIN_VALUE
            const val NO_MEASURE = -1L
            const val NANOS_PER_MS = 1_000_000L
            val WINDOW = 6.hours
            val EXTEND_BY = 6.hours
            val EXTEND_WITHIN = 2.hours
        }
    }
