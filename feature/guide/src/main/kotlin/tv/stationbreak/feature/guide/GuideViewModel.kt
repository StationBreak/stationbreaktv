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
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Holds the program guide (spec 17): loads the schedule window, keeps focus and the viewport, fetches the details of
 * the focused program after a short dwell, ticks the clock, and asks for the channel to be tuned.
 *
 * Focus, the details panel and the info overlay are separate flows from [state], so a key press does not recompose the
 * grid. They are never inconsistent with it: a focus is only ever published next to a model that holds its cell
 * (focus is by identity, see [GuideFocus]), and when one update changes both, the focus goes to null first, so no
 * collector can see a focus from one model next to another.
 *
 * Each [open] starts a session that [close] (or the next [open]) ends; the session owns the clock tick, the fill-in,
 * the extensions and every details request, so nothing runs while the guide is closed. State is changed under [lock]:
 * the keys arrive on the main thread and the session works on [dispatcher].
 */
@Suppress("TooManyFunctions") // One session's steps (load, tick, extend, publish, follow), each small.
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

        private val shownState = MutableStateFlow<GuideState>(GuideState.Closed)
        private val shownFocus = MutableStateFlow<GuideFocus?>(null)
        private val shownPanel = MutableStateFlow<PanelDetails?>(null)
        private val shownInfo = MutableStateFlow<GuideInfo?>(null)
        private val eventChannel = Channel<GuideEvent>(Channel.BUFFERED)

        /** The grid: the model, the visible time range and rows, and the time of the "now" line. */
        val state: StateFlow<GuideState> = shownState.asStateFlow()

        /** The focused cell. Always a cell of the model in [state] it is next to, or null. */
        val focus: StateFlow<GuideFocus?> = shownFocus.asStateFlow()

        /** The details of the focused cell, filled in after [DWELL_MS] of staying on it. */
        val panel: StateFlow<PanelDetails?> = shownPanel.asStateFlow()

        /** The info overlay, while one is open. */
        val info: StateFlow<GuideInfo?> = shownInfo.asStateFlow()

        /** Things for the screen to do once, in order. */
        val events: Flow<GuideEvent> = eventChannel.receiveAsFlow()

        private val lock = Any()
        private val root = SupervisorJob()

        // Everything below is read and written under [lock].
        private var session: CoroutineScope? = null
        private var window: ScheduleWindow? = null
        private var currentChannel: ChannelId? = null
        private var tickJob: Job? = null
        private var dwellJob: Job? = null
        private var infoJob: Job? = null
        private var extensionJob: Job? = null
        private var extendedFrom = NEVER

        /** Opens the guide with the cell on air now on [current] focused (or the first channel's, when unknown). */
        fun open(current: ChannelId?) {
            val started =
                synchronized(lock) {
                    endSession()
                    publishClosed(GuideState.Loading)
                    currentChannel = current
                    CoroutineScope(dispatcher + SupervisorJob(root)).also { session = it }
                }
            started.launch { load(started) }
        }

        /** Closes the guide and stops everything its session was doing. */
        fun close() {
            synchronized(lock) {
                endSession()
                publishClosed(GuideState.Closed)
            }
        }

        /** One step of focus. Ignored while the info overlay is open, which owns the keys. */
        fun move(direction: GuideDirection) {
            synchronized(lock) {
                val ready = shownState.value as? GuideState.Ready
                val from = shownFocus.value
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
                val ready = shownState.value as? GuideState.Ready
                val focus = shownFocus.value
                val located = if (ready == null || focus == null) null else ready.model.indexOf(focus)
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
                shownInfo.value = null
                true
            }

        override fun onCleared() {
            root.cancel()
        }

        // Sessions

        private fun endSession() {
            session?.coroutineContext?.get(Job)?.cancel()
            session = null
            window = null
            tickJob = null
            dwellJob = null
            infoJob = null
            extensionJob = null
            extendedFrom = NEVER
        }

        // Focus first, so no collector sees a focus beside a state that is not a grid.
        private fun publishClosed(to: GuideState) {
            shownFocus.value = null
            shownPanel.value = null
            shownInfo.value = null
            shownState.value = to
        }

        private suspend fun load(s: CoroutineScope) {
            val origin = originMs()
            val nothing = ScheduleWindow(emptyList(), complete = false)
            val cached = windowOrNull(origin, ScheduleBuild.CachedOnly, onFailure = nothing)
            if (cached == null) {
                installIfCurrent(s, null, fromFillIn = false)
                return
            }
            installIfCurrent(s, cached, fromFillIn = false)
            if (cached.complete) return
            // Final: an incomplete window after the build means a channel stopped airing or the lineup changed.
            installIfCurrent(s, windowOrNull(origin, ScheduleBuild.AsNeeded, onFailure = null), fromFillIn = true)
        }

        private suspend fun windowOrNull(
            fromMs: Long,
            build: ScheduleBuild,
            onFailure: ScheduleWindow?,
            untilMs: Long = fromMs + WINDOW.inWholeMilliseconds,
        ): ScheduleWindow? =
            try {
                schedule.schedule(Instant.fromEpochMilliseconds(fromMs), Instant.fromEpochMilliseconds(untilMs), build)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The port reports a lineup that cannot be read as null; anything else is the same to the guide.
                onFailure
            }

        private fun installIfCurrent(
            s: CoroutineScope,
            fresh: ScheduleWindow?,
            fromFillIn: Boolean,
        ) {
            synchronized(lock) {
                if (session !== s) return
                val loading = shownState.value == GuideState.Loading
                when {
                    fresh == null || (fresh.rows.isEmpty() && (fresh.complete || fromFillIn)) -> {
                        // Nothing to show. A guide that already has a grid keeps it when a later look comes back empty.
                        if (loading) publishClosed(GuideState.Empty)
                    }

                    fresh.rows.isEmpty() -> {
                        Unit
                    }

                    else -> {
                        window = fresh
                        install(buildGuideModel(fresh, nowMs()), nowMs())
                        if (tickJob == null) tickJob = s.launch { tickLoop(s) }
                    }
                }
            }
        }

        // Ticks

        private suspend fun tickLoop(s: CoroutineScope) {
            while (true) {
                delay(synchronized(lock) { (shownState.value as? GuideState.Ready).nextTickDelayMs(nowMs()) })
                synchronized(lock) { if (session === s) tick(s) }
            }
        }

        private fun tick(s: CoroutineScope) {
            val current = window ?: return
            val now = nowMs()
            install(buildGuideModel(current, now), now)
            extendIfNearTheEnd(s, now)
        }

        // Extensions only ever read what is already built (never `AsNeeded`), and at most once per half hour.
        private fun extendIfNearTheEnd(
            s: CoroutineScope,
            now: Long,
        ) {
            val model = (shownState.value as? GuideState.Ready)?.model ?: return
            val near = model.endMs - now < EXTEND_WITHIN.inWholeMilliseconds
            if (!near || extendedFrom == model.originMs || extensionJob?.isActive == true) return
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
                    if (longer != null && longer.rows.isNotEmpty()) installIfCurrent(s, longer, fromFillIn = false)
                }
        }

        // Publishing

        // Rebuilds the grid for [model] at [now], keeping focus on its cell, or on the cell that replaced it.
        private fun install(
            model: GuideModel,
            now: Long,
        ) {
            val previous = shownState.value as? GuideState.Ready
            val focus = model.focusAfterRebuild(shownFocus.value, currentChannel, now)
            // The range follows the origin unless the viewer has scrolled it away from there.
            val scrolled = previous != null && previous.viewportStartMs != previous.model.originMs
            val viewport =
                if (previous != null && scrolled) maxOf(previous.viewportStartMs, model.originMs) else model.originMs
            val shown = focus?.let { viewportStartFor(it, model, viewport, GuideDirection.Right) } ?: viewport
            val ready = GuideState.Ready(model, shown, 0, now)
            publish(ready.copy(firstVisibleRow = ready.firstRowFor(focus, previous?.firstVisibleRow ?: 0)), focus)
        }

        // Publishes [ready] and [focus] so that every focus is beside a model that holds it, then follows the focus.
        private fun publish(
            ready: GuideState.Ready,
            focus: GuideFocus?,
        ) {
            val before = shownFocus.value
            val modelChanged = (shownState.value as? GuideState.Ready)?.model !== ready.model
            if (modelChanged && before != focus) shownFocus.value = null
            shownState.value = ready
            shownFocus.value = focus
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
                            val now = shownFocus.value
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
            shownInfo.value = GuideInfo(cell, row.number, row.name, cached, waiting)
            infoJob =
                if (id != null && waiting) {
                    session?.launch {
                        val item = fetch(id)
                        synchronized(lock) {
                            val open = shownInfo.value
                            if (open != null && open.cell.startMs == cell.startMs && open.channelNumber == row.number) {
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
            val WINDOW = 6.hours
            val EXTEND_BY = 6.hours
            val EXTEND_WITHIN = 2.hours
        }
    }
