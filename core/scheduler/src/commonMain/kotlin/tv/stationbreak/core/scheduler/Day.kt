// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.LeftoverPolicy
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.model.schedule.TitleKeeps
import tv.stationbreak.core.model.schedule.TitleWeights

/** What is on at one moment: the block, how far into the media, and the blocks that follow it in the same day. */
data class OnAir(
    val block: ScheduleBlock,
    val offsetMs: Long?,
    val next: List<ScheduleBlock>,
)

/**
 * How a day used the cycle: the run it starts at, the number of runs the regular layout started, and the number of
 * runs in the cycle (those not left out).
 *
 * A day composed from given blocks (`composedDay`) carries `RunPlan(0, 0, 0)`.
 *
 * A test seam: nothing in production reads it. The continuity and end-fill tests need it to tell the regular layout
 * from the end-of-day fill, which the blocks alone do not say.
 */
internal class RunPlan(
    val startRun: Int,
    val regularRuns: Int,
    val cycleRuns: Int,
)

/** One built day (epoch [n]): contiguous half-open blocks that cover exactly `[startMs, endMs)`. */
@Suppress("LongParameterList") // The day's facts, one for one.
class Day internal constructor(
    val n: Long,
    val startMs: Long,
    val endMs: Long,
    val blocks: List<ScheduleBlock>,
    /** The pool the day was laid out from: the pool of epoch [n] after the trim window. Keep this, not the input. */
    val pool: List<ScheduleItem>,
    private val plan: RunPlan,
    // A test seam: called once per comparison of the binary search, so WhatsOnStepsTest counts steps without a
    // clock.
    // Production code builds days with buildDay, which passes null.
    private val onCompare: (() -> Unit)? = null,
) {
    // Test seams (see RunPlan): internal, so only this module's tests read them.
    internal val startRunIndex: Int get() = plan.startRun
    internal val regularRunCount: Int get() = plan.regularRuns
    internal val cycleRunCount: Int get() = plan.cycleRuns

    /**
     * What is on at [t] (milliseconds since the Unix epoch), by binary search over the day's blocks:
     * - the block containing [t]; an edge belongs to the later block;
     * - the position in the media, `mediaStartOffset + (t - blockStart)`, or null for a slate;
     * - up to [k] blocks that follow it in this day. When they run out the caller builds day `n + 1` from the same
     *   items.
     *
     * [t] must be in `[startMs, endMs)`: the caller picks the day. Throws [IllegalArgumentException] otherwise.
     */
    fun whatsOn(
        t: Long,
        k: Int,
    ): OnAir {
        require(t >= startMs && t < endMs) { "Time $t is outside this day [$startMs, $endMs)" }
        require(k >= 0) { "Cannot ask for a negative number of next blocks" }
        // The last block that starts at or before t. blocks[0] starts at startMs <= t, so the answer exists.
        var low = 0
        var high = blocks.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            onCompare?.invoke()
            if (blocks[mid].startMs <= t) low = mid else high = mid - 1
        }
        val block = blocks[low]
        val content = block.content
        val offset = if (content is ScheduleContent.Program) content.mediaStartOffsetMs + (t - block.startMs) else null
        return OnAir(block, offset, blocks.subList(low + 1, minOf(blocks.size.toLong(), low + 1L + k).toInt()).toList())
    }
}

/** The largest time the format allows, in milliseconds: 2^52 (docs/rules-format.md, "Numbers and ranges"). */
private const val MAX_TIME_MS = 1L shl 52

/** `buildDay` takes fewer items than this: 2^26, so that the sum of the slots of a cycle stays below 2^53. */
private const val MAX_ITEMS = 1 shl 26

