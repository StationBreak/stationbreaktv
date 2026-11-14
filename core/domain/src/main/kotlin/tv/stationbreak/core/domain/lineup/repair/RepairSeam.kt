// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent

/**
 * A selection repair's kept window and its tail where they meet: the [kept] blocks, the [tail] laid after them, and
 * the pin's end ([pinEndMs], null with no kept window).
 *
 * A break on air at the pin and a tail that starts with a break (a spread tail, rules format 5, whose first break comes
 * before anything fits) would be two breaks in a row. They are joined into one kept break, and the pin's end moves to
 * its end: the pin is still the block on air, and the tail starts after it (ADR-0025, the spread-day amendment).
 */
internal class RepairSeam(
    val kept: List<KeptBlock>,
    val tail: List<ScheduleBlock>,
    val pinEndMs: Long?,
) {
    companion object {
        fun of(
            window: KeptWindow?,
            laid: List<ScheduleBlock>,
        ): RepairSeam {
            val kept = window?.blocks.orEmpty()
            val last = kept.lastOrNull()
            val first = laid.firstOrNull()
            if (last?.content != KeptContent.Break || first?.content != ScheduleContent.Break) {
                return RepairSeam(kept, laid, window?.pinEndMs)
            }
            val joined = KeptBlock(last.startMs, first.endMs, KeptContent.Break)
            return RepairSeam(kept.dropLast(1) + joined, laid.drop(1), first.endMs)
        }
    }
}
