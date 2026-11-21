// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.engine.lineup.Leftovers
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals

/** Under `kid-hold-6` (ADR-0028) every app channel reads `Approved` as unrated under a ceiling; Open is unchanged. */
class ApprovedIsUnratedTest {
    private val items =
        listOf(
            movie(idOf(1), rating = "Approved"),
            movie(idOf(2), rating = "Passed"),
            movie(idOf(3), rating = "G"),
            movie(idOf(4), rating = "PG-13"),
        )

    private fun ids(
        policy: PoolPolicy,
        ceiling: String?,
    ) = PoolEvaluator
        .evaluate(rules(listOf(Source.Library(LIB)), maxRating = ceiling), snapshot(items = items), policy)
        .items
        .map { it.id }

    @Test
    fun approvedAndPassedFailAGAndAPg13CeilingOnACustomChannel() {
        assertEquals(listOf(idOf(3)), ids(PoolPolicy.Custom, "G"))
        assertEquals(listOf(idOf(3), idOf(4)), ids(PoolPolicy.Custom, "PG-13"))
        assertEquals(listOf(idOf(3), idOf(4)), ids(PoolPolicy.GrownUp, "PG-13"))
    }

    @Test
    fun withNoCeilingEverythingStillPasses() {
        assertEquals(items.map { it.item.id }, ids(PoolPolicy.Custom, null))
    }

    // The rules-format reference (rating.json, the pool vectors) keeps rule 4: Approved ranks with G there.
    @Test
    fun theOpenReferenceIsUnchanged() {
        assertEquals(listOf(idOf(1), idOf(3)), ids(PoolPolicy.Open, "G"))
    }

    @Test
    fun aLeftoversPoolReadsApprovedTheSameWay() {
        val pool = Leftovers.pool(snapshot(items = items), emptySet(), "PG-13", PoolPolicy.Custom)
        assertEquals(listOf(idOf(3), idOf(4)), pool.items.map { it.id })
    }
}