/**
 * Builds day [n] from [items], which are already the pool of the day: no trim window is applied (a caller that keeps
 * the day's pool windows it once, with `EpochPool.dayPool`, when the day is made). Throws [IllegalArgumentException],
 * building nothing, when the input is out of range: 2^26 items or more, a `createdAt` or `deletedAt` beyond +-2^52 ms,
 * or a day whose start or end is. Filtering bad items is the caller's job. [films] is the film-to-collection side input
 * (the orders of rules format version 2 read it; an empty map is every film on its own). [weights] is the side input
 * of rules format version 3: how often a title comes round in `curated`. [TitleWeights.NONE] gives the day of a
 * version 2 scheduler.
 */
fun buildDay(
    settings: SchedulerSettings,
    items: List<ScheduleItem>,
    n: Long,
    films: FilmCollections,
    weights: TitleWeights,
): Day = buildDayCounting(settings, items, n, films, weights, TitleKeeps.NONE, null)

/**
 * [buildDay] with the trim window of [keeps] applied to the pool of the day (docs/rules-format.md, "The pool of an
 * epoch"): for the scheduler's own tests and vectors. Internal, so a lineup's day is windowed in one place only.
 */
@Suppress("LongParameterList") // The day's inputs, one for one.
internal fun buildDay(
    settings: SchedulerSettings,
    items: List<ScheduleItem>,
    n: Long,
    films: FilmCollections,
    weights: TitleWeights,
    keeps: TitleKeeps,
): Day = buildDayCounting(settings, items, n, films, weights, keeps, null)

/** [buildDay] with a comparison counter injected into the day. A test seam, used by WhatsOnStepsTest. */
@Suppress("LongParameterList") // buildDay's inputs plus the seam.
internal fun buildDayCounting(
    settings: SchedulerSettings,
    items: List<ScheduleItem>,
    n: Long,
    films: FilmCollections,
    weights: TitleWeights,
    keeps: TitleKeeps,
    onCompare: (() -> Unit)?,
): Day {
    require(items.size < MAX_ITEMS) { "At most ${MAX_ITEMS - 1} items may be given, got ${items.size}" }
    val start = Epochs.epochStart(settings, n)
    val end = start + settings.epochMs
    require(start in -MAX_TIME_MS..MAX_TIME_MS && end in -MAX_TIME_MS..MAX_TIME_MS) {
        "Day $n is outside the valid times (-2^52 to 2^52 ms)"
    }
    for (item in items) {
        require(item.createdAtMs in -MAX_TIME_MS..MAX_TIME_MS) { "Item ${item.id.value}: createdAt is out of range" }
        val deleted = item.deletedAtMs
        require(deleted == null || deleted in -MAX_TIME_MS..MAX_TIME_MS) {
            "Item ${item.id.value}: deletedAt is out of range"
        }
    }
    val pool = EpochPool.dayPool(EpochPool.build(settings, items, n), n, keeps)
    val groups = GroupRules(films, weights)
    val cycle = Timeline.cycle(settings, pool, Epochs.seedN(settings.seed, n), groups)
    if (cycle.isEmpty()) {
        val slate = listOf(ScheduleBlock(start, end, ScheduleContent.Slate))
        return Day(n, start, end, slate, pool, RunPlan(0, 0, 0), onCompare)
    }
    val startRun = Timeline.startRun(settings, cycle, start)
    val layout = Timeline.layout(settings, cycle, startRun, start, end, groups)
    return Day(n, start, end, layout.blocks, pool, RunPlan(startRun, layout.runsStarted, cycle.size), onCompare)
}

/** The day's layout, step by step (spec 08, Unit 3). Pure functions of their inputs. */
internal object Timeline {
    /** The laid-out blocks, how many runs the regular layout started, and where each of those runs ended. */
    class Layout(
        val blocks: List<ScheduleBlock>,
        val runsStarted: Int,
        val runEnds: List<Long>,
    )

    /** A run's slot: its runtime, rounded up to a whole number of grid slots when aligned. */
    fun slotMs(
        settings: SchedulerSettings,
        run: Run,
    ): Long =
        if (settings.alignToGrid) {
            (run.runtimeMs + settings.gridMs - 1).floorDiv(settings.gridMs) * settings.gridMs
        } else {
            run.runtimeMs
        }

