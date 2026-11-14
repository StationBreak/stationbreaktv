// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent

/**
 * A selection repair's kept window and its tail where they meet: the [kept] blocks, the [tail] laid after them, and
 * the pin's end ([pinEndMs], null with no kept window).
 *
 * On a day that spreads its end (rules format 5), a break on air at the pin and a tail that starts with a break (its
 * first break comes before anything fits) would be two breaks in a row. When the tail airs a programme after that
 * break, the two are joined into one kept break, and the pin's end moves to its end: the pin is still the block on air,
 * and the tail starts after it (ADR-0025, the spread-day amendment). So [pinEndMs] can run past the end of the block
 * that was on air at the repair. A tail with no programme is never joined: it stays the tail, so the day hides its
 * empty rest from the pin's end ([emptyTail]) exactly as it does after a programme pin. A day that gathers its end
 * never lays out a break, and is never joined.
 */
internal class RepairSeam(
    val kept: List<KeptBlock>,
    val tail: List<ScheduleBlock>,
    val pinEndMs: Long?,
) {
    /** The tail airs nothing: it has blocks, and none of them is a programme. */
    val emptyTail: Boolean = tail.isNotEmpty() && tail.none { it.content is ScheduleContent.Program }

    companion object {
        /** [window] and [laid] as they meet; [spreads] says the day spreads its end, the only case that can join. */
        fun of(
            window: KeptWindow?,
            laid: List<ScheduleBlock>,
            spreads: Boolean,
        ): RepairSeam {
            val kept = window?.blocks.orEmpty()
            val last = kept.lastOrNull()
            val first = laid.firstOrNull()
            val breaks = last?.content == KeptContent.Break && first?.content == ScheduleContent.Break
            val joins = spreads && breaks && laid.any { it.content is ScheduleContent.Program }
            if (!joins) return RepairSeam(kept, laid, window?.pinEndMs)
            val joined = KeptBlock(checkNotNull(last).startMs, checkNotNull(first).endMs, KeptContent.Break)
            return RepairSeam(kept.dropLast(1) + joined, laid.drop(1), first.endMs)
        }
    }
}
