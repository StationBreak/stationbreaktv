// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Where a selection change's turn went, in milliseconds (spec 26: the slow untick on older hardware). Numbers only:
 * never an ID or a name. -1 is "not measured" (no repair ran, or the clock cannot tell).
 *
 * - [daysMs]: today's days under the current selection (`chosenDays`; a day build when they were not built).
 * - [snapsMs]: the new selection's snapshot reads.
 * - [admittedMs] and [beforeMs]: what each channel admits of the new and of the current selection (a build each).
 * - [keptMs]: the kept windows (with the read of which programmes are live, and any layout that forces).
 * - [poolsMs]: the tail pools (with their reads); [liveMs] is the current-row reads within [keptMs] and [poolsMs].
 * - [layoutMs]: the tails laid out (with their films and weights).
 * - [pinMs]: finding the pinned programme's library (with the removed libraries' reads).
 * - [writeMs]: the record's write.
 * - [cpuMs]: the turn thread's CPU time over the repair, before the save; [gcCount] and [gcMs]: the process's
 *   collections and their blocking time over it.
 */
data class RepairPhases(
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
    val cpuMs: Long = -1,
    val gcCount: Long = -1,
    val gcMs: Long = -1,
) {
    /** These as the timing line's fields. */
    fun line(): String =
        "days=$daysMs snaps=$snapsMs admitted=$admittedMs before=$beforeMs kept=$keptMs pools=$poolsMs " +
            "live=$liveMs layout=$layoutMs pin=$pinMs write=$writeMs cpu=$cpuMs gc=$gcCount gcMs=$gcMs"

    companion object {
        /** Nothing measured: no repair ran. */
        val NONE = RepairPhases()
    }
}

/** The steps a [RepairClock] times. */
internal enum class RepairPhase { Days, Snaps, Admitted, Before, Kept, Pools, Live, Layout, Pin, Write }

/**
 * Times the steps of the selection changes a caller asks for, on [ticks], adding up over the attempts of one change
 * (a stale attempt that ran again adds to its steps). Used by one change at a time.
 */
class RepairClock(
    private val ticks: TimeSource = TimeSource.Monotonic,
) {
    // Nanoseconds, so many short steps add up exactly.
    private val nanos = LongArray(RepairPhase.entries.size)
    private var used = false
    private var cpuMs = 0L
    private var gcCount = 0L
    private var gcMs = 0L

    /** What was timed; [RepairPhases.NONE] when no repair ran. */
    fun phases(): RepairPhases =
        if (!used) {
            RepairPhases.NONE
        } else {
            val ms = LongArray(nanos.size) { nanos[it] / NANOS_PER_MS }
            RepairPhases(
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
                cpuMs = cpuMs,
                gcCount = gcCount,
                gcMs = gcMs,
            )
        }

    internal fun mark() = ticks.markNow()

    internal fun add(
        phase: RepairPhase,
        elapsed: Duration,
    ) {
        used = true
        nanos[phase.ordinal] += elapsed.inWholeNanoseconds
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

/** [block], timed as [phase] when there is a clock. */
internal inline fun <T> RepairClock?.time(
    phase: RepairPhase,
    block: () -> T,
): T {
    val clock = this ?: return block()
    val mark = clock.mark()
    try {
        return block()
    } finally {
        clock.add(phase, mark.elapsedNow())
    }
}