    /** One cycle of the pool's runs, without the runs whose slot is longer than the epoch. */
    fun cycle(
        settings: SchedulerSettings,
        pool: List<ScheduleItem>,
        seedN: ULong,
        groups: GroupRules,
    ): List<Run> = Runs.cycle(settings, pool, seedN, groups).filter { slotMs(settings, it) <= settings.epochMs }

    /**
     * The run a day starts at. `shuffle` and `curated` start at run 0, because each day has its own seed. The other
     * modes start at the run of the channel's continuous playout (the cycle repeated forever, each run taking its
     * slot, from the origin `-offsetMs`) that contains [dayStart]: with the prefix sums `Q[0] = 0 < ... < Q[B] = C` of
     * the slots and
     * `pos = floorMod(dayStart + offsetMs, C)`, the largest `j` with `Q[j] <= pos`, found by binary search.
     */
    fun startRun(
        settings: SchedulerSettings,
        cycle: List<Run>,
        dayStart: Long,
    ): Int {
        if (settings.orderingMode == OrderingMode.Shuffle || settings.orderingMode == OrderingMode.Curated) return 0
        val prefix = LongArray(cycle.size + 1)
        for (i in cycle.indices) prefix[i + 1] = prefix[i] + slotMs(settings, cycle[i])
        val pos = (dayStart + settings.offsetMs).mod(prefix[cycle.size])
        // The largest j in [0, B - 1] with prefix[j] <= pos. prefix[0] = 0 <= pos always holds.
        var low = 0
        var high = cycle.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (prefix[mid] <= pos) low = mid else high = mid - 1
        }
        return low
    }

    /** The first grid mark at or after [t] when aligned, else [t]. */
    fun startAtOrAfter(
        settings: SchedulerSettings,
        t: Long,
    ): Long = if (settings.alignToGrid) t + (-(t + settings.offsetMs)).mod(settings.gridMs) else t

    /**
     * Lays the day out: runs from [startRun] at [dayStart], each at the first grid mark at or after the previous run's
     * end when aligned. When the next run would end after [dayEnd], the end of the day is planned by [fillEnd]
     * ([LeftoverPolicy.Gather]) or by [spread] ([LeftoverPolicy.Spread]), which also reads [airedBefore]: what aired
     * before [dayStart] in a repair's tail, empty for a whole day.
     */
    @Suppress("LongParameterList") // The day's window, the group rules and what aired are each an input.
    fun layout(
        settings: SchedulerSettings,
        cycle: List<Run>,
        startRun: Int,
        dayStart: Long,
        dayEnd: Long,
        groups: GroupRules,
        airedBefore: List<AiredBefore> = emptyList(),
    ): Layout {
        val blocks = ArrayList<ScheduleBlock>()
        val runEnds = ArrayList<Long>()
        val spreads = settings.leftover == LeftoverPolicy.Spread
        val aired = if (spreads) ArrayList(airedBefore) else null
        var cursor = dayStart
        var index = startRun
        var started = 0
        var unstarted = -1
        while (cursor < dayEnd) {
            val run = cycle[index]
            val runStart = startAtOrAfter(settings, cursor)
            if (runStart + run.runtimeMs > dayEnd) {
                unstarted = index
                break
            }
            if (runStart > cursor) blocks.add(ScheduleBlock(cursor, runStart, ScheduleContent.Slate))
            cursor = runStart
            for (item in run.items) {
                blocks.add(ScheduleBlock(cursor, cursor + item.runtimeMs, ScheduleContent.Program(item.id)))
                cursor += item.runtimeMs
                aired?.add(AiredBefore(item, cursor))
            }
            runEnds.add(cursor)
            started++
            index = (index + 1) % cycle.size
        }
        if (aired != null) {
            val spread = spreadEnd(settings, cycle, unstarted, blocks, runEnds, aired, groups, dayStart, dayEnd)
            return Layout(spread, started, runEnds)
        }
        if (unstarted >= 0) {
            val filled = fillEnd(settings, cycle, unstarted, cursor, dayEnd, groups)
            blocks.addAll(filled)
            cursor = filled.lastOrNull()?.endMs ?: cursor
        }
        if (cursor < dayEnd) blocks.add(ScheduleBlock(cursor, dayEnd, ScheduleContent.Slate))
        return Layout(blocks, started, runEnds)
    }

    /**
     * [spread] over the visit of the planned end: from the first item of the run that did not fit ([unstarted]), the
     * cycle's items, wrapping in `shuffle` and `curated` and stopping at the cycle's end in the other modes; no visit
     * when every run fitted.
     */
    @Suppress("LongParameterList") // The layout's inputs and what the regular layout made.
    private fun spreadEnd(
        settings: SchedulerSettings,
        cycle: List<Run>,
        unstarted: Int,
        regular: List<ScheduleBlock>,
        runEnds: List<Long>,
        aired: List<AiredBefore>,
        groups: GroupRules,
        dayStart: Long,
        dayEnd: Long,
    ): List<ScheduleBlock> {
        val items = cycle.flatMap { it.items }
        val firstItem = if (unstarted < 0) 0 else (0 until unstarted).sumOf { cycle[it].items.size }
        val wraps = settings.orderingMode == OrderingMode.Shuffle || settings.orderingMode == OrderingMode.Curated
        val visit =
            when {
                unstarted < 0 -> emptyList()
                wraps -> List(items.size) { items[(firstItem + it) % items.size] }
                else -> items.subList(firstItem, items.size)
            }
        return spread(settings, regular, runEnds, visit, firstItem, aired, groups, dayStart, dayEnd)
    }

    /**
     * The planned end of the day (single pass). Starting at the first item of the run that would not fit
     * ([unstarted]), visits at most `N` items of the cycle (`N` counts only the runs that were not left out). `shuffle`
     * and `curated` wrap; the other modes stop at the cycle's end, so an earlier episode is never replayed after a
     * later one. Each item starts at the end of the previous block, moved to the next grid mark when aligned, and is
     * placed when its start plus its actual runtime is no later than [dayEnd]. For the other modes, once an item of a
     * group has been passed over no later item of that group is eligible. Rotation's group is the one [groups] gives (a
     * collection is one group); the other modes' is the series or the item. Returns the blocks placed (with the slates
     * for grid gaps before them); the caller adds the closing slate.
     */
    @Suppress("LongParameterList") // The same inputs as the layout, plus where the fill starts.
    private fun fillEnd(
        settings: SchedulerSettings,
        cycle: List<Run>,
        unstarted: Int,
        from: Long,
        dayEnd: Long,
        groups: GroupRules,
    ): List<ScheduleBlock> {
        val items = cycle.flatMap { it.items }
        val firstItem = (0 until unstarted).sumOf { cycle[it].items.size }
        val wraps = settings.orderingMode == OrderingMode.Shuffle || settings.orderingMode == OrderingMode.Curated
        val visits = if (wraps) items.size else items.size - firstItem
        val rotation = settings.orderingMode == OrderingMode.Rotation
        val passedOver = HashSet<CanonicalId>()
        val blocks = ArrayList<ScheduleBlock>()
        var cursor = from
        for (visit in 0 until visits) {
            val item = items[(firstItem + visit) % items.size]
            val group = if (rotation) groups.groupId(item) else item.groupId
            if (!wraps && group in passedOver) continue
            val start = startAtOrAfter(settings, cursor)
            if (start + item.runtimeMs <= dayEnd) {
                if (start > cursor) blocks.add(ScheduleBlock(cursor, start, ScheduleContent.Slate))
                blocks.add(ScheduleBlock(start, start + item.runtimeMs, ScheduleContent.Program(item.id)))
                cursor = start + item.runtimeMs
            } else if (!wraps) {
                passedOver.add(group)
            }
        }
        return blocks
    }
}
