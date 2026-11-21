// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries

/**
 * The grown-up hold (ADR-0020): the name of the version of its gate, and the gate itself. A grown-up pool drops an item
 * only when a kid channel could take it ([dropsFromGrownUp]), so nothing airs nowhere because of the holds. The gate
 * is built from [KidHold.isMadeForKids] and [KidHold.passesKidCeiling], so it changes whenever either of them does:
 * `grown-up-hold-3` moves with `kid-hold-4` (the kid studio list matched by studio fold 2, ADR-0026), and
 * `grown-up-hold-4` moves with `kid-hold-5` (an episode of a rated series follows the series rating; ADR-0019 addendum
 * 2026-11-13). `grown-up-hold-5` moves with `kid-hold-6` (ADR-0028): it drops exactly the kids tier ([RatingTier]).
 * `grown-up-hold-6` moves with `kid-hold-7` (ADR-0029): the same gate, on a kids tier that the bump list narrows.
 */
object GrownUpHold {
    /** The name of this version of the grown-up gate. */
    const val VERSION_NAME = "grown-up-hold-6"

    /**
     * Whether a grown-up pool drops [entry]: it is in the kids tier ([KidHold.admits]), so some kid channel can take
     * it. Everything else (family, teen and adult titles, made for kids or not) is kept, and the usual rating ceiling
     * of the grown-up channel applies to it. Pure: it reads the entry and its [series] only.
     */
    fun dropsFromGrownUp(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = KidHold.admits(entry, series)
}
