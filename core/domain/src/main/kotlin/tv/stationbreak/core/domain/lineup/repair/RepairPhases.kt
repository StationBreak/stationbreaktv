// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Where a selection change's repair went, in milliseconds (spec 26: the slow untick on older hardware). Numbers only:
 * never an ID or a name. -1 is "not measured": no repair ran, the step never ran, or the clock cannot tell. Each step
 * is its own time only (a step run inside another counts in its own field, never in both), so the steps add up to the
 * change's `record` time, give or take a millisecond each for rounding.
 *
 * - [warmMs]: today's days under the current selection built before the turn is asked for (a day build when they
 *   were not built yet: after an earlier selection save, say), so the turn does not build them.
 * - [daysMs]: today's days read in the turn (`chosenDays`; a day build only when they moved on while it waited).
 * - [snapsMs]: the new selection's snapshot reads.
 * - [admittedMs] and [beforeMs]: what each channel admits of the new and of the current selection (a build each).
 * - [keptMs]: the kept windows (with any layout of today's days that forces).
 * - [poolsMs]: the tail pools (with their by-id reads on a repaired day).
 * - [liveMs]: which items have a current row (the `currentIds` reads, for the kept windows and the tail pools).
 * - [layoutMs]: the tails laid out (with their films and weights).
 * - [pinMs]: finding the pinned programme's library (with the removed libraries' reads).
 * - [writeMs]: the record's write.
 * - [otherMs]: the rest of the turn (its reads of the lineup and the saved selection, the days scheduled, the titles
 *   of today's pools), not counting the caller's save.
 * - [cpuMs]: the process's CPU time over the repair, before the save (the whole process, not the turn's thread: the
 *   plan runs on the planning dispatcher's threads, and other work of the process counts in it too); [gcCount] and
 *   [gcMs]: the process's collections and their blocking time over it. -1 when the process's CPU time cannot be read.
 */
data class RepairPhases(
    val warmMs: Long = -1,
    val daysMs: Long = -1,
    val snapsMs: Long = -1,
    val admittedMs: Long = -1,
    val beforeMs: Long = -1,
    val keptMs: Long = -1,
    val poolsMs: Long = -1,
    val liveMs: Long = -1,
    val layoutMs: Long = -1,
    val pinMs: Long = -1,
    val writeMs: Long = -1,
    val otherMs: Long = -1,
    val cpuMs: Long = -1,
    val gcCount: Long = -1,
    val gcMs: Long = -1,
) {
    /** These as the timing line's fields. */
    fun line(): String =
        "warm=$warmMs days=$daysMs snaps=$snapsMs admitted=$admittedMs before=$beforeMs kept=$keptMs pools=$poolsMs " +
            "live=$liveMs layout=$layoutMs pin=$pinMs write=$writeMs other=$otherMs cpu=$cpuMs gc=$gcCount gcMs=$gcMs"

    companion object {
        /** Nothing measured: no repair ran. */
        val NONE = RepairPhases()
    }
}

/**
 * The steps a [RepairClock] times. [Other] is the turn around the rest; [Save] is the caller's save inside it, timed
 * only so that it counts in no step (the change times it itself).
 */
internal enum class RepairPhase {
    Warm,
    Days,
    Snaps,
    Admitted,
    Before,
    Kept,
    Pools,
    Live,
    Layout,
    Pin,
    Write,
    Other,
    Save,
}

/**
 * Times the steps of the selection changes a caller asks for, on [ticks], adding up over the attempts of one change
 * (a stale attempt that ran again adds to its steps). A step timed inside another is taken out of the outer one, so
 * every step is its own time only. Used by one change at a time, one step after another.
 */
class RepairClock(
    private val ticks: TimeSource = TimeSource.Monotonic,
) {
    // Nanoseconds, so many short steps add up exactly.
    private val nanos = LongArray(RepairPhase.entries.size)

    // Whether each step ran at all: one that never ran says -1.
    private val ran = BooleanArray(RepairPhase.entries.size)
    private var used = false

    // The time of the steps run inside each open step so far, innermost last, in nanoseconds.
    private val inside = ArrayList<Long>()
    private var cpuMs = 0L
    private var gcCount = 0L
    private var gcMs = 0L

    /** What was timed; [RepairPhases.NONE] when no repair ran. */
    fun phases(): RepairPhases =
        if (!used) {
            RepairPhases.NONE
        } else {
            val ms = LongArray(nanos.size) { if (ran[it]) nanos[it] / NANOS_PER_MS else -1L }
            RepairPhases(
                warmMs = ms[RepairPhase.Warm.ordinal],
                daysMs = ms[RepairPhase.Days.ordinal],
                snapsMs = ms[RepairPhase.Snaps.ordinal],
                admittedMs = ms[RepairPhase.Admitted.ordinal],
                beforeMs = ms[RepairPhase.Before.ordinal],
                keptMs = ms[RepairPhase.Kept.ordinal],
                poolsMs = ms[RepairPhase.Pools.ordinal],
                liveMs = ms[RepairPhase.Live.ordinal],
                layoutMs = ms[RepairPhase.Layout.ordinal],
                pinMs = ms[RepairPhase.Pin.ordinal],
                writeMs = ms[RepairPhase.Write.ordinal],
                otherMs = ms[RepairPhase.Other.ordinal],
                cpuMs = cpuMs,
                gcCount = gcCount,
                gcMs = gcMs,
            )
        }

    // A step starts: its clock mark.
    internal fun enter(): TimeMark {
        inside += 0L
        return ticks.markNow()
    }

    // A step ends after [elapsed]: it keeps that less the steps inside it, and the step around it, if any, counts all
    // of it as inside.
    internal fun leave(
        phase: RepairPhase,
        elapsed: Duration,
    ) {
        val total = elapsed.inWholeNanoseconds
        val nested = inside.removeAt(inside.lastIndex)
        used = true
        ran[phase.ordinal] = true
        nanos[phase.ordinal] += total - nested
        if (inside.isNotEmpty()) inside[inside.lastIndex] += total
    }

    // A reading that is unknown (-1) makes the total unknown.
    internal fun addProbes(
        cpu: Long,
        count: Long,
        blocked: Long,
    ) {
        used = true
        cpuMs = sum(cpuMs, cpu)
        gcCount = sum(gcCount, count)
        gcMs = sum(gcMs, blocked)
    }

    private fun sum(
        total: Long,
        more: Long,
    ): Long = if (total < 0 || more < 0) -1 else total + more

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** [block], timed as [phase]: a suspending step outside this module (the warm-up). */
internal suspend fun <T> RepairClock.timed(
    phase: RepairPhase,
    block: suspend () -> T,
): T = time(phase) { block() }

/** [block], timed as [phase] when there is a clock. */
internal inline fun <T> RepairClock?.time(
    phase: RepairPhase,
    block: () -> T,
): T {
    val clock = this ?: return block()
    val mark = clock.enter()
    try {
        return block()
    } finally {
        clock.leave(phase, mark.elapsedNow())
    }
}
