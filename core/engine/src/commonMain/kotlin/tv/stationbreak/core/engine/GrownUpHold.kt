// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries

/**
 * The grown-up hold (ADR-0020): the name of the version of its gate, and the gate itself. A grown-up pool drops an item
 * only when a kid channel could take it ([dropsFromGrownUp]), so nothing airs nowhere because of the holds. The gate
 * is built from [KidHold.isMadeForKids] and [KidHold.passesKidCeiling], so it changes whenever either of them does:
 * `grown-up-hold-3` moves with `kid-hold-4` (the kid studio list matched by studio fold 2, ADR-0026).
 */
object GrownUpHold {
    /** The name of this version of the grown-up gate. */
    const val VERSION_NAME = "grown-up-hold-4"

    /**
     * Whether a grown-up pool drops [entry]: it is made for kids ([KidHold.isMadeForKids]) and passes the widest kid
     * ceiling ([KidHold.passesKidCeiling] with no ceiling, which is PG), so some kid channel can take it. A
     * made-for-kids item that fails that ceiling (unrated, or rated above what a kid channel airs) is kept, and the
     * usual rating ceiling of the grown-up channel applies to it. Pure: it reads the entry and its [series] only.
     */
    fun dropsFromGrownUp(
        entry: RuleItem,
        series: RuleSeries?,
    ): Boolean = KidHold.isMadeForKids(entry, series) && KidHold.passesKidCeiling(entry, series, null)
}
